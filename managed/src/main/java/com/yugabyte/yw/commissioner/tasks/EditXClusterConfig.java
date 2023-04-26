// Copyright (c) YugaByte, Inc.
package com.yugabyte.yw.commissioner.tasks;

import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.UserTaskDetails;
import com.yugabyte.yw.commissioner.tasks.subtasks.xcluster.XClusterConfigModifyTables;
import com.yugabyte.yw.forms.XClusterConfigEditFormData;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.XClusterConfig;
import com.yugabyte.yw.models.XClusterConfig.XClusterConfigStatusType;
import com.yugabyte.yw.models.XClusterTableConfig;
import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.yb.master.MasterDdlOuterClass;

@Slf4j
public class EditXClusterConfig extends CreateXClusterConfig {

  @Inject
  protected EditXClusterConfig(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  @Override
  public void run() {
    log.info("Running {}", getName());

    XClusterConfig xClusterConfig = getXClusterConfigFromTaskParams();
    Universe sourceUniverse = Universe.getOrBadRequest(xClusterConfig.getSourceUniverseUUID());
    Universe targetUniverse = Universe.getOrBadRequest(xClusterConfig.getTargetUniverseUUID());
    XClusterConfigEditFormData editFormData = taskParams().getEditFormData();

    // Lock the source universe.
    lockUniverseForUpdate(sourceUniverse.getUniverseUUID(), sourceUniverse.getVersion());
    try {
      // Lock the target universe.
      lockUniverseForUpdate(targetUniverse.getUniverseUUID(), targetUniverse.getVersion());
      try {

        // Check Auto flags on source and target universes while resuming xCluster.
        if (editFormData.status != null && editFormData.status.equals("Running")) {
          createCheckXUniverseAutoFlag(sourceUniverse, targetUniverse)
              .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.PreflightChecks);
        }

        createXClusterConfigSetStatusTask(XClusterConfigStatusType.Updating)
            .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse);

        if (editFormData.name != null) {
          String oldReplicationGroupName = xClusterConfig.getReplicationGroupName();
          // If TLS root certificates are different, create a directory containing the source
          // universe root certs with the new name.
          Optional<File> sourceCertificate =
              getSourceCertificateIfNecessary(sourceUniverse, targetUniverse);
          sourceCertificate.ifPresent(
              cert ->
                  createTransferXClusterCertsCopyTasks(
                      xClusterConfig,
                      targetUniverse.getNodes(),
                      xClusterConfig.getNewReplicationGroupName(
                          xClusterConfig.getSourceUniverseUUID(), editFormData.name),
                      cert,
                      targetUniverse.getUniverseDetails().getSourceRootCertDirPath()));

          createXClusterConfigRenameTask(editFormData.name)
              .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse);

          // Delete the old directory if it created a new one. When the old directory is removed
          // because of renaming, the directory for transactional replication must not be deleted.
          sourceCertificate.ifPresent(
              cert ->
                  createTransferXClusterCertsRemoveTasks(
                      xClusterConfig,
                      oldReplicationGroupName,
                      targetUniverse.getUniverseDetails().getSourceRootCertDirPath(),
                      false /* ignoreErrors */,
                      true /* skipRemoveTransactionalCert */));
        } else if (editFormData.status != null) {
          createSetReplicationPausedTask(editFormData.status)
              .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse);
        } else if (editFormData.sourceRole != null || editFormData.targetRole != null) {
          createChangeXClusterRoleTask(
              taskParams().getXClusterConfig(), editFormData.sourceRole, editFormData.targetRole);
        } else if (editFormData.tables != null) {
          if (!CollectionUtils.isEmpty(taskParams().getTableInfoList())) {
            createXClusterConfigSetStatusForTablesTask(
                taskParams().getTableIdsToAdd(), XClusterTableConfig.Status.Updating);
            addSubtasksToAddTablesToXClusterConfig(
                sourceUniverse,
                targetUniverse,
                taskParams().getTableInfoList(),
                taskParams().getMainTableIndexTablesMap(),
                Collections.emptySet() /* tableIdsScheduledForBeingRemoved */,
                taskParams().getTxnTableInfo());
          }

          if (!CollectionUtils.isEmpty(taskParams().getTableIdsToRemove())) {
            createXClusterConfigSetStatusForTablesTask(
                taskParams().getTableIdsToRemove(), XClusterTableConfig.Status.Updating);
            createXClusterConfigModifyTablesTask(
                    taskParams().getTableIdsToRemove(),
                    XClusterConfigModifyTables.Params.Action.DELETE)
                .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse);
          }
        } else {
          throw new RuntimeException("No edit operation was specified in editFormData");
        }

        createXClusterConfigSetStatusTask(XClusterConfigStatusType.Running)
            .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse);

        createMarkUniverseUpdateSuccessTasks(targetUniverse.getUniverseUUID())
            .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse);

        createMarkUniverseUpdateSuccessTasks(sourceUniverse.getUniverseUUID())
            .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse);

        getRunnableTask().runSubTasks();
      } finally {
        // Unlock the target universe.
        unlockUniverseForUpdate(targetUniverse.getUniverseUUID());
      }
    } catch (Exception e) {
      log.error("{} hit error : {}", getName(), e.getMessage());
      setXClusterConfigStatus(XClusterConfigStatusType.Running);
      if (editFormData.tables != null) {
        // Set tables in updating status to failed.
        Set<String> tablesInPendingStatus =
            xClusterConfig.getTableIdsInStatus(
                Stream.concat(
                        getTableIds(taskParams().getTableInfoList()).stream(),
                        taskParams().getTableIdsToRemove().stream())
                    .collect(Collectors.toSet()),
                X_CLUSTER_TABLE_CONFIG_PENDING_STATUS_LIST);
        xClusterConfig.updateStatusForTables(
            tablesInPendingStatus, XClusterTableConfig.Status.Failed);
      }
      throw new RuntimeException(e);
    } finally {
      // Unlock the source universe.
      unlockUniverseForUpdate(sourceUniverse.getUniverseUUID());
    }

    log.info("Completed {}", getName());
  }

  protected void addSubtasksToAddTablesToXClusterConfig(
      Universe sourceUniverse,
      Universe targetUniverse,
      List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> requestedTableInfoList,
      Map<String, List<String>> mainTableIndexTablesMap,
      Set<String> tableIdsScheduledForBeingRemoved,
      @Nullable MasterDdlOuterClass.ListTablesResponsePB.TableInfo txnTableInfo) {
    Map<String, List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo>>
        dbToTablesInfoMapNeedBootstrap =
            getDbToTablesInfoMapNeedBootstrap(
                taskParams().getTableIdsToAdd(),
                requestedTableInfoList,
                mainTableIndexTablesMap,
                null /* txnTableInfo */);

    // Replication for tables that do NOT need bootstrapping.
    Set<String> tableIdsNotNeedBootstrap =
        getTableIdsNotNeedBootstrap(taskParams().getTableIdsToAdd());
    if (!tableIdsNotNeedBootstrap.isEmpty()) {
      log.info(
          "Creating a subtask to modify replication to add tables without bootstrap for "
              + "tables {}",
          tableIdsNotNeedBootstrap);
      createXClusterConfigModifyTablesTask(
              tableIdsNotNeedBootstrap, XClusterConfigModifyTables.Params.Action.ADD)
          .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse);
    }

    // YSQL tables replication with bootstrapping can only be set up with DB granularity. The
    // following subtasks remove tables in replication, so the replication can be set up again
    // for all the tables in the DB including the new tables.
    XClusterConfig xClusterConfig = getXClusterConfigFromTaskParams();
    Set<String> tableIdsDeleteReplication = new HashSet<>();
    dbToTablesInfoMapNeedBootstrap.forEach(
        (namespaceName, tablesInfo) -> {
          Set<String> tableIdsNeedBootstrap = getTableIds(tablesInfo);
          Set<String> tableIdsNeedBootstrapInReplication =
              xClusterConfig.getTableIdsWithReplicationSetup(
                  tableIdsNeedBootstrap, true /* done */);
          tableIdsDeleteReplication.addAll(
              tableIdsNeedBootstrapInReplication.stream()
                  .filter(tableId -> !tableIdsScheduledForBeingRemoved.contains(tableId))
                  .collect(Collectors.toSet()));
        });

    // A replication group with no tables in it cannot exist in YBDB. If all the tables must be
    // removed from the replication group, remove the replication group.
    boolean isRestartWholeConfig =
        tableIdsDeleteReplication.size() + tableIdsScheduledForBeingRemoved.size()
            >= xClusterConfig.getTableIdsWithReplicationSetup().size()
                + tableIdsNotNeedBootstrap.size();
    log.info(
        "tableIdsDeleteReplication is {} and isRestartWholeConfig is {}",
        tableIdsDeleteReplication,
        isRestartWholeConfig);
    if (isRestartWholeConfig) {
      createXClusterConfigSetStatusForTablesTask(
          getTableIds(requestedTableInfoList, txnTableInfo), XClusterTableConfig.Status.Updating);

      // Delete the xCluster config.
      createDeleteXClusterConfigSubtasks(
          xClusterConfig, true /* keepEntry */, taskParams().isForced());

      createXClusterConfigSetStatusTask(XClusterConfig.XClusterConfigStatusType.Updating);

      createXClusterConfigSetStatusForTablesTask(
          getTableIds(requestedTableInfoList, txnTableInfo), XClusterTableConfig.Status.Updating);

      // Recreate the config including the new tables.
      addSubtasksToCreateXClusterConfig(
          sourceUniverse,
          targetUniverse,
          requestedTableInfoList,
          mainTableIndexTablesMap,
          txnTableInfo);
    } else {
      createXClusterConfigModifyTablesTask(
          tableIdsDeleteReplication,
          XClusterConfigModifyTables.Params.Action.REMOVE_FROM_REPLICATION_ONLY);

      createXClusterConfigSetStatusForTablesTask(
          tableIdsDeleteReplication, XClusterTableConfig.Status.Updating);

      // Add the subtasks to set up replication for tables that need bootstrapping.
      addSubtasksForTablesNeedBootstrap(
          sourceUniverse,
          targetUniverse,
          taskParams().getBootstrapParams(),
          dbToTablesInfoMapNeedBootstrap,
          true /* isReplicationConfigCreated */);
    }
  }
}
