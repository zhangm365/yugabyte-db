// Copyright (c) YugaByte, Inc.
package com.yugabyte.yw.commissioner.tasks;

import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.UserTaskDetails;
import com.yugabyte.yw.common.XClusterUniverseService;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.XClusterConfig;
import com.yugabyte.yw.models.XClusterConfig.XClusterConfigStatusType;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DeleteXClusterConfig extends XClusterConfigTaskBase {

  private final XClusterUniverseService xClusterUniverseService;

  @Inject
  protected DeleteXClusterConfig(
      BaseTaskDependencies baseTaskDependencies, XClusterUniverseService xClusterUniverseService) {
    super(baseTaskDependencies);
    this.xClusterUniverseService = xClusterUniverseService;
  }

  @Override
  public void run() {
    log.info("Running {}", getName());

    XClusterConfig xClusterConfig = getXClusterConfigFromTaskParams();

    Universe sourceUniverse = null;
    Universe targetUniverse = null;
    if (xClusterConfig.getSourceUniverseUUID() != null) {
      sourceUniverse = Universe.getOrBadRequest(xClusterConfig.getSourceUniverseUUID());
    }
    if (xClusterConfig.getTargetUniverseUUID() != null) {
      targetUniverse = Universe.getOrBadRequest(xClusterConfig.getTargetUniverseUUID());
    }
    try {
      if (sourceUniverse != null) {
        // Lock the source universe.
        lockUniverseForUpdate(sourceUniverse.getUniverseUUID(), sourceUniverse.getVersion());
      }
      try {
        if (targetUniverse != null) {
          // Lock the target universe.
          lockUniverseForUpdate(targetUniverse.getUniverseUUID(), targetUniverse.getVersion());
        }

        if (!isInMustDeleteStatus(xClusterConfig)) {
          createXClusterConfigSetStatusTask(XClusterConfig.XClusterConfigStatusType.Updating)
              .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.DeleteXClusterReplication);
        }

        // Create all the subtasks to delete the xCluster config and all the bootstrap ids related
        // to them if any.
        createDeleteXClusterConfigSubtasks(xClusterConfig, taskParams().isForced());

        // Fetch all universes that are connected through xCluster config to source and
        // target universe.
        Set<Universe> xClusterConnectedUniverseSet = new HashSet<>();
        Set<UUID> alreadyLockedUniverseUUIDSet = new HashSet<>();
        if (sourceUniverse != null) {
          xClusterConnectedUniverseSet.addAll(
              xClusterUniverseService.getXClusterConnectedUniverses(sourceUniverse));
          alreadyLockedUniverseUUIDSet.add(sourceUniverse.getUniverseUUID());
        }
        if (targetUniverse != null) {
          xClusterConnectedUniverseSet.addAll(
              xClusterUniverseService.getXClusterConnectedUniverses(targetUniverse));
          alreadyLockedUniverseUUIDSet.add(targetUniverse.getUniverseUUID());
        }

        // Promote auto flags on all connected universes which were blocked
        // due to the xCluster config.
        createPromoteAutoFlagsAndLockOtherUniversesForUniverseSet(
            xClusterConnectedUniverseSet.stream()
                .map(Universe::getUniverseUUID)
                .collect(Collectors.toSet()),
            alreadyLockedUniverseUUIDSet,
            xClusterUniverseService,
            Collections.singleton(xClusterConfig.getUuid()));

        if (targetUniverse != null) {
          createMarkUniverseUpdateSuccessTasks(targetUniverse.getUniverseUUID())
              .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse);
        }

        if (sourceUniverse != null) {
          createMarkUniverseUpdateSuccessTasks(sourceUniverse.getUniverseUUID())
              .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse);
        }

        getRunnableTask().runSubTasks();
      } finally {
        if (targetUniverse != null) {
          // Unlock the target universe.
          unlockUniverseForUpdate(targetUniverse.getUniverseUUID());
        }
        unlockXClusterUniverses(lockedXClusterUniversesUuidSet, false /* force delete */);
      }
    } catch (Exception e) {
      log.error("{} hit error : {}", getName(), e.getMessage());
      Optional<XClusterConfig> mightDeletedXClusterConfig = maybeGetXClusterConfig();
      if (mightDeletedXClusterConfig.isPresent()
          && !isInMustDeleteStatus(mightDeletedXClusterConfig.get())) {
        setXClusterConfigStatus(XClusterConfigStatusType.DeletionFailed);
      }
      throw new RuntimeException(e);
    } finally {
      if (sourceUniverse != null) {
        // Unlock the source universe.
        unlockUniverseForUpdate(sourceUniverse.getUniverseUUID());
      }
    }

    log.info("Completed {}", getName());
  }
}
