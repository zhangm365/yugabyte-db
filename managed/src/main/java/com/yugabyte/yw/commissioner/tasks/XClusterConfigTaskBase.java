// Copyright (c) YugaByte, Inc.
package com.yugabyte.yw.commissioner.tasks;

import com.google.api.client.util.Throwables;
import com.google.common.collect.ImmutableList;
import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.TaskExecutor;
import com.yugabyte.yw.commissioner.TaskExecutor.SubTaskGroup;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.commissioner.tasks.subtasks.xcluster.BootstrapProducer;
import com.yugabyte.yw.commissioner.tasks.subtasks.xcluster.CheckBootstrapRequired;
import com.yugabyte.yw.commissioner.tasks.subtasks.xcluster.ReplicateNamespaces;
import com.yugabyte.yw.commissioner.tasks.subtasks.xcluster.SetReplicationPaused;
import com.yugabyte.yw.commissioner.tasks.subtasks.xcluster.SetRestoreTime;
import com.yugabyte.yw.commissioner.tasks.subtasks.xcluster.XClusterConfigModifyTables;
import com.yugabyte.yw.commissioner.tasks.subtasks.xcluster.XClusterConfigRename;
import com.yugabyte.yw.commissioner.tasks.subtasks.xcluster.XClusterConfigSetStatus;
import com.yugabyte.yw.commissioner.tasks.subtasks.xcluster.XClusterConfigSetStatusForTables;
import com.yugabyte.yw.commissioner.tasks.subtasks.xcluster.XClusterConfigSetup;
import com.yugabyte.yw.commissioner.tasks.subtasks.xcluster.XClusterConfigSync;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.common.gflags.GFlagsUtil;
import com.yugabyte.yw.common.services.YBClientService;
import com.yugabyte.yw.common.utils.Pair;
import com.yugabyte.yw.forms.ITaskParams;
import com.yugabyte.yw.forms.XClusterConfigCreateFormData.BootstrapParams;
import com.yugabyte.yw.forms.XClusterConfigTaskParams;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.XClusterConfig;
import com.yugabyte.yw.models.XClusterConfig.ConfigType;
import com.yugabyte.yw.models.XClusterConfig.XClusterConfigStatusType;
import com.yugabyte.yw.models.XClusterTableConfig;
import com.yugabyte.yw.models.helpers.TaskType;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.yb.CommonTypes;
import org.yb.CommonTypes.TableType;
import org.yb.WireProtocol.AppStatusPB.ErrorCode;
import org.yb.cdc.CdcConsumer;
import org.yb.cdc.CdcConsumer.StreamEntryPB;
import org.yb.cdc.CdcConsumer.XClusterRole;
import org.yb.client.GetMasterClusterConfigResponse;
import org.yb.client.GetTableSchemaResponse;
import org.yb.client.IsBootstrapRequiredResponse;
import org.yb.client.IsSetupUniverseReplicationDoneResponse;
import org.yb.client.ListTablesResponse;
import org.yb.client.YBClient;
import org.yb.master.CatalogEntityInfo;
import org.yb.master.MasterDdlOuterClass;
import org.yb.master.MasterDdlOuterClass.ListTablesResponsePB.TableInfo;
import org.yb.master.MasterTypes.RelationType;
import play.mvc.Http.Status;

@Slf4j
public abstract class XClusterConfigTaskBase extends UniverseDefinitionTaskBase {

  private static final int POLL_TIMEOUT_SECONDS = 300;
  private static final int PARTITION_SIZE_FOR_IS_BOOTSTRAP_REQUIRED_API = 8;
  public static final String SOURCE_ROOT_CERTS_DIR_GFLAG = "certs_for_cdc_dir";
  public static final String DEFAULT_SOURCE_ROOT_CERTS_DIR_NAME = "/yugabyte-tls-producer";
  public static final String SOURCE_ROOT_CERTIFICATE_NAME = "ca.crt";
  public static final String ENABLE_REPLICATE_TRANSACTION_STATUS_TABLE_GFLAG =
      "enable_replicate_transaction_status_table";
  public static final boolean ENABLE_REPLICATE_TRANSACTION_STATUS_TABLE_DEFAULT = false;
  public static final String TRANSACTION_STATUS_TABLE_REPLICATION_GROUP_NAME = "system";
  public static final String TRANSACTION_STATUS_TABLE_NAMESPACE = "system";
  public static final String TRANSACTION_STATUS_TABLE_NAME = "transactions";
  public static final Boolean TRANSACTION_SOURCE_UNIVERSE_ROLE_ACTIVE_DEFAULT = true;
  public static final Boolean TRANSACTION_TARGET_UNIVERSE_ROLE_ACTIVE_DEFAULT = true;
  public static final String ENABLE_PG_SAVEPOINTS_GFLAG_NAME = "enable_pg_savepoints";

  public static final List<XClusterConfig.XClusterConfigStatusType>
      X_CLUSTER_CONFIG_MUST_DELETE_STATUS_LIST =
          ImmutableList.of(
              XClusterConfig.XClusterConfigStatusType.DeletionFailed,
              XClusterConfig.XClusterConfigStatusType.DeletedUniverse);

  public static final List<XClusterTableConfig.Status> X_CLUSTER_TABLE_CONFIG_PENDING_STATUS_LIST =
      ImmutableList.of(
          XClusterTableConfig.Status.Validated,
          XClusterTableConfig.Status.Updating,
          XClusterTableConfig.Status.Bootstrapping);

  private static final Map<XClusterConfigStatusType, List<TaskType>> STATUS_TO_ALLOWED_TASKS =
      new HashMap<>();

  static {
    STATUS_TO_ALLOWED_TASKS.put(
        XClusterConfigStatusType.Initialized,
        ImmutableList.of(
            TaskType.CreateXClusterConfig,
            TaskType.DeleteXClusterConfig,
            TaskType.RestartXClusterConfig));
    STATUS_TO_ALLOWED_TASKS.put(
        XClusterConfigStatusType.Running,
        ImmutableList.of(
            TaskType.EditXClusterConfig,
            TaskType.DeleteXClusterConfig,
            TaskType.RestartXClusterConfig));
    STATUS_TO_ALLOWED_TASKS.put(
        XClusterConfigStatusType.Updating,
        ImmutableList.of(TaskType.DeleteXClusterConfig, TaskType.RestartXClusterConfig));
    STATUS_TO_ALLOWED_TASKS.put(
        XClusterConfigStatusType.DeletedUniverse, ImmutableList.of(TaskType.DeleteXClusterConfig));
    STATUS_TO_ALLOWED_TASKS.put(
        XClusterConfigStatusType.DeletionFailed, ImmutableList.of(TaskType.DeleteXClusterConfig));
    STATUS_TO_ALLOWED_TASKS.put(
        XClusterConfigStatusType.Failed,
        ImmutableList.of(TaskType.DeleteXClusterConfig, TaskType.RestartXClusterConfig));
  }

