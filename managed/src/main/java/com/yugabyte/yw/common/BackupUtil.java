// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import static com.cronutils.model.CronType.UNIX;
import static com.yugabyte.yw.common.Util.getUUIDRepresentation;
import static java.lang.Math.abs;
import static java.lang.Math.max;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.INTERNAL_SERVER_ERROR;

import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.inject.Singleton;
import com.yugabyte.yw.common.config.RuntimeConfGetter;
import com.yugabyte.yw.common.config.UniverseConfKeys;
import com.yugabyte.yw.common.customer.config.CustomerConfigService;
import com.yugabyte.yw.common.metrics.MetricService;
import com.yugabyte.yw.common.services.YBClientService;
import com.yugabyte.yw.common.utils.Pair;
import com.yugabyte.yw.common.ybc.YbcBackupUtil;
import com.yugabyte.yw.forms.BackupRequestParams.KeyspaceTable;
import com.yugabyte.yw.forms.BackupTableParams;
import com.yugabyte.yw.forms.RestoreBackupParams;
import com.yugabyte.yw.forms.RestoreBackupParams.BackupStorageInfo;
import com.yugabyte.yw.models.Backup;
import com.yugabyte.yw.models.Backup.BackupCategory;
import com.yugabyte.yw.models.Backup.BackupState;
import com.yugabyte.yw.models.BackupResp;
import com.yugabyte.yw.models.BackupResp.BackupRespBuilder;
import com.yugabyte.yw.models.CommonBackupInfo;
import com.yugabyte.yw.models.CommonBackupInfo.CommonBackupInfoBuilder;
import com.yugabyte.yw.models.KmsConfig;
import com.yugabyte.yw.models.Metric;
import com.yugabyte.yw.models.PitrConfig;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.configs.CustomerConfig;
import com.yugabyte.yw.models.configs.data.CustomerConfigStorageData;
import com.yugabyte.yw.models.configs.data.CustomerConfigStorageNFSData;
import com.yugabyte.yw.models.helpers.KeyspaceTablesList;
import com.yugabyte.yw.models.helpers.KnownAlertLabels;
import com.yugabyte.yw.models.helpers.PlatformMetrics;
import com.yugabyte.yw.models.helpers.TaskType;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.CommonTypes.TableType;
import org.yb.CommonTypes.YQLDatabase;
import org.yb.client.SnapshotInfo;
import org.yb.client.YBClient;
import org.yb.master.CatalogEntityInfo.SysSnapshotEntryPB.State;
import org.yb.master.MasterDdlOuterClass.ListTablesResponsePB.TableInfo;
import org.yb.master.MasterTypes.RelationType;

@Singleton
public class BackupUtil {

  @Inject YBClientService ybService;

  @Inject CustomerConfigService customerConfigService;

  @Inject RuntimeConfGetter confGetter;

  public static final Logger LOG = LoggerFactory.getLogger(BackupUtil.class);

  public static final int EMR_MULTIPLE = 8;
  public static final int BACKUP_PREFIX_LENGTH = 8;
  public static final int TS_FMT_LENGTH = 19;
  public static final int UNIV_PREFIX_LENGTH = 6;
  public static final int UUID_LENGTH = 36;
  public static final long MIN_SCHEDULE_DURATION_IN_SECS = 3600L;
  public static final long MIN_SCHEDULE_DURATION_IN_MILLIS = MIN_SCHEDULE_DURATION_IN_SECS * 1000L;
  public static final long MIN_INCREMENTAL_SCHEDULE_DURATION_IN_MILLIS = 1800000L;
  public static final String BACKUP_SIZE_FIELD = "backup_size_in_bytes";
  public static final String YBC_BACKUP_IDENTIFIER = "ybc_backup";
  public static final String YB_CLOUD_COMMAND_TYPE = "table";
  public static final String K8S_CERT_PATH = "/opt/certs/yugabyte/";
  public static final String VM_CERT_DIR = "/yugabyte-tls-config/";
  public static final String BACKUP_SCRIPT = "bin/yb_backup.py";
  public static final String REGION_LOCATIONS = "REGION_LOCATIONS";
  public static final String REGION_NAME = "REGION";
  public static final String SNAPSHOT_URL_FIELD = "snapshot_url";
  public static final String UNIVERSE_UUID_IDENTIFIER_STRING =
      "(univ-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/)";
  public static final String BACKUP_IDENTIFIER_STRING =
      "(.*?)" + "(%s)?" + UNIVERSE_UUID_IDENTIFIER_STRING + "((ybc_)?backup-(.*))";
  public static final String YBC_BACKUP_LOCATION_IDENTIFIER_STRING =
      "(/?)" + UNIVERSE_UUID_IDENTIFIER_STRING + "(" + YBC_BACKUP_IDENTIFIER + ")";
  public static final Pattern PATTERN_FOR_YBC_BACKUP_LOCATION =
      Pattern.compile(YBC_BACKUP_LOCATION_IDENTIFIER_STRING);
  public static final List<TaskType> BACKUP_TASK_TYPES =
      ImmutableList.of(TaskType.CreateBackup, TaskType.BackupUniverse, TaskType.MultiTableBackup);

