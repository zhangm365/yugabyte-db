/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks;

import static com.yugabyte.yw.commissioner.tasks.BackupUniverse.BACKUP_ATTEMPT_COUNTER;
import static com.yugabyte.yw.commissioner.tasks.BackupUniverse.BACKUP_FAILURE_COUNTER;
import static com.yugabyte.yw.commissioner.tasks.BackupUniverse.BACKUP_SUCCESS_COUNTER;
import static com.yugabyte.yw.commissioner.tasks.BackupUniverse.SCHEDULED_BACKUP_ATTEMPT_COUNTER;
import static com.yugabyte.yw.commissioner.tasks.BackupUniverse.SCHEDULED_BACKUP_FAILURE_COUNTER;
import static com.yugabyte.yw.commissioner.tasks.BackupUniverse.SCHEDULED_BACKUP_SUCCESS_COUNTER;
import static com.yugabyte.yw.common.metrics.MetricService.buildMetricTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.commissioner.ITask.Abortable;
import com.yugabyte.yw.commissioner.UserTaskDetails;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.common.BackupUtil;
import com.yugabyte.yw.common.StorageUtil;
import com.yugabyte.yw.common.customer.config.CustomerConfigService;
import com.yugabyte.yw.common.metrics.MetricLabelsBuilder;
import com.yugabyte.yw.common.ybc.YbcManager;
import com.yugabyte.yw.forms.BackupRequestParams;
import com.yugabyte.yw.models.Backup;
import com.yugabyte.yw.models.Backup.BackupCategory;
import com.yugabyte.yw.models.Backup.BackupState;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.Schedule;
import com.yugabyte.yw.models.ScheduleTask;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.configs.CustomerConfig;
import com.yugabyte.yw.models.configs.CustomerConfig.ConfigState;
import com.yugabyte.yw.models.helpers.CommonUtils;
import com.yugabyte.yw.models.helpers.PlatformMetrics;
import com.yugabyte.yw.models.helpers.TaskType;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.yb.CommonTypes.TableType;
import play.libs.Json;

@Slf4j
@Abortable
public class CreateBackup extends UniverseTaskBase {

  private final CustomerConfigService customerConfigService;
  private final YbcManager ybcManager;
  private final BackupUtil backupUtil;

  @Inject
  protected CreateBackup(
      BaseTaskDependencies baseTaskDependencies,
      CustomerConfigService customerConfigService,
      YbcManager ybcManager,
      BackupUtil backupUtil) {
    super(baseTaskDependencies);
    this.customerConfigService = customerConfigService;
    this.ybcManager = ybcManager;
    this.backupUtil = backupUtil;
  }

  protected BackupRequestParams params() {
    return (BackupRequestParams) taskParams;
  }

  @Override
  protected String getExecutorPoolName() {
    return "backup_task";
  }