  protected XClusterConfigTaskBase(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  @Override
  public void initialize(ITaskParams params) {
    super.initialize(params);
  }

  @Override
  public String getName() {
    if (taskParams().getXClusterConfig() != null) {
      return String.format(
          "%s(uuid=%s, universe=%s)",
          this.getClass().getSimpleName(),
          taskParams().getXClusterConfig().getUuid(),
          taskParams().getUniverseUUID());
    } else {
      return String.format(
          "%s(universe=%s)", this.getClass().getSimpleName(), taskParams().getUniverseUUID());
    }
  }

  @Override
  protected XClusterConfigTaskParams taskParams() {
    return (XClusterConfigTaskParams) taskParams;
  }

  protected XClusterConfig getXClusterConfigFromTaskParams() {
    XClusterConfig xClusterConfig = taskParams().getXClusterConfig();
    if (xClusterConfig == null) {
      throw new RuntimeException("xClusterConfig in task params is null");
    }
    return xClusterConfig;
  }

  protected Optional<XClusterConfig> maybeGetXClusterConfig() {
    return XClusterConfig.maybeGet(taskParams().getXClusterConfig().getUuid());
  }

  protected void setXClusterConfigStatus(XClusterConfigStatusType status) {
    taskParams().getXClusterConfig().updateStatus(status);
  }

  public static boolean isInMustDeleteStatus(XClusterConfig xClusterConfig) {
    if (xClusterConfig == null) {
      throw new RuntimeException("xClusterConfig cannot be null");
    }
    return X_CLUSTER_CONFIG_MUST_DELETE_STATUS_LIST.contains(xClusterConfig.getStatus());
  }

  public static boolean isTaskAllowed(XClusterConfig xClusterConfig, TaskType taskType) {
    if (taskType == null) {
      throw new RuntimeException("taskType cannot be null");
    }
    List<TaskType> allowedTaskTypes = getAllowedTasks(xClusterConfig);
    // Allow unknown situations to avoid bugs for now.
    if (allowedTaskTypes == null) {
      return true;
    }
    return allowedTaskTypes.contains(taskType);
  }

  public static List<TaskType> getAllowedTasks(XClusterConfig xClusterConfig) {
    if (xClusterConfig == null) {
      throw new RuntimeException(
          "Cannot retrieve the list of allowed tasks because xClusterConfig is null");
    }
    List<TaskType> allowedTaskTypes = STATUS_TO_ALLOWED_TASKS.get(xClusterConfig.getStatus());
    if (allowedTaskTypes == null) {
      log.warn(
          "Cannot retrieve the list of allowed tasks because it is not defined for status={}",
          xClusterConfig.getStatus());
    }
    return allowedTaskTypes;
  }

  public static String getProducerCertsDir(UUID providerUuid) {
    Provider provider = Provider.getOrBadRequest(providerUuid);
    // For Kubernetes universe, we must use the PV instead of home directory.
    return Paths.get(
            (provider.getCode().equals(CloudType.kubernetes.toString())
                ? KubernetesTaskBase.K8S_NODE_YW_DATA_DIR
                : provider.getYbHome()),
            DEFAULT_SOURCE_ROOT_CERTS_DIR_NAME)
        .toString();
  }

  public static String getProducerCertsDir(String providerUuid) {
    return getProducerCertsDir(UUID.fromString(providerUuid));
  }

  public String getProducerCertsDir() {
    return getProducerCertsDir(
        Universe.getOrBadRequest(taskParams().getXClusterConfig().getTargetUniverseUUID())
            .getUniverseDetails()
            .getPrimaryCluster()
            .userIntent
            .provider);
  }

  protected SubTaskGroup createXClusterConfigSetupTask(Set<String> tableIds) {
    SubTaskGroup subTaskGroup = createSubTaskGroup("XClusterConfigSetup");
    XClusterConfigSetup.Params xClusterConfigParams = new XClusterConfigSetup.Params();
    xClusterConfigParams.setUniverseUUID(taskParams().getXClusterConfig().getTargetUniverseUUID());
    xClusterConfigParams.xClusterConfig = taskParams().getXClusterConfig();
    xClusterConfigParams.tableIds = tableIds;

    XClusterConfigSetup task = createTask(XClusterConfigSetup.class);
    task.initialize(xClusterConfigParams);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * It creates a subtask to set the status of an xCluster config and save it in the Platform DB.
   *
   * @param desiredStatus The xCluster config will have this status
   * @return The created subtask group; it can be used to assign a subtask group type to this
   *     subtask
   */
  protected SubTaskGroup createXClusterConfigSetStatusTask(XClusterConfigStatusType desiredStatus) {
    SubTaskGroup subTaskGroup = createSubTaskGroup("XClusterConfigSetStatus");
    XClusterConfigSetStatus.Params setStatusParams = new XClusterConfigSetStatus.Params();
    setStatusParams.setUniverseUUID(taskParams().getXClusterConfig().getTargetUniverseUUID());
    setStatusParams.xClusterConfig = taskParams().getXClusterConfig();
    setStatusParams.desiredStatus = desiredStatus;

    XClusterConfigSetStatus task = createTask(XClusterConfigSetStatus.class);
    task.initialize(setStatusParams);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  protected SubTaskGroup createXClusterConfigSetStatusForTablesTask(
      Set<String> tableIds, XClusterTableConfig.Status desiredStatus) {
    SubTaskGroup subTaskGroup = createSubTaskGroup("XClusterConfigSetStatusForTables");
    XClusterConfigSetStatusForTables.Params setStatusForTablesParams =
        new XClusterConfigSetStatusForTables.Params();
    setStatusForTablesParams.setUniverseUUID(
        taskParams().getXClusterConfig().getTargetUniverseUUID());
    setStatusForTablesParams.xClusterConfig = taskParams().getXClusterConfig();
    setStatusForTablesParams.tableIds = tableIds;
    setStatusForTablesParams.desiredStatus = desiredStatus;

    XClusterConfigSetStatusForTables task = createTask(XClusterConfigSetStatusForTables.class);
    task.initialize(setStatusForTablesParams);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * It makes an RPC call to pause/enable the xCluster config and saves it in the Platform DB.
   *
   * @param pause Whether to pause replication
   * @return The created subtask group
   */
  protected SubTaskGroup createSetReplicationPausedTask(boolean pause) {
    SubTaskGroup subTaskGroup = createSubTaskGroup("SetReplicationPaused");
    SetReplicationPaused.Params setReplicationPausedParams = new SetReplicationPaused.Params();
    setReplicationPausedParams.setUniverseUUID(
        taskParams().getXClusterConfig().getTargetUniverseUUID());
    setReplicationPausedParams.xClusterConfig = taskParams().getXClusterConfig();
    setReplicationPausedParams.pause = pause;

    SetReplicationPaused task = createTask(SetReplicationPaused.class);
    task.initialize(setReplicationPausedParams);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  protected SubTaskGroup createSetReplicationPausedTask(String status) {
    return createSetReplicationPausedTask(status.equals("Paused"));
  }

  protected SubTaskGroup createXClusterConfigModifyTablesTask(
      Set<String> tables, XClusterConfigModifyTables.Params.Action action) {
    SubTaskGroup subTaskGroup = createSubTaskGroup("XClusterConfigModifyTables");
    XClusterConfigModifyTables.Params modifyTablesParams = new XClusterConfigModifyTables.Params();
    modifyTablesParams.setUniverseUUID(taskParams().getXClusterConfig().getTargetUniverseUUID());
    modifyTablesParams.xClusterConfig = taskParams().getXClusterConfig();
    modifyTablesParams.tables = tables;
    modifyTablesParams.action = action;

    XClusterConfigModifyTables task = createTask(XClusterConfigModifyTables.class);
    task.initialize(modifyTablesParams);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  protected SubTaskGroup createXClusterConfigRenameTask(String newName) {
    SubTaskGroup subTaskGroup = createSubTaskGroup("XClusterConfigRename");
    XClusterConfigRename.Params xClusterConfigRenameParams = new XClusterConfigRename.Params();
    xClusterConfigRenameParams.setUniverseUUID(
        taskParams().getXClusterConfig().getTargetUniverseUUID());
    xClusterConfigRenameParams.xClusterConfig = taskParams().getXClusterConfig();
    xClusterConfigRenameParams.newName = newName;

    XClusterConfigRename task = createTask(XClusterConfigRename.class);
    task.initialize(xClusterConfigRenameParams);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  protected SubTaskGroup createDeleteBootstrapIdsTask(boolean forceDelete) {
    return createDeleteBootstrapIdsTask(taskParams().getXClusterConfig(), forceDelete);
  }

  protected SubTaskGroup createDeleteBootstrapIdsTask() {
    return createDeleteBootstrapIdsTask(false /* forceDelete */);
  }

  protected SubTaskGroup createDeleteReplicationTask(boolean ignoreErrors) {
    return createDeleteReplicationTask(taskParams().getXClusterConfig(), ignoreErrors);
  }

  protected SubTaskGroup createDeleteReplicationTask() {
    return createDeleteReplicationTask(false /* ignoreErrors */);
  }

  protected SubTaskGroup createDeleteXClusterConfigEntryTask() {
    return createDeleteXClusterConfigEntryTask(getXClusterConfigFromTaskParams());
  }

  protected SubTaskGroup createXClusterConfigSyncTask() {
    SubTaskGroup subTaskGroup = createSubTaskGroup("XClusterConfigSync");
    XClusterConfigSync task = createTask(XClusterConfigSync.class);
    task.initialize(taskParams());
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  protected interface IPollForXClusterOperation {
    IsSetupUniverseReplicationDoneResponse poll(String replicationGroupName) throws Exception;
  }

  /**
   * It parses a replication group name in the coreDB, and return the source universe UUID and the
   * xCluster config name shown in the Platform UI. The input must have a format of
   * sourceUniverseUuid_xClusterConfigName (e.g., dc510c24-9c4a-4e15-b59b-333e5815e936_MyRepl).
   *
   * @param replicationGroupName The replication group name saved in the coreDB.
   * @return returns an optional of a Pair of source universe UUID and the xCluster config name if
   *     the input has the correct format. Otherwise, the optional is empty.
   */
  public static Optional<Pair<UUID, String>> maybeParseReplicationGroupName(
      String replicationGroupName) {
    String[] sourceUniverseUuidAndConfigName = replicationGroupName.split("_", 2);
    if (sourceUniverseUuidAndConfigName.length != 2) {
      log.warn(
          "Unable to parse XClusterConfig: {}, expected format <sourceUniverseUUID>_<name>",
          replicationGroupName);
      return Optional.empty();
    }
    UUID sourceUniverseUUID;
    try {
      sourceUniverseUUID = UUID.fromString(sourceUniverseUuidAndConfigName[0]);
    } catch (Exception e) {
      log.warn(
          "Unable to parse {} as valid UUID for replication group name: {}",
          sourceUniverseUuidAndConfigName[0],
          replicationGroupName);
      return Optional.empty();
    }
    return Optional.of(new Pair<>(sourceUniverseUUID, sourceUniverseUuidAndConfigName[1]));
  }

  protected void waitForXClusterOperation(IPollForXClusterOperation p) {
    XClusterConfig xClusterConfig = taskParams().getXClusterConfig();
    try {
      IsSetupUniverseReplicationDoneResponse doneResponse = null;
      int numAttempts = 1;
      long startTime = System.currentTimeMillis();
      while (((System.currentTimeMillis() - startTime) / 1000) < POLL_TIMEOUT_SECONDS) {
        if (numAttempts % 10 == 0) {
          log.info(
              "Wait for XClusterConfig({}) operation to complete (attempt {})",
              xClusterConfig.getUuid(),
              numAttempts);
        }

        doneResponse = p.poll(xClusterConfig.getReplicationGroupName());
        if (doneResponse.isDone()) {
          break;
        }
        if (doneResponse.hasError()) {
          log.warn(
              "Failed to wait for XClusterConfig({}) operation: {}",
              xClusterConfig.getUuid(),
              doneResponse.getError().toString());
        }
        waitFor(Duration.ofMillis(1000));
        numAttempts++;
      }

      if (doneResponse == null) {
        // TODO: Add unit tests (?) -- May be difficult due to wait
        throw new RuntimeException(
            String.format(
                "Never received response waiting for XClusterConfig(%s) operation to complete",
                xClusterConfig.getUuid()));
      }
      if (!doneResponse.isDone()) {
        // TODO: Add unit tests (?) -- May be difficult due to wait
        throw new RuntimeException(
            String.format(
                "Timed out waiting for XClusterConfig(%s) operation to complete",
                xClusterConfig.getUuid()));
      }
      if (doneResponse.hasReplicationError()
          && doneResponse.getReplicationError().getCode() != ErrorCode.OK) {
        throw new RuntimeException(
            String.format(
                "XClusterConfig(%s) operation failed: %s",
                xClusterConfig.getUuid(), doneResponse.getReplicationError().toString()));
      }

    } catch (Exception e) {
      log.error("{} hit error : {}", getName(), e.getMessage());
      Throwables.propagate(e);
    }
  }

  /**
   * It creates a group of subtasks to check if bootstrap is required for all the tables in the
   * xCluster config of this task.
   *
   * <p>It creates separate subtasks for each table to increase parallelism because current coreDB
   * implementation can check one table in one RPC call.
   *
   * @param tableIds A set of table IDs to check whether they require bootstrap
   * @return The created subtask group
   */
  protected SubTaskGroup createCheckBootstrapRequiredTask(Set<String> tableIds) {
    SubTaskGroup subTaskGroup = createSubTaskGroup("CheckBootstrapRequired");
    for (String tableId : tableIds) {
      CheckBootstrapRequired.Params bootstrapRequiredParams = new CheckBootstrapRequired.Params();
      bootstrapRequiredParams.setUniverseUUID(
          taskParams().getXClusterConfig().getSourceUniverseUUID());
      bootstrapRequiredParams.xClusterConfig = taskParams().getXClusterConfig();
      bootstrapRequiredParams.tableIds = Collections.singleton(tableId);

      CheckBootstrapRequired task = createTask(CheckBootstrapRequired.class);
      task.initialize(bootstrapRequiredParams);
      subTaskGroup.addSubTask(task);
    }
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  protected void checkBootstrapRequiredForReplicationSetup(Set<String> tableIds) {
    XClusterConfig xClusterConfig = getXClusterConfigFromTaskParams();
    log.info(
        "Running checkBootstrapRequired with (sourceUniverse={},xClusterUuid={},tableIds={})",
        xClusterConfig.getSourceUniverseUUID(),
        xClusterConfig,
        tableIds);

    try {
      // Check whether bootstrap is required.
      Map<String, Boolean> isBootstrapRequiredMap =
          isBootstrapRequired(tableIds, xClusterConfig.getSourceUniverseUUID());
      log.debug("IsBootstrapRequired result is {}", isBootstrapRequiredMap);

      // Persist whether bootstrap is required.
      Map<Boolean, List<String>> tableIdsPartitionedByNeedBootstrap =
          isBootstrapRequiredMap.keySet().stream()
              .collect(Collectors.partitioningBy(isBootstrapRequiredMap::get));
      xClusterConfig.updateNeedBootstrapForTables(
          tableIdsPartitionedByNeedBootstrap.get(true), true /* needBootstrap */);
      xClusterConfig.updateNeedBootstrapForTables(
          tableIdsPartitionedByNeedBootstrap.get(false), false /* needBootstrap */);
    } catch (Exception e) {
      log.error("{} hit error : {}", getName(), e.getMessage());
      throw new RuntimeException(e);
    }

    log.info("Completed checkBootstrapRequired");
  }

  /**
   * It returns a set of tables that xCluster replication cannot be setup for them and need
   * bootstrap.
   *
   * <p>Note: You must run {@link #checkBootstrapRequiredForReplicationSetup} method before running
   * this method to have the most updated values.
   *
   * @param tableIds A set of tables to check whether they need bootstrapping
   * @return A set of tables that need to be bootstrapped
   * @see #checkBootstrapRequiredForReplicationSetup(Set)
   */
  protected Set<XClusterTableConfig> getTablesNeedBootstrap(Set<String> tableIds) {
    return getXClusterConfigFromTaskParams().getTablesById(tableIds).stream()
        .filter(tableConfig -> tableConfig.isNeedBootstrap())
        .collect(Collectors.toSet());
  }

  /** @see #getTablesNeedBootstrap(Set) */
  protected Set<XClusterTableConfig> getTablesNeedBootstrap() {
    return getTablesNeedBootstrap(getXClusterConfigFromTaskParams().getTableIds());
  }

  protected Set<String> getTableIdsNeedBootstrap(Set<String> tableIds) {
    return getTablesNeedBootstrap(tableIds).stream()
        .map(tableConfig -> tableConfig.getTableId())
        .collect(Collectors.toSet());
  }

  protected Set<String> getTableIdsNeedBootstrap() {
    return getTableIdsNeedBootstrap(getXClusterConfigFromTaskParams().getTableIds());
  }

  /**
   * It returns a set of tables that xCluster replication can be set up without bootstrapping.
   *
   * <p>Note: You must run {@link #checkBootstrapRequiredForReplicationSetup} method before running
   * this method to have the most updated values.
   *
   * @param tableIds A set of tables to check whether they need bootstrapping
   * @return A set of tables that do not need to be bootstrapped
   * @see #checkBootstrapRequiredForReplicationSetup(Set)
   */
  protected Set<XClusterTableConfig> getTablesNotNeedBootstrap(Set<String> tableIds) {
    return getXClusterConfigFromTaskParams().getTablesById(tableIds).stream()
        .filter(tableConfig -> !tableConfig.isNeedBootstrap())
        .collect(Collectors.toSet());
  }

  /** @see #getTablesNotNeedBootstrap(Set) */
  protected Set<XClusterTableConfig> getTablesNotNeedBootstrap() {
    return getTablesNotNeedBootstrap(getXClusterConfigFromTaskParams().getTableIds());
  }

  protected Set<String> getTableIdsNotNeedBootstrap(Set<String> tableIds) {
    return getTablesNotNeedBootstrap(tableIds).stream()
        .map(tableConfig -> tableConfig.getTableId())
        .collect(Collectors.toSet());
  }

  protected Set<String> getTableIdsNotNeedBootstrap() {
    return getTableIdsNotNeedBootstrap(getXClusterConfigFromTaskParams().getTableIds());
  }

  /**
   * It creates a subtask to bootstrap the set of tables passed in.
   *
   * @param tableIds The ids of the tables to be bootstrapped
   */
  protected SubTaskGroup createBootstrapProducerTask(Collection<String> tableIds) {
    SubTaskGroup subTaskGroup = createSubTaskGroup("BootstrapProducer");
    BootstrapProducer.Params bootstrapProducerParams = new BootstrapProducer.Params();
    bootstrapProducerParams.setUniverseUUID(
        taskParams().getXClusterConfig().getSourceUniverseUUID());
    bootstrapProducerParams.xClusterConfig = taskParams().getXClusterConfig();
    bootstrapProducerParams.tableIds = new ArrayList<>(tableIds);

    BootstrapProducer task = createTask(BootstrapProducer.class);
    task.initialize(bootstrapProducerParams);
    subTaskGroup.addSubTask(task);
    subTaskGroup.setSubTaskGroupType(SubTaskGroupType.BootstrappingProducer);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * It creates a subtask to set the restore time in the Platform DB for tables that underwent
   * bootstrapping. This subtask must be added to the run queue after the restore subtask.
   *
   * @param tableIds Table ids that underwent bootstrapping and a restore to the target universe
   *     happened for them
   */
  protected SubTaskGroup createSetRestoreTimeTask(Set<String> tableIds) {
    TaskExecutor.SubTaskGroup subTaskGroup = createSubTaskGroup("SetBootstrapBackup");
    SetRestoreTime.Params setRestoreTimeParams = new SetRestoreTime.Params();
    setRestoreTimeParams.setUniverseUUID(taskParams().getXClusterConfig().getSourceUniverseUUID());
    setRestoreTimeParams.xClusterConfig = taskParams().getXClusterConfig();
    setRestoreTimeParams.tableIds = tableIds;

    SetRestoreTime task = createTask(SetRestoreTime.class);
    task.initialize(setRestoreTimeParams);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  protected SubTaskGroup createReplicateNamespacesTask() {
    TaskExecutor.SubTaskGroup subTaskGroup = createSubTaskGroup("ReplicateNamespaces");
    XClusterConfigTaskParams replicateNamespacesParams = new XClusterConfigTaskParams();
    replicateNamespacesParams.xClusterConfig = getXClusterConfigFromTaskParams();

    ReplicateNamespaces task = createTask(ReplicateNamespaces.class);
    task.initialize(replicateNamespacesParams);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public static CatalogEntityInfo.SysClusterConfigEntryPB getClusterConfig(
      YBClient client, UUID universeUuid) throws Exception {
    GetMasterClusterConfigResponse clusterConfigResp = client.getMasterClusterConfig();
    if (clusterConfigResp.hasError()) {
      throw new RuntimeException(
          String.format(
              "Failed to getMasterClusterConfig from target universe (%s): %s",
              universeUuid, clusterConfigResp.errorMessage()));
    }
    return clusterConfigResp.getConfig();
  }

  private static Set<String> getConsumerTableIdsFromClusterConfig(
      CatalogEntityInfo.SysClusterConfigEntryPB clusterConfig,
      Collection<String> excludeReplicationGroupNames) {
    Set<String> consumerTableIdsFromClusterConfig = new HashSet<>();
    Map<java.lang.String, org.yb.cdc.CdcConsumer.ProducerEntryPB> producerMapMap =
        clusterConfig.getConsumerRegistry().getProducerMapMap();
    producerMapMap.forEach(
        (replicationGroupName, replicationGroup) -> {
          if (excludeReplicationGroupNames.contains(replicationGroupName)
              || replicationGroup == null) {
            return;
          }
          consumerTableIdsFromClusterConfig.addAll(
              replicationGroup.getStreamMapMap().values().stream()
                  .map(StreamEntryPB::getConsumerTableId)
                  .collect(Collectors.toSet()));
        });
    return consumerTableIdsFromClusterConfig;
  }

  /**
   * It reads information for the associated replication group in DB with the xCluster config and
   * updates its state.
   *
   * @param config The cluster config on the target universe
   * @param xClusterConfig The xCluster config object to sync
   * @param tableIds The list of tables in the {@code xClusterConfig} to sync
   * @param skipSyncTxnTable Whether to skip syncing txn table
   */
  protected void syncXClusterConfigWithReplicationGroup(
      CatalogEntityInfo.SysClusterConfigEntryPB config,
      XClusterConfig xClusterConfig,
      Set<String> tableIds,
      boolean skipSyncTxnTable) {
    CdcConsumer.ProducerEntryPB replicationGroup =
        config
            .getConsumerRegistry()
            .getProducerMapMap()
            .get(xClusterConfig.getReplicationGroupName());
    if (replicationGroup == null) {
      throw new RuntimeException(
          String.format(
              "No replication group found with name (%s) in universe (%s) cluster config",
              xClusterConfig.getReplicationGroupName(), xClusterConfig.getTargetUniverseUUID()));
    }

    // Ensure disable stream state is in sync with Platform's point of view.
    if (replicationGroup.getDisableStream() != xClusterConfig.isPaused()) {
      log.warn(
          "Detected mismatched disable state for replication group {} and xCluster config {}",
          replicationGroup,
          xClusterConfig);
      xClusterConfig.updatePaused(replicationGroup.getDisableStream());
    }

    // Sync id and status for each stream.
    Map<String, CdcConsumer.StreamEntryPB> replicationStreams = replicationGroup.getStreamMapMap();
    Map<String, String> streamMap =
        replicationStreams.entrySet().stream()
            .collect(Collectors.toMap(e -> e.getValue().getProducerTableId(), Map.Entry::getKey));
    for (String tableId : tableIds) {
      Optional<XClusterTableConfig> tableConfig = xClusterConfig.maybeGetTableById(tableId);
      if (!tableConfig.isPresent()) {
        throw new RuntimeException(
            String.format(
                "Could not find tableId (%s) in the xCluster config %s", tableId, xClusterConfig));
      }
      String streamId = streamMap.get(tableId);
      if (Objects.isNull(streamId)) {
        log.warn(
            "No stream id found for table ({}) in the cluster config for xCluster config ({})",
            tableId,
            xClusterConfig.getUuid());
        tableConfig.get().setStreamId(null);
        tableConfig.get().setStatus(XClusterTableConfig.Status.Failed);
        tableConfig.get().update();
        continue;
      }
      if (Objects.nonNull(tableConfig.get().getStreamId())
          && !tableConfig.get().getStreamId().equals(streamId)) {
        log.warn(
            "Bootstrap id ({}) for table ({}) is different from stream id ({}) in the "
                + "cluster config for xCluster config ({})",
            tableConfig.get().getStreamId(),
            tableId,
            streamId,
            xClusterConfig.getUuid());
      }
      tableConfig.get().setStreamId(streamId);
      tableConfig.get().setStatus(XClusterTableConfig.Status.Running);
      tableConfig.get().setReplicationSetupDone(true);
      tableConfig.get().update();
      log.info(
          "StreamId for table {} in xCluster config {} is set to {}",
          tableId,
          xClusterConfig.getUuid(),
          streamId);
    }

    // Sync id and status for the txn table stream and the target universe role.
    if (xClusterConfig.getType().equals(ConfigType.Txn) && !skipSyncTxnTable) {
      CdcConsumer.ProducerEntryPB txnTableReplicationGroup =
          config
              .getConsumerRegistry()
              .getProducerMapMap()
              .get(xClusterConfig.getTxnTableReplicationGroupName());
      if (txnTableReplicationGroup == null) {
        throw new RuntimeException(
            String.format(
                "Xcluster type is ConfigType.Txn but no replication group found with name (%s) "
                    + "in universe (%s) cluster config",
                xClusterConfig.getTxnTableReplicationGroupName(),
                xClusterConfig.getTargetUniverseUUID()));
      }
      Map<String, String> txnReplicationStreamMap =
          txnTableReplicationGroup.getStreamMapMap().entrySet().stream()
              .collect(Collectors.toMap(e -> e.getValue().getProducerTableId(), Map.Entry::getKey));
      XClusterTableConfig txnTableConfig = xClusterConfig.getTxnTableDetails();
      if (Objects.isNull(txnTableConfig)) {
        throw new IllegalStateException(
            "Xcluster type is ConfigType.Txn but txnTableConfig is null");
      }
      String streamId = txnReplicationStreamMap.get(txnTableConfig.getTableId());
      if (Objects.isNull(streamId)) {
        txnTableConfig.setStatus(XClusterTableConfig.Status.Failed);
        txnTableConfig.update();
        throw new IllegalStateException(
            "Xcluster type is ConfigType.Txn but no stream id is set for the txn table in the "
                + "target universe cluster config");
      }
      if (Objects.nonNull(txnTableConfig.getStreamId())
          && !txnTableConfig.getStreamId().equals(streamId)) {
        log.warn(
            "Bootstrap id ({}) for table ({}) is different from stream id ({}) in the "
                + "cluster config for xCluster config ({})",
            txnTableConfig.getStreamId(),
            txnTableConfig.getTableId(),
            streamId,
            xClusterConfig.getUuid());
      }
      txnTableConfig.setStreamId(streamId);
      txnTableConfig.setStatus(XClusterTableConfig.Status.Running);
      txnTableConfig.setReplicationSetupDone(true);
      txnTableConfig.update();
      log.info(
          "StreamId for txn table {} in xCluster config {} is set to {}",
          txnTableConfig.getTableId(),
          xClusterConfig.getUuid(),
          streamId);
      xClusterConfig.setTargetActive(
          config.getConsumerRegistry().getRole().equals(XClusterRole.ACTIVE));
      xClusterConfig.update();
      log.info(
          "Universe role for universe {} was set to {}",
          xClusterConfig.getTargetUniverseUUID(),
          xClusterConfig.isTargetActive() ? XClusterRole.ACTIVE : XClusterRole.STANDBY);
    }
  }

  private static boolean supportsMultipleTablesWithIsBootstrapRequired(Universe universe) {
    // The minimum YBDB version that supports multiple tables with IsBootstrapRequired is
    // 2.15.3.0-b64.
    return Util.compareYbVersions(
            "2.15.3.0-b63",
            universe.getUniverseDetails().getPrimaryCluster().userIntent.ybSoftwareVersion,
            true /* suppressFormatError */)
        < 0;
  }

  public static boolean supportsTxnXCluster(Universe universe) {
    // The minimum YBDB version that supports transactional xCluster is 2.17.3.0-b2.
    return Util.compareYbVersions(
            "2.17.3.0-b1",
            universe.getUniverseDetails().getPrimaryCluster().userIntent.ybSoftwareVersion,
            true /* suppressFormatError */)
        < 0;
  }

  /**
   * It creates the required parameters to make IsBootstrapRequired API call and then makes the
   * call.
   *
   * @param ybService The YBClientService object to get a yb client from
   * @param tableIds The table IDs of tables to check whether they need bootstrap
   * @param xClusterConfig The config to check if an existing stream has fallen far behind
   * @param sourceUniverseUuid The UUID of the universe that {@code tableIds} belong to
   * @return A map of tableId to a boolean showing whether that table needs bootstrapping
   */
  private static Map<String, Boolean> isBootstrapRequired(
      YBClientService ybService,
      Set<String> tableIds,
      @Nullable XClusterConfig xClusterConfig,
      UUID sourceUniverseUuid)
      throws Exception {
    log.debug(
        "XClusterConfigTaskBase.isBootstrapRequired is called with xClusterConfig={}, "
            + "tableIds={}, and universeUuid={}",
        xClusterConfig,
        tableIds,
        sourceUniverseUuid);
    Map<String, Boolean> isBootstrapRequiredMap = new HashMap<>();

    // If there is no table to check, return the empty map.
    if (tableIds.isEmpty()) {
      return isBootstrapRequiredMap;
    }

    // Create tableIdStreamId map to pass to the IsBootstrapRequired API.
    Map<String, String> tableIdStreamIdMap;
    if (xClusterConfig != null) {
      tableIdStreamIdMap = xClusterConfig.getTableIdStreamIdMap(tableIds);
    } else {
      tableIdStreamIdMap = new HashMap<>();
      tableIds.forEach(tableId -> tableIdStreamIdMap.put(tableId, null));
    }

    Universe sourceUniverse = Universe.getOrBadRequest(sourceUniverseUuid);
    String sourceUniverseMasterAddresses =
        sourceUniverse.getMasterAddresses(true /* mastersQueryable */);
    // If there is no queryable master, return the empty map.
    if (sourceUniverseMasterAddresses.isEmpty()) {
      return isBootstrapRequiredMap;
    }
    String sourceUniverseCertificate = sourceUniverse.getCertificateNodetoNode();
    try (YBClient client =
        ybService.getClient(sourceUniverseMasterAddresses, sourceUniverseCertificate)) {
      try {
        int partitionSize =
            supportsMultipleTablesWithIsBootstrapRequired(sourceUniverse)
                ? PARTITION_SIZE_FOR_IS_BOOTSTRAP_REQUIRED_API
                : 1;
        log.info("Partition size used for isBootstrapRequiredParallel is {}", partitionSize);
        // Check whether bootstrap is required.
        List<IsBootstrapRequiredResponse> resps =
            client.isBootstrapRequiredParallel(tableIdStreamIdMap, partitionSize);
        for (IsBootstrapRequiredResponse resp : resps) {
          if (resp.hasError()) {
            throw new RuntimeException(
                String.format(
                    "IsBootstrapRequired RPC call with %s has errors in xCluster config %s: %s",
                    xClusterConfig, tableIdStreamIdMap, resp.errorMessage()));
          }
          isBootstrapRequiredMap.putAll(resp.getResults());
        }
      } catch (Exception e) {
        if (e.getMessage().contains("invalid method name: IsBootstrapRequired")) {
          // It means the current YBDB version of the source universe does not support the
          // IsBootstrapRequired RPC call. Ignore the error.
          log.warn(
              "XClusterConfigTaskBase.isBootstrapRequired hit error because its corresponding "
                  + "RPC call does not exist in the source universe {} (error is ignored) : {}",
              sourceUniverse.getUniverseUUID(),
              e.getMessage());
          return isBootstrapRequiredMap;
        } else {
          log.error("XClusterConfigTaskBase.isBootstrapRequired hit error : {}", e.getMessage());
          throw e;
        }
      }
      log.debug(
          "IsBootstrapRequired RPC call with {} returned {}",
          tableIdStreamIdMap,
          isBootstrapRequiredMap);

      return isBootstrapRequiredMap;
    }
  }

  /**
   * It checks whether the replication of tables can be started without bootstrap.
   *
   * @see #isBootstrapRequired(YBClientService, Set, XClusterConfig, UUID)
   */
  public static Map<String, Boolean> isBootstrapRequired(
      YBClientService ybService, Set<String> tableIds, UUID sourceUniverseUuid) throws Exception {
    return isBootstrapRequired(ybService, tableIds, null /* xClusterConfig */, sourceUniverseUuid);
  }

  protected Map<String, Boolean> isBootstrapRequired(Set<String> tableIds, UUID sourceUniverseUuid)
      throws Exception {
    return isBootstrapRequired(this.ybService, tableIds, sourceUniverseUuid);
  }

  /**
   * It checks whether the replication of tables in an existing xCluster config has fallen behind.
   *
   * @see #isBootstrapRequired(YBClientService, Set, XClusterConfig, UUID)
   */
  public static Map<String, Boolean> isBootstrapRequired(
      YBClientService ybService, Set<String> tableIds, XClusterConfig xClusterConfig)
      throws Exception {
    return isBootstrapRequired(
        ybService, tableIds, xClusterConfig, xClusterConfig.getSourceUniverseUUID());
  }

  protected Map<String, Boolean> isBootstrapRequired(
      Set<String> tableIds, XClusterConfig xClusterConfig) throws Exception {
    return isBootstrapRequired(this.ybService, tableIds, xClusterConfig);
  }

  /**
   * It synchronizes whether the replication is set up for each table in the Platform DB with what
   * state it has in the target universe cluster config.
   *
   * @param config The cluster config of the target universe
   * @param xClusterConfig The xClusterConfig object that {@code tableIds} belong to
   * @param tableIds The IDs of the table to synchronize
   * @return A boolean showing whether a replication group corresponding to the xCluster config
   *     exists
   */
  protected boolean syncReplicationSetUpStateForTables(
      CatalogEntityInfo.SysClusterConfigEntryPB config,
      XClusterConfig xClusterConfig,
      Set<String> tableIds) {
    CdcConsumer.ProducerEntryPB replicationGroup =
        config
            .getConsumerRegistry()
            .getProducerMapMap()
            .get(xClusterConfig.getReplicationGroupName());
    if (replicationGroup == null) {
      return false;
    }

    Set<String> tableIdsWithReplication =
        replicationGroup.getStreamMapMap().values().stream()
            .map(CdcConsumer.StreamEntryPB::getProducerTableId)
            .collect(Collectors.toSet());
    log.debug("Table ids found in the target universe cluster config: {}", tableIdsWithReplication);
    Map<Boolean, List<String>> tableIdsPartitionedByReplicationSetupDone =
        tableIds.stream().collect(Collectors.partitioningBy(tableIdsWithReplication::contains));
    xClusterConfig.updateReplicationSetupDone(
        tableIdsPartitionedByReplicationSetupDone.get(true), true /* replicationSetupDone */);
    xClusterConfig.updateReplicationSetupDone(
        tableIdsPartitionedByReplicationSetupDone.get(false), false /* replicationSetupDone */);
    return true;
  }

  /**
   * It returns the list of table info from a universe for a set of table ids. Moreover, it ensures
   * that all requested tables exist on the source and target universes, and they have the same
   * type. Also, if the table type is YSQL and bootstrap is required, it ensures all the tables in a
   * keyspace are selected because per-table backup/restore is not supported for YSQL.
   *
   * @param ybService The service to get a YB client from
   * @param requestedTableIds The table ids to get the {@code TableInfo} for
   * @param bootstrapParams The parameters used for bootstrapping
   * @param sourceUniverse The universe to gather the {@code TableInfo}s from
   * @param targetUniverse The universe to check that matching tables exist on
   * @param currentReplicationGroupName The replication group name of the current xCluster config if
   *     any
   * @return A list of {@link MasterDdlOuterClass.ListTablesResponsePB.TableInfo} containing table
   *     info of the tables whose id is specified at {@code requestedTableIds}
   */
  public static List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo>
      getRequestedTableInfoListAndVerify(
          YBClientService ybService,
          Set<String> requestedTableIds,
          @Nullable BootstrapParams bootstrapParams,
          Universe sourceUniverse,
          Universe targetUniverse,
          @Nullable String currentReplicationGroupName,
          XClusterConfig.ConfigType configType) {
    log.debug(
        "requestedTableIds are {} and BootstrapParams are {}", requestedTableIds, bootstrapParams);
    // Ensure at least one table exists to verify.
    if (requestedTableIds.isEmpty()) {
      throw new IllegalArgumentException("requestedTableIds cannot be empty");
    }
    List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> sourceTableInfoList =
        getTableInfoList(ybService, sourceUniverse, false /* excludeSystemTables */);

    // Remove the transactions table id from the list of user tables.
    if (configType.equals(ConfigType.Txn)) {
      getTxnTableInfoIfExists(sourceTableInfoList)
          .ifPresent(
              txnTableInfo -> {
                String txnTableId = getTableId(txnTableInfo);
                requestedTableIds.removeIf(tableId -> tableId.equals(txnTableId));
                if (bootstrapParams != null && bootstrapParams.tables != null) {
                  bootstrapParams.tables.removeIf(tableId -> tableId.equals(txnTableId));
                }
              });
    }

    List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> requestedTableInfoList =
        sourceTableInfoList.stream()
            .filter(tableInfo -> requestedTableIds.contains(tableInfo.getId().toStringUtf8()))
            .collect(Collectors.toList());

    // All tables are found on the source universe.
    if (requestedTableInfoList.size() != requestedTableIds.size()) {
      Set<String> foundTableIds = getTableIds(requestedTableInfoList);
      Set<String> missingTableIds =
          requestedTableIds.stream()
              .filter(tableId -> !foundTableIds.contains(tableId))
              .collect(Collectors.toSet());
      throw new IllegalArgumentException(
          String.format(
              "Some of the tables were not found on the source universe (Please note that "
                  + "it might be an index table): was %d, found %d, missing tables: %s",
              requestedTableIds.size(), requestedTableInfoList.size(), missingTableIds));
    }

    CommonTypes.TableType tableType = requestedTableInfoList.get(0).getTableType();
    // All tables have the same type.
    if (!requestedTableInfoList.stream()
        .allMatch(tableInfo -> tableInfo.getTableType().equals(tableType))) {
      throw new IllegalArgumentException(
          "At least one table has a different type from others. "
              + "All tables in an xCluster config must have the same type. Please create separate "
              + "xCluster configs for different table types.");
    }

    // Txn xCluster is supported only for YSQL tables.
    if (configType.equals(ConfigType.Txn) && !tableType.equals(TableType.PGSQL_TABLE_TYPE)) {
      throw new IllegalArgumentException(
          String.format(
              "Transaction xCluster is supported only for YSQL tables. Table type %s is selected",
              tableType));
    }

    // XCluster replication can be set up only for YCQL and YSQL tables.
    if (!tableType.equals(CommonTypes.TableType.YQL_TABLE_TYPE)
        && !tableType.equals(CommonTypes.TableType.PGSQL_TABLE_TYPE)) {
      throw new IllegalArgumentException(
          String.format(
              "XCluster replication can be set up only for YCQL and YSQL tables: "
                  + "type %s requested",
              tableType));
    }
    log.info("All the requested tables are found and they have a type of {}", tableType);

    // Make sure all the tables on the source universe have a corresponding table on the target
    // universe.
    List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> targetTablesInfoList =
        getTableInfoList(ybService, targetUniverse, false /* excludeSystemTables */);
    Map<String, String> sourceTableIdTargetTableIdMap =
        getSourceTableIdTargetTableIdMap(requestedTableInfoList, targetTablesInfoList);

    if (bootstrapParams != null && bootstrapParams.tables != null) {
      // Ensure tables in bootstrapParams is a subset of requestedTableIds.
      if (!requestedTableIds.containsAll(bootstrapParams.tables)) {
        throw new IllegalArgumentException(
            String.format(
                "The set of tables in bootstrapParams (%s) is not a subset of "
                    + "requestedTableIds (%s)",
                bootstrapParams.tables, requestedTableIds));
      }

      // Bootstrapping must not be done for tables whose corresponding target table is in
      // replication. It also includes tables that are in reverse replication between the same
      // universes.
      Map<String, String> sourceTableIdTargetTableIdWithBootstrapMap =
          sourceTableIdTargetTableIdMap.entrySet().stream()
              .filter(entry -> bootstrapParams.tables.contains(entry.getKey()))
              .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
      bootstrapParams.tables =
          getTableIdsWithoutTablesOnTargetInReplication(
              ybService,
              sourceTableIdTargetTableIdWithBootstrapMap,
              targetUniverse,
              currentReplicationGroupName);

      // If table type is YSQL and bootstrap is requested, all tables in that keyspace are selected.
      if (tableType == CommonTypes.TableType.PGSQL_TABLE_TYPE) {
        groupByNamespaceId(requestedTableInfoList)
            .forEach(
                (namespaceId, tablesInfoList) -> {
                  Set<String> selectedTableIdsInNamespace = getTableIds(tablesInfoList);
                  Set<String> tableIdsNeedBootstrap =
                      selectedTableIdsInNamespace.stream()
                          .filter(bootstrapParams.tables::contains)
                          .collect(Collectors.toSet());
                  if (!tableIdsNeedBootstrap.isEmpty()) {
                    Set<String> tableIdsInNamespace =
                        sourceTableInfoList.stream()
                            .filter(
                                tableInfo ->
                                    !tableInfo
                                            .getRelationType()
                                            .equals(RelationType.SYSTEM_TABLE_RELATION)
                                        && tableInfo
                                            .getNamespace()
                                            .getId()
                                            .toStringUtf8()
                                            .equals(namespaceId))
                            .map(tableInfo -> tableInfo.getId().toStringUtf8())
                            .collect(Collectors.toSet());
                    if (tableIdsInNamespace.size() != selectedTableIdsInNamespace.size()) {
                      throw new IllegalArgumentException(
                          String.format(
                              "For YSQL tables, all the tables in a keyspace must be selected: "
                                  + "selected: %s, tables in the keyspace: %s",
                              selectedTableIdsInNamespace, tableIdsInNamespace));
                    }
                  }
                });
      }
    }

    // Add transactions table id to the end of requestedTableInfoList if required.
    if (configType.equals(ConfigType.Txn)) {
      if (Objects.isNull(bootstrapParams)) {
        throw new IllegalArgumentException(
            "To create a transactional xCluster, \"bootstrapParams\" are required");
      }
      Optional<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> txnTableInfoSourceOptional =
          getTxnTableInfoIfExists(sourceTableInfoList);
      Optional<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> txnTableInfoTargetOptional =
          getTxnTableInfoIfExists(targetTablesInfoList);
      if (txnTableInfoSourceOptional.isPresent()) {
        requestedTableInfoList.add(txnTableInfoSourceOptional.get());
      } else {
        throw new IllegalArgumentException(
            String.format(
                "Transactional replication cannot be set up because %s.%s table does not "
                    + "exist on the source universe",
                TRANSACTION_STATUS_TABLE_NAMESPACE, TRANSACTION_STATUS_TABLE_NAME));
      }
      if (!txnTableInfoTargetOptional.isPresent()) {
        throw new IllegalArgumentException(
            String.format(
                "Transactional replication cannot be set up because %s.%s table does not "
                    + "exist on the target universe",
                TRANSACTION_STATUS_TABLE_NAMESPACE, TRANSACTION_STATUS_TABLE_NAME));
      }
    }

    log.debug("requestedTableInfoList is {}", requestedTableInfoList);
    return requestedTableInfoList;
  }

  private static Set<String> getTableIdsInReplicationOnTargetUniverse(
      YBClientService ybService,
      Collection<String> tableIds,
      Universe targetUniverse,
      @Nullable String currentReplicationGroupName) {
    Set<String> tableIdsInReplicationOnTargetUniverse = new HashSet<>();
    // In replication as source. It will take care of bidirectional cases as well.
    List<XClusterConfig> xClusterConfigsAsSource =
        XClusterConfig.getBySourceUniverseUUID(targetUniverse.getUniverseUUID());
    xClusterConfigsAsSource.forEach(
        xClusterConfig -> {
          tableIdsInReplicationOnTargetUniverse.addAll(
              xClusterConfig.getTableIds().stream()
                  .filter(tableIds::contains)
                  .collect(Collectors.toSet()));
        });
    // In replication as target.
    String targetUniverseMasterAddresses = targetUniverse.getMasterAddresses();
    String targetUniverseCertificate = targetUniverse.getCertificateNodetoNode();
    try (YBClient client =
        ybService.getClient(targetUniverseMasterAddresses, targetUniverseCertificate)) {
      CatalogEntityInfo.SysClusterConfigEntryPB clusterConfig =
          getClusterConfig(client, targetUniverse.getUniverseUUID());
      tableIdsInReplicationOnTargetUniverse.addAll(
          getConsumerTableIdsFromClusterConfig(
                  clusterConfig, Collections.singleton(currentReplicationGroupName))
              .stream()
              .filter(tableIds::contains)
              .collect(Collectors.toSet()));
    } catch (Exception e) {
      log.error("getTableIdsInReplicationOnTargetUniverse hit error : {}", e.getMessage());
      throw new RuntimeException(e);
    }
    log.debug("tableIdsInReplicationOnTargetUniverse is {}", tableIdsInReplicationOnTargetUniverse);
    return tableIdsInReplicationOnTargetUniverse;
  }

  private static Set<String> getTableIdsWithoutTablesOnTargetInReplication(
      YBClientService ybService,
      Map<String, String> sourceTableIdTargetTableIdMap,
      Universe targetUniverse,
      @Nullable String currentReplicationGroupName) {
    Set<String> tableIdsInReplicationOnTargetUniverse =
        getTableIdsInReplicationOnTargetUniverse(
            ybService,
            sourceTableIdTargetTableIdMap.values(),
            targetUniverse,
            currentReplicationGroupName);
    if (tableIdsInReplicationOnTargetUniverse.isEmpty()) {
      return sourceTableIdTargetTableIdMap.keySet();
    }
    log.warn(
        "Tables {} are in replication on the target universe {} of this config and cannot be "
            + "bootstrapped; Bootstrapping for their corresponding source table will be disabled.",
        tableIdsInReplicationOnTargetUniverse,
        targetUniverse.getUniverseUUID());
    return sourceTableIdTargetTableIdMap.entrySet().stream()
        .filter(entry -> !tableIdsInReplicationOnTargetUniverse.contains(entry.getValue()))
        .map(Entry::getKey)
        .collect(Collectors.toSet());
  }

  public static Map<String, String> getSourceTableIdTargetTableIdMap(
      List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> requestedSourceTablesInfoList,
      List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> targetTablesInfoList) {
    Map<String, String> sourceTableIdTargetTableIdMap = new HashMap<>();
    if (requestedSourceTablesInfoList.isEmpty()) {
      log.warn("requestedSourceTablesInfoList is empty");
      return sourceTableIdTargetTableIdMap;
    }
    Set<String> notFoundNamespaces = new HashSet<>();
    Set<String> notFoundTables = new HashSet<>();
    // All tables in `requestedTableInfoList` have the same type.
    CommonTypes.TableType tableType = requestedSourceTablesInfoList.get(0).getTableType();

    // Namespace name for one table type is unique.
    Map<String, List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo>>
        targetNamespaceNameTablesInfoListMap =
            groupByNamespaceName(
                targetTablesInfoList.stream()
                    .filter(tableInfo -> tableInfo.getTableType().equals(tableType))
                    .collect(Collectors.toList()));

    groupByNamespaceName(requestedSourceTablesInfoList)
        .forEach(
            (namespaceName, tableInfoListOnSource) -> {
              List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> tableInfoListOnTarget =
                  targetNamespaceNameTablesInfoListMap.get(namespaceName);
              if (tableInfoListOnTarget != null) {
                if (tableType.equals(TableType.PGSQL_TABLE_TYPE)) {
                  Map<String, List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo>>
                      schemaNameTableInfoListOnSourceMap =
                          groupByPgSchemaName(tableInfoListOnSource);
                  Map<String, List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo>>
                      schemaNameTableInfoListOnTargetMap =
                          groupByPgSchemaName(tableInfoListOnTarget);
                  schemaNameTableInfoListOnSourceMap.forEach(
                      (schemaName, TableInfoListInSchemaOnSource) -> {
                        List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo>
                            tableInfoListInSchemaOnTarget =
                                schemaNameTableInfoListOnTargetMap.get(schemaName);
                        if (tableInfoListInSchemaOnTarget != null) {
                          Pair<Map<String, String>, Set<String>> tableIdMapNotFoundTableIdsSetPair =
                              getSourceTableIdTargetTableIdMapUnique(
                                  TableInfoListInSchemaOnSource, tableInfoListInSchemaOnTarget);
                          sourceTableIdTargetTableIdMap.putAll(
                              tableIdMapNotFoundTableIdsSetPair.getFirst());
                          notFoundTables.addAll(tableIdMapNotFoundTableIdsSetPair.getSecond());
                        } else {
                          notFoundTables.addAll(
                              TableInfoListInSchemaOnSource.stream()
                                  .map(tableInfo -> tableInfo.getId().toStringUtf8())
                                  .collect(Collectors.toSet()));
                        }
                      });
                } else {
                  Pair<Map<String, String>, Set<String>> tableIdMapNotFoundTableIdsSetPair =
                      getSourceTableIdTargetTableIdMapUnique(
                          tableInfoListOnSource, tableInfoListOnTarget);
                  sourceTableIdTargetTableIdMap.putAll(
                      tableIdMapNotFoundTableIdsSetPair.getFirst());
                  notFoundTables.addAll(tableIdMapNotFoundTableIdsSetPair.getSecond());
                }
              } else {
                notFoundNamespaces.add(namespaceName);
              }
            });
    if (!notFoundNamespaces.isEmpty() || !notFoundTables.isEmpty()) {
      throw new IllegalStateException(
          String.format(
              "Not found namespaces on the target universe: %s, not found tables on the "
                  + "target universe: %s",
              notFoundNamespaces, notFoundTables));
    }
    log.debug("sourceTableIdTargetTableIdMap is {}", sourceTableIdTargetTableIdMap);
    return sourceTableIdTargetTableIdMap;
  }

  /**
   * It assumes table names in both {@code tableInfoListOnSource} and {@code tableInfoListOnTarget}
   * are unique.
   *
   * @param tableInfoListOnSource The list of table info on the source in a namespace.schema for
   *     `PGSQL_TABLE_TYPE` or namespace for others
   * @param tableInfoListOnTarget The list of table info on the target in a namespace.schema for
   *     `PGSQL_TABLE_TYPE` or namespace for others
   * @return A pair containing a map of source table ID to their corresponding target table ID and a
   *     set of not found table IDs
   */
  private static Pair<Map<String, String>, Set<String>> getSourceTableIdTargetTableIdMapUnique(
      List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> tableInfoListOnSource,
      List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> tableInfoListOnTarget) {
    Map<String, String> sourceTableIdTargetTableIdMap = new HashMap<>();
    Set<String> notFoundTables = new HashSet<>();
    Map<String, String> tableNameTableIdOnTargetMap =
        tableInfoListOnTarget.stream()
            .collect(
                Collectors.toMap(
                    TableInfo::getName, tableInfo -> tableInfo.getId().toStringUtf8()));
    tableInfoListOnSource.forEach(
        tableInfo -> {
          String tableIdOnTarget = tableNameTableIdOnTargetMap.get(tableInfo.getName());
          if (tableIdOnTarget != null) {
            sourceTableIdTargetTableIdMap.put(tableInfo.getId().toStringUtf8(), tableIdOnTarget);
          } else {
            notFoundTables.add(tableInfo.getId().toStringUtf8());
          }
        });
    return new Pair<>(sourceTableIdTargetTableIdMap, notFoundTables);
  }

  /**
   * It gets the table schema information for a list of main tables from a universe.
   *
   * @param ybService The service to get a YB client from
   * @param universe The universe to get the table schema information from
   * @param mainTableUuidList A set of main table uuids to get the schema information for
   * @return A map of main table uuid to its schema information
   */
  public static Map<String, GetTableSchemaResponse> getTableSchemas(
      YBClientService ybService, Universe universe, Set<String> mainTableUuidList) {
    if (mainTableUuidList.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, GetTableSchemaResponse> tableSchemaMap = new HashMap<>();
    String universeMasterAddresses = universe.getMasterAddresses(true /* mastersQueryable */);
    String universeCertificate = universe.getCertificateNodetoNode();
    try (YBClient client = ybService.getClient(universeMasterAddresses, universeCertificate)) {
      for (String tableUuid : mainTableUuidList) {
        // To make sure there is no `-` in the table UUID.
        tableUuid = tableUuid.replace("-", "");
        tableSchemaMap.put(tableUuid, client.getTableSchemaByUUID(tableUuid));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return tableSchemaMap;
  }

  public static Map<String, List<String>> getMainTableIndexTablesMap(
      YBClientService ybService, Universe universe, Set<String> mainTableUuidList) {
    Map<String, GetTableSchemaResponse> tableSchemaMap =
        getTableSchemas(ybService, universe, mainTableUuidList);
    Map<String, List<String>> mainTableIndexTablesMap = new HashMap<>();
    tableSchemaMap.forEach(
        (mainTableUuid, tableSchemaResponse) -> {
          List<String> indexTableUuidList = new ArrayList<>();
          tableSchemaResponse
              .getIndexes()
              .forEach(
                  indexInfo -> {
                    if (!indexInfo.getIndexedTableId().equals(mainTableUuid)) {
                      throw new IllegalStateException(
                          String.format(
                              "Received index table with uuid %s as part of "
                                  + "getTableSchemaByUUID response for the main table %s, but "
                                  + "indexedTableId in its index info is %s",
                              indexInfo.getTableId(),
                              mainTableUuid,
                              indexInfo.getIndexedTableId()));
                    }
                    indexTableUuidList.add(indexInfo.getTableId().replace("-", ""));
                  });
          mainTableIndexTablesMap.put(mainTableUuid, indexTableUuidList);
        });
    log.debug("mainTableIndexTablesMap for {} is {}", mainTableUuidList, mainTableIndexTablesMap);
    return mainTableIndexTablesMap;
  }

  public static Set<String> getTableIdsToAdd(
      Set<String> currentTableIds, Set<String> destinationTableIds) {
    return destinationTableIds.stream()
        .filter(tableId -> !currentTableIds.contains(tableId))
        .collect(Collectors.toSet());
  }

  public static Set<String> getTableIdsToRemove(
      Set<String> currentTableIds, Set<String> destinationTableIds) {
    return currentTableIds.stream()
        .filter(tableId -> !destinationTableIds.contains(tableId))
        .collect(Collectors.toSet());
  }

  public static Pair<Set<String>, Set<String>> getTableIdsDiff(
      Set<String> currentTableIds, Set<String> destinationTableIds) {
    return new Pair<>(
        getTableIdsToAdd(currentTableIds, destinationTableIds),
        getTableIdsToRemove(currentTableIds, destinationTableIds));
  }

  public static Set<String> convertTableUuidStringsToTableIdSet(Collection<String> tableUuids) {
    return tableUuids.stream()
        .map(uuidOrId -> uuidOrId.replace("-", ""))
        .collect(Collectors.toSet());
  }

  // TableInfo helpers: Convenient methods for working with
  // MasterDdlOuterClass.ListTablesResponsePB.TableInfo.
  // --------------------------------------------------------------------------------
  public static List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> getTableInfoList(
      YBClientService ybService, Universe universe, boolean excludeSystemTables) {
    List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> tableInfoList;
    String universeMasterAddresses = universe.getMasterAddresses(true /* mastersQueryable */);
    String universeCertificate = universe.getCertificateNodetoNode();
    try (YBClient client = ybService.getClient(universeMasterAddresses, universeCertificate)) {
      ListTablesResponse listTablesResponse =
          client.getTablesList(null /* nameFilter */, excludeSystemTables, null /* namespace */);
      tableInfoList = listTablesResponse.getTableInfoList();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return tableInfoList;
  }

  public static List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> getTableInfoList(
      YBClientService ybService, Universe universe) {
    return getTableInfoList(ybService, universe, true /* excludeSystemTables */);
  }

  public static List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> getTableInfoList(
      YBClientService ybService, Universe universe, Collection<String> tableIds) {
    return getTableInfoList(ybService, universe).stream()
        .filter(tableInfo -> tableIds.contains(tableInfo.getId().toStringUtf8()))
        .collect(Collectors.toList());
  }

  protected final List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> getTableInfoList(
      Universe universe) {
    return getTableInfoList(this.ybService, universe);
  }

  public static String getTableId(MasterDdlOuterClass.ListTablesResponsePB.TableInfo tableInfo) {
    return tableInfo.getId().toStringUtf8();
  }

  public static Set<String> getTableIds(
      Collection<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> tablesInfoList) {
    if (tablesInfoList == null) {
      return Collections.emptySet();
    }
    return tablesInfoList.stream()
        .map(XClusterConfigTaskBase::getTableId)
        .collect(Collectors.toSet());
  }

  public static Set<String> getTableIds(
      Collection<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> tablesInfoList,
      @Nullable MasterDdlOuterClass.ListTablesResponsePB.TableInfo txnTableInfo) {
    if (Objects.nonNull(txnTableInfo)) {
      return getTableIds(
          Stream.concat(tablesInfoList.stream(), Stream.of(txnTableInfo))
              .collect(Collectors.toList()));
    }
    return getTableIds(tablesInfoList);
  }

  public static Map<String, List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo>>
      groupByNamespaceId(
          Collection<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> tablesInfoList) {
    return tablesInfoList.stream()
        .collect(
            Collectors.groupingBy(tableInfo -> tableInfo.getNamespace().getId().toStringUtf8()));
  }

  public static Map<String, List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo>>
      groupByNamespaceName(
          Collection<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> tablesInfoList) {
    return tablesInfoList.stream()
        .collect(Collectors.groupingBy(tableInfo -> tableInfo.getNamespace().getName()));
  }

  public static Map<String, List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo>>
      groupByPgSchemaName(
          Collection<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> tablesInfoList) {
    if (tablesInfoList.isEmpty()) {
      return Collections.emptyMap();
    }
    // All tables in `requestedTableInfoList` have the same type.
    CommonTypes.TableType tableType = tablesInfoList.iterator().next().getTableType();
    if (!tableType.equals(TableType.PGSQL_TABLE_TYPE)) {
      throw new RuntimeException(
          String.format(
              "Only PGSQL_TABLE_TYPE tables have schema name but received tables of type %s",
              tableType));
    }
    return tablesInfoList.stream().collect(Collectors.groupingBy(TableInfo::getPgschemaName));
  }

  public static CommonTypes.TableType getTableType(
      List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> tablesInfoList) {
    if (tablesInfoList.isEmpty()) {
      return null;
    }
    return tablesInfoList.get(0).getTableType();
  }
  // --------------------------------------------------------------------------------
  // End of TableInfo helpers.

  public static boolean isTransactionalReplication(
      @Nullable Universe sourceUniverse, Universe targetUniverse) {
    Map<String, String> targetMasterGFlags =
        GFlagsUtil.getBaseGFlags(
            ServerType.MASTER,
            targetUniverse.getUniverseDetails().getPrimaryCluster(),
            targetUniverse.getUniverseDetails().clusters);
    String gflagValueOnTarget =
        targetMasterGFlags.get(ENABLE_REPLICATE_TRANSACTION_STATUS_TABLE_GFLAG);
    Boolean gflagValueOnTargetBoolean =
        gflagValueOnTarget != null ? Boolean.valueOf(gflagValueOnTarget) : null;

    if (sourceUniverse != null) {
      Map<String, String> sourceMasterGFlags =
          GFlagsUtil.getBaseGFlags(
              ServerType.MASTER,
              sourceUniverse.getUniverseDetails().getPrimaryCluster(),
              sourceUniverse.getUniverseDetails().clusters);

      // Replication between a universe with the gflag `enable_replicate_transaction_status_table`
      // and a universe without it is not allowed.
      String gflagValueOnSource =
          sourceMasterGFlags.get(ENABLE_REPLICATE_TRANSACTION_STATUS_TABLE_GFLAG);
      Boolean gflagValueOnSourceBoolean =
          gflagValueOnSource != null ? Boolean.valueOf(gflagValueOnSource) : null;
      if (!Objects.equals(gflagValueOnSourceBoolean, gflagValueOnTargetBoolean)) {
        throw new PlatformServiceException(
            Status.BAD_REQUEST,
            String.format(
                "The gflag %s must be set to the same value on both universes",
                ENABLE_REPLICATE_TRANSACTION_STATUS_TABLE_GFLAG));
      }
    }

    return gflagValueOnTargetBoolean == null
        ? ENABLE_REPLICATE_TRANSACTION_STATUS_TABLE_DEFAULT
        : gflagValueOnTargetBoolean;
  }

  public static boolean otherXClusterConfigsAsTargetExist(
      UUID universeUuid, UUID xClusterConfigUuid) {
    List<XClusterConfig> xClusterConfigs = XClusterConfig.getByTargetUniverseUUID(universeUuid);
    if (xClusterConfigs.size() == 0) {
      return false;
    }
    return xClusterConfigs.size() != 1
        || !xClusterConfigs.get(0).getUuid().equals(xClusterConfigUuid);
  }

  /**
   * It returns the {@code TableInfo} object for the transactions table from tableInfoList. It
   * assumes the list of {@code TableInfo} includes system tables.
   *
   * @param tableInfoList The list of {@code TableInfo} objects including system tables
   * @return An optional of {@link MasterDdlOuterClass.ListTablesResponsePB.TableInfo} for the
   *     transactions table
   */
  public static Optional<MasterDdlOuterClass.ListTablesResponsePB.TableInfo>
      getTxnTableInfoIfExists(
          List<MasterDdlOuterClass.ListTablesResponsePB.TableInfo> tableInfoList) {
    return tableInfoList.stream().filter(XClusterConfigTaskBase::isTableInfoForTxnTable).findAny();
  }

  public static boolean isTableInfoForTxnTable(
      MasterDdlOuterClass.ListTablesResponsePB.TableInfo tableInfo) {
    return tableInfo.getTableType().equals(TableType.TRANSACTION_STATUS_TABLE_TYPE)
        && tableInfo.getNamespace().getName().equals(TRANSACTION_STATUS_TABLE_NAMESPACE)
        && tableInfo.getName().equals(TRANSACTION_STATUS_TABLE_NAME);
  }
}