  public static BiMap<TableType, YQLDatabase> TABLE_TYPE_TO_YQL_DATABASE_MAP;

  static {
    TABLE_TYPE_TO_YQL_DATABASE_MAP = HashBiMap.create();
    TABLE_TYPE_TO_YQL_DATABASE_MAP.put(TableType.YQL_TABLE_TYPE, YQLDatabase.YQL_DATABASE_CQL);
    TABLE_TYPE_TO_YQL_DATABASE_MAP.put(TableType.PGSQL_TABLE_TYPE, YQLDatabase.YQL_DATABASE_PGSQL);
    TABLE_TYPE_TO_YQL_DATABASE_MAP.put(TableType.REDIS_TABLE_TYPE, YQLDatabase.YQL_DATABASE_REDIS);
  }

  public enum ApiType {
    YSQL("YSQL"),
    YCQL("YCQL");

    private final String value;

    ApiType(String value) {
      this.value = value;
    }

    public String toString() {
      return this.value;
    }
  }

  public static BiMap<ApiType, TableType> API_TYPE_TO_TABLE_TYPE_MAP = HashBiMap.create();

  static {
    API_TYPE_TO_TABLE_TYPE_MAP.put(ApiType.YSQL, TableType.PGSQL_TABLE_TYPE);
    API_TYPE_TO_TABLE_TYPE_MAP.put(ApiType.YCQL, TableType.YQL_TABLE_TYPE);
  }

  public static String getKeyspaceName(ApiType apiType, String keyspaceName) {
    return apiType.toString().toLowerCase() + "." + keyspaceName;
  }

  public static boolean allSnapshotsSuccessful(List<SnapshotInfo> snapshotInfoList) {
    return !snapshotInfoList.stream().anyMatch(info -> info.getState().equals(State.FAILED));
  }

  public static Metric buildMetricTemplate(
      PlatformMetrics metric, Universe universe, PitrConfig pitrConfig, double value) {
    return MetricService.buildMetricTemplate(
            metric, universe, MetricService.DEFAULT_METRIC_EXPIRY_SEC)
        .setKeyLabel(KnownAlertLabels.PITR_CONFIG_UUID, pitrConfig.getUuid().toString())
        .setLabel(KnownAlertLabels.TABLE_TYPE.labelName(), pitrConfig.getTableType().toString())
        .setLabel(KnownAlertLabels.NAMESPACE_NAME.labelName(), pitrConfig.getDbName())
        .setValue(value);
  }

  /**
   * Use for cases apart from customer configs. Currently used in backup listing API, and fetching
   * list of locations for backup deletion.
   */
  public static class RegionLocations {
    public String REGION;
    public String LOCATION;
    public String HOST_BASE;
  }