  @Override
  public void run() {
    Set<String> tablesToBackup = new HashSet<>();
    Universe universe = Universe.getOrBadRequest(params().getUniverseUUID());
    MetricLabelsBuilder metricLabelsBuilder = MetricLabelsBuilder.create().appendSource(universe);
    BACKUP_ATTEMPT_COUNTER.labels(metricLabelsBuilder.getPrometheusValues()).inc();
    boolean isUniverseLocked = false;
    boolean ybcBackup =
        !BackupCategory.YB_BACKUP_SCRIPT.equals(params().backupCategory)
            && universe.isYbcEnabled()
            && !params().backupType.equals(TableType.REDIS_TABLE_TYPE);
    try {
      checkUniverseVersion();

      // Update the universe DB with the update to be performed and set the 'updateInProgress' flag
      // to prevent other updates from happening.
      lockUniverse(-1 /* expectedUniverseVersion */);
      isUniverseLocked = true;
      try {
        // Check if the storage config is in active state or not.
        CustomerConfig customerConfig =
            customerConfigService.getOrBadRequest(
                params().customerUUID, params().storageConfigUUID);
        if (!customerConfig.getState().equals(ConfigState.Active)) {
          throw new RuntimeException("Storage config cannot be used as it is not in Active state");
        }
        // Clear any previous subtasks if any.
        getRunnableTask().reset();

        StorageUtil.getStorageUtil(customerConfig.getName())
            .validateStorageConfigOnUniverse(customerConfig, universe);

        if (ybcBackup
            && universe.isYbcEnabled()
            && !universe
                .getUniverseDetails()
                .getYbcSoftwareVersion()
                .equals(ybcManager.getStableYbcVersion())) {

          if (universe
              .getUniverseDetails()
              .getPrimaryCluster()
              .userIntent
              .providerType
              .equals(Common.CloudType.kubernetes)) {
            createUpgradeYbcTaskOnK8s(params().getUniverseUUID(), ybcManager.getStableYbcVersion())
                .setSubTaskGroupType(SubTaskGroupType.UpgradingYbc);
          } else {
            createUpgradeYbcTask(params().getUniverseUUID(), ybcManager.getStableYbcVersion(), true)
                .setSubTaskGroupType(SubTaskGroupType.UpgradingYbc);
          }
        }

        Backup backup =
            createAllBackupSubtasks(
                params(),
                UserTaskDetails.SubTaskGroupType.CreatingTableBackup,
                tablesToBackup,
                ybcBackup);
        log.info("Task id {} for the backup {}", backup.getTaskUUID(), backup.getBackupUUID());

        // Marks the update of this universe as a success only if all the tasks before it succeeded.
        createMarkUniverseUpdateSuccessTasks()
            .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse);

        taskInfo = String.join(",", tablesToBackup);

        getRunnableTask().runSubTasks(true);
        unlockUniverseForUpdate();
        isUniverseLocked = false;

        Backup currentBackup =
            Backup.getOrBadRequest(params().customerUUID, backup.getBackupUUID());
        if (ybcBackup) {
          if (!currentBackup.getBaseBackupUUID().equals(currentBackup.getBackupUUID())) {
            Backup baseBackup =
                Backup.getOrBadRequest(params().customerUUID, currentBackup.getBaseBackupUUID());
            currentBackup.onCompletion();
            baseBackup.onIncrementCompletion(currentBackup.getCreateTime());
          } else {
            currentBackup.onCompletion();
          }
        }
        BACKUP_SUCCESS_COUNTER.labels(metricLabelsBuilder.getPrometheusValues()).inc();
        metricService.setOkStatusMetric(
            buildMetricTemplate(PlatformMetrics.CREATE_BACKUP_STATUS, universe));

      } catch (CancellationException ce) {
        log.error("Aborting backups for task: {}", userTaskUUID);
        Backup.fetchAllBackupsByTaskUUID(userTaskUUID)
            .forEach(
                backup -> {
                  backup.transitionState(BackupState.Stopped);
                  backup.setCompletionTime(new Date());
                  backup.save();
                });
        unlockUniverseForUpdate(false);
        isUniverseLocked = false;
        throw ce;
      } catch (Throwable t) {
        if (params().alterLoadBalancer) {
          // If the task failed, we don't want the loadbalancer to be
          // disabled, so we enable it again in case of errors.
          setTaskQueueAndRun(
              () ->
                  createLoadBalancerStateChangeTask(true)
                      .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse));
        }
        throw t;
      }
    } catch (Throwable t) {
      try {
        log.error("Error executing task {} with error='{}'.", getName(), t.getMessage(), t);
        // Ensures that backup reaches a final state
        Backup.fetchAllBackupsByTaskUUID(userTaskUUID)
            .forEach(
                backup -> {
                  if (backup.getState().equals(BackupState.InProgress)) {
                    backup.transitionState(BackupState.Failed);
                    backup.setCompletionTime(new Date());
                    backup.save();
                  }
                });
        BACKUP_FAILURE_COUNTER.labels(metricLabelsBuilder.getPrometheusValues()).inc();
        metricService.setFailureStatusMetric(
            buildMetricTemplate(PlatformMetrics.CREATE_BACKUP_STATUS, universe));
      } finally {
        // Run an unlock in case the task failed before getting to the unlock. It is okay if it
        // errors out.
        if (isUniverseLocked) {
          unlockUniverseForUpdate();
        }
      }
      throw t;
    }
    log.info("Finished {} task.", getName());
  }

  public void runScheduledBackup(
      Schedule schedule, Commissioner commissioner, boolean alreadyRunning, UUID baseBackupUUID) {
    UUID customerUUID = schedule.getCustomerUUID();
    Customer customer = Customer.get(customerUUID);
    JsonNode params = schedule.getTaskParams();
    BackupRequestParams taskParams = Json.fromJson(params, BackupRequestParams.class);
    taskParams.scheduleUUID = schedule.getScheduleUUID();
    taskParams.baseBackupUUID = baseBackupUUID;
    Universe universe;
    try {
      universe = Universe.getOrBadRequest(taskParams.getUniverseUUID());
    } catch (Exception e) {
      log.info(
          "Deleting the schedule {} as the source universe {} does not exists.",
          schedule.getScheduleUUID(),
          taskParams.getUniverseUUID());
      schedule.delete();
      return;
    }
    MetricLabelsBuilder metricLabelsBuilder = MetricLabelsBuilder.create().appendSource(universe);
    SCHEDULED_BACKUP_ATTEMPT_COUNTER.labels(metricLabelsBuilder.getPrometheusValues()).inc();
    Map<String, String> config = universe.getConfig();
    boolean shouldTakeBackup =
        !universe.getUniverseDetails().universePaused
            && config.get(Universe.TAKE_BACKUPS).equals("true");
    if (alreadyRunning || !shouldTakeBackup || universe.getUniverseDetails().updateInProgress) {
      if (shouldTakeBackup) {
        if (baseBackupUUID == null) {
          // Update backlog status only for full backup as we don't store expected task time
          // for incremental backups and check its requirement in every 2 minutes.
          schedule.updateBacklogStatus(true);
        }
        log.debug("Schedule {} backlog status is set to true", schedule.getScheduleUUID());
        SCHEDULED_BACKUP_FAILURE_COUNTER.labels(metricLabelsBuilder.getPrometheusValues()).inc();
        metricService.setFailureStatusMetric(
            buildMetricTemplate(PlatformMetrics.SCHEDULE_BACKUP_STATUS, universe));
      }
      String stateLogMsg = CommonUtils.generateStateLogMsg(universe, alreadyRunning);
      log.warn(
          "Cannot run Backup task on universe {} due to the state {}",
          taskParams.getUniverseUUID().toString(),
          stateLogMsg);
      return;
    }
    UUID taskUUID = commissioner.submit(TaskType.CreateBackup, taskParams);
    ScheduleTask.create(taskUUID, schedule.getScheduleUUID());
    if (schedule.isBacklogStatus()) {
      schedule.updateBacklogStatus(false);
      log.debug("Schedule {} backlog status is set to false", schedule.getScheduleUUID());
    }
    log.info(
        "Submitted backup for universe: {}, task uuid = {}.",
        taskParams.getUniverseUUID(),
        taskUUID);
    CustomerTask.create(
        customer,
        taskParams.getUniverseUUID(),
        taskUUID,
        CustomerTask.TargetType.Backup,
        CustomerTask.TaskType.Create,
        universe.getName());
    log.info(
        "Saved task uuid {} in customer tasks table for universe {}:{}",
        taskUUID,
        taskParams.getUniverseUUID(),
        universe.getName());
    SCHEDULED_BACKUP_SUCCESS_COUNTER.labels(metricLabelsBuilder.getPrometheusValues()).inc();
    metricService.setOkStatusMetric(
        buildMetricTemplate(PlatformMetrics.SCHEDULE_BACKUP_STATUS, universe));
  }
}