  public static void validateBackupCronExpression(String cronExpression)
      throws PlatformServiceException {
    if (getCronExpressionTimeInterval(cronExpression)
        < BackupUtil.MIN_SCHEDULE_DURATION_IN_MILLIS) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Duration between the cron schedules cannot be less than 1 hour");
    }
  }

  public static long getCronExpressionTimeInterval(String cronExpression) {
    Cron parsedUnixCronExpression;
    try {
      CronParser unixCronParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(UNIX));
      parsedUnixCronExpression = unixCronParser.parse(cronExpression);
      parsedUnixCronExpression.validate();
    } catch (Exception ex) {
      throw new PlatformServiceException(BAD_REQUEST, "Cron expression specified is invalid");
    }
    ExecutionTime executionTime = ExecutionTime.forCron(parsedUnixCronExpression);
    Duration timeToNextExecution =
        executionTime.timeToNextExecution(Instant.now().atZone(ZoneId.of("UTC"))).get();
    Duration timeFromLastExecution =
        executionTime.timeFromLastExecution(Instant.now().atZone(ZoneId.of("UTC"))).get();
    Duration duration = Duration.ZERO;
    duration = duration.plus(timeToNextExecution).plus(timeFromLastExecution);
    return duration.getSeconds() * 1000;
  }

  public static void validateBackupFrequency(long frequency) throws PlatformServiceException {
    if (frequency < MIN_SCHEDULE_DURATION_IN_MILLIS) {
      throw new PlatformServiceException(BAD_REQUEST, "Minimum schedule duration is 1 hour");
    }
  }

  public void validateIncrementalScheduleFrequency(
      long frequency, long fullBackupFrequency, Universe universe) {
    long minimumIncrementalBackupScheduleFrequency =
        max(
            confGetter.getConfForScope(
                    universe, UniverseConfKeys.minIncrementalScheduleFrequencyInSecs)
                * 1000L,
            Util.YB_SCHEDULER_INTERVAL * 60 * 1000L);
    if (frequency < minimumIncrementalBackupScheduleFrequency) {
      throw new PlatformServiceException(
          BAD_REQUEST,
          "Minimum incremental backup schedule duration is "
              + minimumIncrementalBackupScheduleFrequency
              + " milliseconds");
    }
    if (frequency >= fullBackupFrequency) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Incremental backup frequency should be lower than full backup frequency.");
    }
  }

  public static List<BackupStorageInfo> sortBackupStorageInfo(
      List<BackupStorageInfo> backupStorageInfoList) {
    backupStorageInfoList.sort(BackupUtil::compareBackupStorageInfo);
    return backupStorageInfoList;
  }

  public static int compareBackupStorageInfo(
      BackupStorageInfo backupStorageInfo1, BackupStorageInfo backupStorageInfo2) {
    return backupStorageInfo1.storageLocation.compareTo(backupStorageInfo2.storageLocation);
  }

  public static RestoreBackupParams createRestoreKeyParams(
      RestoreBackupParams restoreBackupParams, BackupStorageInfo backupStorageInfo) {
    if (KmsConfig.get(restoreBackupParams.kmsConfigUUID) != null) {
      RestoreBackupParams restoreKeyParams =
          new RestoreBackupParams(
              restoreBackupParams, backupStorageInfo, RestoreBackupParams.ActionType.RESTORE_KEYS);
      return restoreKeyParams;
    }
    return null;
  }

  public static long extractBackupSize(JsonNode backupResponse) {
    long backupSize = 0L;
    JsonNode backupSizeJsonNode = backupResponse.get(BACKUP_SIZE_FIELD);
    if (backupSizeJsonNode != null && !backupSizeJsonNode.isNull()) {
      backupSize = Long.parseLong(backupSizeJsonNode.asText());
    } else {
      LOG.error(BACKUP_SIZE_FIELD + " not present in " + backupResponse.toString());
    }
    return backupSize;
  }

  public static BackupResp toBackupResp(Backup backup) {

    Boolean isStorageConfigPresent = checkIfStorageConfigExists(backup);
    Boolean isUniversePresent = checkIfUniverseExists(backup);
    List<Backup> backupChain =
        Backup.fetchAllBackupsByBaseBackupUUID(
            backup.getCustomerUUID(), backup.getBaseBackupUUID());
    Date lastIncrementDate = null;
    boolean hasIncrements = false;
    BackupState lastBackupState = BackupState.Completed;
    if (CollectionUtils.isNotEmpty(backupChain)) {
      lastIncrementDate = backupChain.get(0).getCreateTime();
      lastBackupState = backupChain.get(0).getState();
      hasIncrements = backupChain.size() > 1;
    }
    Boolean onDemand = (backup.getScheduleUUID() == null);
    BackupRespBuilder builder =
        BackupResp.builder()
            .expiryTime(backup.getExpiry())
            .expiryTimeUnit(backup.getExpiryTimeUnit())
            .onDemand(onDemand)
            .isFullBackup(backup.getBackupInfo().isFullBackup)
            .universeName(backup.getUniverseName())
            .scheduleUUID(backup.getScheduleUUID())
            .customerUUID(backup.getCustomerUUID())
            .universeUUID(backup.getUniverseUUID())
            .category(backup.getCategory())
            .isStorageConfigPresent(isStorageConfigPresent)
            .isUniversePresent(isUniversePresent)
            .backupType(backup.getBackupInfo().backupType)
            .storageConfigType(backup.getBackupInfo().storageConfigType)
            .fullChainSizeInBytes(backup.getBackupInfo().fullChainSizeInBytes)
            .commonBackupInfo(getCommonBackupInfo(backup))
            .hasIncrementalBackups(hasIncrements)
            .lastIncrementalBackupTime(lastIncrementDate)
            .lastBackupState(lastBackupState)
            .scheduleName(backup.getScheduleName());
    return builder.build();
  }

  public static CommonBackupInfo getCommonBackupInfo(Backup backup) {
    CommonBackupInfoBuilder builder = CommonBackupInfo.builder();
    builder
        .createTime(backup.getCreateTime())
        .updateTime(backup.getUpdateTime())
        .completionTime(backup.getCompletionTime())
        .backupUUID(backup.getBackupUUID())
        .baseBackupUUID(backup.getBaseBackupUUID())
        .totalBackupSizeInBytes(Long.valueOf(backup.getBackupInfo().backupSizeInBytes))
        .state(backup.getState())
        .kmsConfigUUID(backup.getBackupInfo().kmsConfigUUID)
        .storageConfigUUID(backup.getStorageConfigUUID())
        .taskUUID(backup.getTaskUUID())
        .sse(backup.getBackupInfo().sse);
    if (backup.getBackupInfo().backupList == null) {
      KeyspaceTablesList kTList =
          KeyspaceTablesList.builder()
              .keyspace(backup.getBackupInfo().getKeyspace())
              .tablesList(backup.getBackupInfo().getTableNames())
              .backupSizeInBytes(Long.valueOf(backup.getBackupInfo().backupSizeInBytes))
              .defaultLocation(backup.getBackupInfo().storageLocation)
              .perRegionLocations(backup.getBackupInfo().regionLocations)
              .build();
      builder.responseList(Stream.of(kTList).collect(Collectors.toSet()));
    } else {
      Set<KeyspaceTablesList> kTLists =
          backup.getBackupInfo().backupList.stream()
              .map(
                  b -> {
                    return KeyspaceTablesList.builder()
                        .keyspace(b.getKeyspace())
                        .allTables(b.allTables)
                        .tablesList(b.getTableNames())
                        .backupSizeInBytes(b.backupSizeInBytes)
                        .defaultLocation(b.storageLocation)
                        .perRegionLocations(b.regionLocations)
                        .build();
                  })
              .collect(Collectors.toSet());
      builder.responseList(kTLists);
    }
    return builder.build();
  }

  public List<CommonBackupInfo> getIncrementalBackupList(UUID baseBackupUUID, UUID customerUUID) {
    List<Backup> backupChain = Backup.fetchAllBackupsByBaseBackupUUID(customerUUID, baseBackupUUID);
    if (CollectionUtils.isEmpty(backupChain)) {
      return new ArrayList<>();
    }
    return backupChain.stream().map(BackupUtil::getCommonBackupInfo).collect(Collectors.toList());
  }

  // For creating new backup we would set the storage location based on
  // universe UUID and backup UUID.
  // univ-<univ_uuid>/backup-<timestamp>-<something_to_disambiguate_from_yugaware>/table-keyspace
  // .table_name.table_uuid
  public static String formatStorageLocation(BackupTableParams params, boolean isYbc) {
    SimpleDateFormat tsFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    String updatedLocation;
    String backupLabel = isYbc ? YBC_BACKUP_IDENTIFIER : "backup";
    if (params.tableUUIDList != null) {
      updatedLocation =
          String.format(
              "univ-%s/%s-%s-%d/multi-table-%s",
              params.getUniverseUUID(),
              backupLabel,
              tsFormat.format(new Date()),
              abs(params.backupUuid.hashCode()),
              params.getKeyspace());
    } else if (params.getTableName() == null && params.getKeyspace() != null) {
      updatedLocation =
          String.format(
              "univ-%s/%s-%s-%d/keyspace-%s",
              params.getUniverseUUID(),
              backupLabel,
              tsFormat.format(new Date()),
              abs(params.backupUuid.hashCode()),
              params.getKeyspace());
    } else {
      updatedLocation =
          String.format(
              "univ-%s/%s-%s-%d/table-%s.%s",
              params.getUniverseUUID(),
              backupLabel,
              tsFormat.format(new Date()),
              abs(params.backupUuid.hashCode()),
              params.getKeyspace(),
              params.getTableName());
      if (params.tableUUID != null) {
        updatedLocation =
            String.format("%s-%s", updatedLocation, params.tableUUID.toString().replace("-", ""));
      }
    }
    return updatedLocation;
  }

  public static List<RegionLocations> extractPerRegionLocationsFromBackupScriptResponse(
      JsonNode backupScriptResponse) {
    ObjectMapper objectMapper = new ObjectMapper();
    List<RegionLocations> regionLocations = new ArrayList<>();
    Map<String, Object> locations =
        objectMapper.convertValue(
            backupScriptResponse, new TypeReference<Map<String, Object>>() {});
    for (Entry<String, Object> entry : locations.entrySet()) {
      if (!(entry.getKey().equals(SNAPSHOT_URL_FIELD)
          || entry.getKey().equals(BACKUP_SIZE_FIELD))) {
        String r = entry.getKey();
        String l = entry.getValue().toString();
        RegionLocations regionLocation = new RegionLocations();
        regionLocation.REGION = r;
        regionLocation.LOCATION = l;
        regionLocations.add(regionLocation);
      }
    }
    return regionLocations;
  }

  public static void updateDefaultStorageLocation(
      BackupTableParams params, UUID customerUUID, BackupCategory category) {
    CustomerConfig customerConfig = CustomerConfig.get(customerUUID, params.storageConfigUUID);
    boolean isYbc = category.equals(BackupCategory.YB_CONTROLLER);
    params.storageLocation = formatStorageLocation(params, isYbc);
    if (customerConfig != null) {
      String backupLocation = null;
      if (customerConfig.getName().equals(Util.NFS)) {
        CustomerConfigStorageNFSData configData =
            (CustomerConfigStorageNFSData) customerConfig.getDataObject();
        backupLocation = configData.backupLocation;
        if (category.equals(BackupCategory.YB_CONTROLLER))
          // We allow / as nfs location, so add the check here.
          backupLocation = getCloudpathWithConfigSuffix(backupLocation, configData.nfsBucket);
      } else {
        CustomerConfigStorageData configData =
            (CustomerConfigStorageData) customerConfig.getDataObject();
        backupLocation = configData.backupLocation;
      }
      if (StringUtils.isNotBlank(backupLocation)) {
        params.storageLocation =
            getCloudpathWithConfigSuffix(backupLocation, params.storageLocation);
      }
    }
  }

  public void validateBackupStorageConfig(Backup backup) {
    CustomerConfig config =
        customerConfigService.getOrBadRequest(
            backup.getCustomerUUID(), backup.getStorageConfigUUID());
    validateStorageConfigOnBackup(config, backup);
  }

  public void validateStorageConfigOnBackup(CustomerConfig config, Backup backup) {
    StorageUtil storageUtil = StorageUtil.getStorageUtil(config.getName());
    BackupTableParams params = backup.getBackupInfo();
    if (CollectionUtils.isNotEmpty(params.backupList)) {
      for (BackupTableParams tableParams : params.backupList) {
        Map<String, String> keyspaceLocationMap = getKeyspaceLocationMap(tableParams);
        storageUtil.validateStorageConfigOnLocations(config.getDataObject(), keyspaceLocationMap);
      }
    } else {
      Map<String, String> keyspaceLocationMap = getKeyspaceLocationMap(params);
      storageUtil.validateStorageConfigOnLocations(config.getDataObject(), keyspaceLocationMap);
    }
  }

  public void validateStorageConfig(CustomerConfig config) throws PlatformServiceException {
    LOG.info(String.format("Validating storage config %s", config.getConfigName()));
    CustomerConfigStorageData configData = (CustomerConfigStorageData) config.getDataObject();
    if (StringUtils.isBlank(configData.backupLocation)) {
      throw new PlatformServiceException(BAD_REQUEST, "Default backup location cannot be empty");
    }
    StorageUtil.getStorageUtil(config.getName()).validateStorageConfigOnLocations(configData);
  }

  /**
   * Get exact storage location for regional locations, while persisting in backup object
   *
   * @param backupLocation The default location of the backup, containing the md/success file
   * @param configRegionLocation The regional location from the config
   * @return
   */
  public static String getExactRegionLocation(String backupLocation, String configRegionLocation) {
    return getExactRegionLocation(backupLocation, configRegionLocation, "");
  }

  public static String getExactRegionLocation(
      String backupLocation, String configRegionLocation, String nfsBucket) {
    return getCloudpathWithConfigSuffix(
        configRegionLocation, getBackupIdentifier(backupLocation, false, nfsBucket));
  }

  /**
   * Check whether backup is taken via YB-Controller.
   *
   * @param backupLocation
   * @return
   */
  public boolean isYbcBackup(String backupLocation) {
    return PATTERN_FOR_YBC_BACKUP_LOCATION.matcher(backupLocation).find();
  }

  /**
   * Returns the univ-<>/backup-<>-<>/some_value extracted from the default backup location or <NFS
   * bucket>/univ-<>/backup-<>-<>/some_value if YBC NFS backup and checkYbcNfs is false. If
   * checkYbcNfs is set to true, it additionally checks for NFS bucket in the location and removes
   * it.
   *
   * @param checkYbcNfs Remove default nfs bucket name if true
   * @param defaultBackupLocation The default location of the backup, containing the md/success file
   */
  public static String getBackupIdentifier(String defaultBackupLocation, boolean checkYbcNfs) {
    return getBackupIdentifier(defaultBackupLocation, checkYbcNfs, "");
  }

  public static String getBackupIdentifier(
      String defaultBackupLocation, boolean checkYbcNfs, String nfsBucket) {
    String pattern =
        String.format(
            BACKUP_IDENTIFIER_STRING, StringUtils.isEmpty(nfsBucket) ? "" : nfsBucket + "/");
    // Group 1: config prefix
    // Group 2: NFS bucket
    // Group 3: univ-<uuid>/
    // Group 4: suffix after universe
    // Group 5: ybc_ identifier
    Matcher m = Pattern.compile(pattern).matcher(defaultBackupLocation);
    m.matches();
    String backupIdentifier = m.group(3).concat(m.group(4));
    if (checkYbcNfs || StringUtils.isBlank(m.group(2)) || StringUtils.isBlank(m.group(5))) {
      return backupIdentifier;
    }
    // If NFS backup and checkYbcNfs disabled append
    return StringUtils.startsWith(m.group(1), "/")
        ? m.group(2).concat(backupIdentifier)
        : backupIdentifier;
  }

  public void validateRestoreOverwrites(
      List<BackupStorageInfo> backupStorageInfos, Universe universe, Backup.BackupCategory category)
      throws PlatformServiceException {
    List<TableInfo> tableInfoList = getTableInfosOrEmpty(universe);
    for (BackupStorageInfo backupInfo : backupStorageInfos) {
      if (!backupInfo.backupType.equals(TableType.REDIS_TABLE_TYPE)) {
        if (backupInfo.backupType.equals(TableType.YQL_TABLE_TYPE)
            && CollectionUtils.isNotEmpty(backupInfo.tableNameList)) {
          List<TableInfo> tableInfos =
              tableInfoList
                  .parallelStream()
                  .filter(tableInfo -> backupInfo.backupType.equals(tableInfo.getTableType()))
                  .filter(
                      tableInfo -> backupInfo.keyspace.equals(tableInfo.getNamespace().getName()))
                  .filter(tableInfo -> backupInfo.tableNameList.contains(tableInfo.getName()))
                  .collect(Collectors.toList());
          if (CollectionUtils.isNotEmpty(tableInfos)) {
            throw new PlatformServiceException(
                BAD_REQUEST,
                String.format(
                    "Keyspace %s contains tables with same names, overwriting data is not allowed",
                    backupInfo.keyspace));
          }
        } else if (backupInfo.backupType.equals(TableType.PGSQL_TABLE_TYPE)) {
          List<TableInfo> tableInfos =
              tableInfoList
                  .parallelStream()
                  .filter(tableInfo -> backupInfo.backupType.equals(tableInfo.getTableType()))
                  .filter(
                      tableInfo -> backupInfo.keyspace.equals(tableInfo.getNamespace().getName()))
                  .collect(Collectors.toList());
          if (CollectionUtils.isNotEmpty(tableInfos)) {
            throw new PlatformServiceException(
                BAD_REQUEST,
                String.format(
                    "Keyspace %s already exists, overwriting data is not allowed",
                    backupInfo.keyspace));
          }
        }
      }
    }
  }

  // Throws BAD_REQUEST for cases where same keyspace present twice in backup request.
  // If needed to support this, we'll make a fix and remove this check.
  public void validateKeyspaces(List<KeyspaceTable> keyspaceTableList) {
    if (keyspaceTableList.stream().map(kT -> kT.keyspace).collect(Collectors.toSet()).size()
        < keyspaceTableList.size()) {
      throw new PlatformServiceException(BAD_REQUEST, "Repeated keyspace backup requested");
    }
  }

  public void validateTables(
      List<UUID> tableUuids, Universe universe, String keyspace, TableType tableType)
      throws PlatformServiceException {

    List<TableInfo> tableInfoList = getTableInfosOrEmpty(universe);
    if (keyspace != null && tableUuids.isEmpty()) {
      tableInfoList =
          tableInfoList
              .parallelStream()
              .filter(tableInfo -> keyspace.equals(tableInfo.getNamespace().getName()))
              .filter(tableInfo -> tableType.equals(tableInfo.getTableType()))
              .collect(Collectors.toList());
      if (tableInfoList.isEmpty()) {
        throw new PlatformServiceException(
            BAD_REQUEST, "Cannot initiate backup with empty Keyspace " + keyspace);
      }
      return;
    }

    if (keyspace == null) {
      tableInfoList =
          tableInfoList
              .parallelStream()
              .filter(tableInfo -> tableType.equals(tableInfo.getTableType()))
              .collect(Collectors.toList());
      if (tableInfoList.isEmpty()) {
        throw new PlatformServiceException(
            BAD_REQUEST,
            "No tables to backup inside specified Universe "
                + universe.getUniverseUUID().toString()
                + " and Table Type "
                + tableType.name());
      }
      return;
    }

    // Match if the table is an index or ysql table.
    for (TableInfo tableInfo : tableInfoList) {
      if (tableUuids.contains(
          getUUIDRepresentation(tableInfo.getId().toStringUtf8().replace("-", "")))) {
        if (tableInfo.hasRelationType()
            && tableInfo.getRelationType() == RelationType.INDEX_TABLE_RELATION) {
          throw new PlatformServiceException(
              BAD_REQUEST, "Cannot backup index table " + tableInfo.getName());
        } else if (tableInfo.hasTableType()
            && tableInfo.getTableType() == TableType.PGSQL_TABLE_TYPE) {
          throw new PlatformServiceException(
              BAD_REQUEST, "Cannot backup ysql table " + tableInfo.getName());
        }
      }
    }
  }

  public List<TableInfo> getTableInfosOrEmpty(Universe universe) throws PlatformServiceException {
    final String masterAddresses = universe.getMasterAddresses(true);
    if (masterAddresses.isEmpty()) {
      throw new PlatformServiceException(
          INTERNAL_SERVER_ERROR, "Masters are not currently queryable.");
    }
    YBClient client = null;
    try {
      String certificate = universe.getCertificateNodetoNode();
      client = ybService.getClient(masterAddresses, certificate);
      return client.getTablesList().getTableInfoList();
    } catch (Exception e) {
      LOG.warn(e.toString());
      return Collections.emptyList();
    } finally {
      ybService.closeClient(client, masterAddresses);
    }
  }

  /**
   * Returns a list of locations in the backup.
   *
   * @param backup The backup to get all locations of.
   * @return List of locations( defaul and regional) for the backup.
   */
  public List<String> getBackupLocations(Backup backup) {
    BackupTableParams backupParams = backup.getBackupInfo();
    List<String> backupLocations = new ArrayList<>();
    Map<String, String> keyspaceLocations = new HashMap<>();
    if (backupParams.backupList != null) {
      for (BackupTableParams params : backupParams.backupList) {
        keyspaceLocations = getKeyspaceLocationMap(params);
        keyspaceLocations.values().forEach(l -> backupLocations.add(l));
      }
    } else {
      keyspaceLocations = getKeyspaceLocationMap(backupParams);
      keyspaceLocations.values().forEach(l -> backupLocations.add(l));
    }
    return backupLocations;
  }

  /**
   * Get exact cloud path after merging storage config suffix and backup identifier.
   *
   * @param storageConfigSuffix Storage config suffix to merge with common cloud directory
   * @param commonDir Backup identifier
   */
  public static String getCloudpathWithConfigSuffix(String storageConfigSuffix, String commonDir) {
    return String.format(
        ((StringUtils.isBlank(storageConfigSuffix) || storageConfigSuffix.endsWith("/"))
            ? "%s%s"
            : "%s/%s"),
        storageConfigSuffix,
        commonDir);
  }

  public static String appendSlash(String l) {
    return l.concat(l.endsWith("/") ? "" : "/");
  }

  /**
   * Return the region-location mapping of a given keyspace/table backup. The default location is
   * mapped to "default_region" as key in map.
   *
   * @param tableParams
   * @return The mapping
   */
  public Map<String, String> getKeyspaceLocationMap(BackupTableParams tableParams) {
    Map<String, String> keyspaceRegionLocations = new HashMap<>();
    if (tableParams != null) {
      keyspaceRegionLocations.put(YbcBackupUtil.DEFAULT_REGION_STRING, tableParams.storageLocation);
      if (CollectionUtils.isNotEmpty(tableParams.regionLocations)) {
        tableParams.regionLocations.forEach(
            rL -> {
              keyspaceRegionLocations.put(rL.REGION, rL.LOCATION);
            });
      }
    }
    return keyspaceRegionLocations;
  }

  public static String getKeyspaceFromStorageLocation(String storageLocation) {
    String[] splitArray = storageLocation.split("/");
    String keyspaceString = splitArray[(splitArray).length - 1];
    splitArray = keyspaceString.split("-");
    return splitArray[(splitArray).length - 1];
  }

  public static boolean checkInProgressIncrementalBackup(Backup backup) {
    return Backup.fetchAllBackupsByBaseBackupUUID(backup.getCustomerUUID(), backup.getBackupUUID())
        .stream()
        .anyMatch((b) -> (b.getState().equals(BackupState.InProgress)));
  }

  public static boolean checkIfStorageConfigExists(Backup backup) {
    return CustomerConfig.get(backup.getCustomerUUID(), backup.getStorageConfigUUID()) != null;
  }

  public static boolean checkIfUniverseExists(Backup backup) {
    return Universe.maybeGet(backup.getUniverseUUID()).isPresent();
  }

  public static boolean checkIfUniverseExists(UUID universeUUID) {
    return Universe.maybeGet(universeUUID).isPresent();
  }

  /**
   * Function to get total time taken for backups taken in parallel. Does a union of time intervals
   * based upon task-start time time taken, taking into considerations overlapping intervals.
   */
  public static long getTimeTakenForParallelBackups(List<BackupTableParams> backupList) {
    List<Pair<Long, Long>> sortedList =
        backupList.stream()
            .sorted(
                (tP1, tP2) -> {
                  long delta = tP1.thisBackupSubTaskStartTime - tP2.thisBackupSubTaskStartTime;
                  return delta >= 0 ? 1 : -1;
                })
            .map(
                bTP ->
                    new Pair<Long, Long>(
                        bTP.thisBackupSubTaskStartTime,
                        bTP.thisBackupSubTaskStartTime + bTP.timeTakenPartial))
            .collect(Collectors.toList());

    Stack<Pair<Long, Long>> mergeParallelTimes = new Stack<>();
    for (Pair<Long, Long> subTaskTime : sortedList) {
      if (mergeParallelTimes.empty()) {
        mergeParallelTimes.add(subTaskTime);
      } else {
        Pair<Long, Long> peek = mergeParallelTimes.peek();
        if (peek.getSecond() >= subTaskTime.getFirst()) {
          mergeParallelTimes.pop();

          mergeParallelTimes.add(
              new Pair<Long, Long>(
                  peek.getFirst(), max(peek.getSecond(), subTaskTime.getSecond())));
        } else {
          mergeParallelTimes.add(subTaskTime);
        }
      }
    }
    return mergeParallelTimes.stream().mapToLong(p -> p.getSecond() - p.getFirst()).sum();
  }
}
