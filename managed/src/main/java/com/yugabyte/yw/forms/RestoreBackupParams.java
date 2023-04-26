package com.yugabyte.yw.forms;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yugabyte.yw.models.Backup.BackupCategory;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.yb.CommonTypes.TableType;
import play.data.validation.Constraints;

@NoArgsConstructor
public class RestoreBackupParams extends UniverseTaskParams {

  public enum ActionType {
    RESTORE,
    RESTORE_KEYS
  }

  @Constraints.Required
  @ApiModelProperty(value = "Customer UUID")
  public UUID customerUUID;

  @Constraints.Required
  @ApiModelProperty(value = "Universe UUID", required = true)
  @Getter
  @Setter
  private UUID universeUUID;

  @ApiModelProperty(value = "KMS configuration UUID")
  public UUID kmsConfigUUID = null;

  @ApiModelProperty(value = "Action type")
  public ActionType actionType;

  @ApiModelProperty(value = "Category of the backup")
  public BackupCategory category = BackupCategory.YB_BACKUP_SCRIPT;

  @ApiModelProperty(value = "Backup's storage info to restore")
  public List<BackupStorageInfo> backupStorageInfoList;

  // Intermediate states to resume ybc backups
  public UUID prefixUUID;

  public int currentIdx;

  public String currentYbcTaskId;

  public String nodeIp;

  // Should backup script enable verbose logging.
  @ApiModelProperty(value = "Is verbose logging enabled")
  public boolean enableVerboseLogs = false;

  @ApiModelProperty(value = "Storage config uuid")
  public UUID storageConfigUUID;

  @ApiModelProperty(value = "Alter load balancer state")
  public boolean alterLoadBalancer = true;

  @ApiModelProperty(value = "Disable checksum")
  public Boolean disableChecksum = false;

  @ApiModelProperty(value = "Is tablespaces information included")
  public Boolean useTablespaces = false;

  @ApiModelProperty(value = "Disable multipart upload")
  public boolean disableMultipart = false;

  // The number of concurrent commands to run on nodes over SSH
  @ApiModelProperty(value = "Number of concurrent commands to run on nodes over SSH")
  public int parallelism = 8;

  @ApiModelProperty(value = "Restore TimeStamp")
  public String restoreTimeStamp = null;

  @ApiModel(description = "Backup Storage Info for doing restore operation")
  public static class BackupStorageInfo {

    @ApiModelProperty(value = "Backup type")
    public TableType backupType;

    // Specifies the backup storage location. In case of S3 it would have
    // the S3 url based on universeUUID and timestamp.
    @ApiModelProperty(value = "Storage location")
    public String storageLocation;

    @ApiModelProperty(value = "Keyspace name")
    public String keyspace;

    @ApiModelProperty(value = "Tables")
    public List<String> tableNameList;

    @ApiModelProperty(value = "Is SSE")
    public boolean sse = false;

    @ApiModelProperty(value = "User name of the current tables owner")
    public String oldOwner = "postgres";

    @ApiModelProperty(value = "User name of the new tables owner")
    public String newOwner = null;
  }

  public RestoreBackupParams(
      RestoreBackupParams otherParams, BackupStorageInfo backupStorageInfo, ActionType actionType) {
    this.customerUUID = otherParams.customerUUID;
    this.setUniverseUUID(otherParams.getUniverseUUID());
    this.storageConfigUUID = otherParams.storageConfigUUID;
    this.restoreTimeStamp = otherParams.restoreTimeStamp;
    this.kmsConfigUUID = otherParams.kmsConfigUUID;
    this.parallelism = otherParams.parallelism;
    this.actionType = actionType;
    this.backupStorageInfoList = new ArrayList<>();
    this.backupStorageInfoList.add(backupStorageInfo);
    this.disableChecksum = otherParams.disableChecksum;
    this.useTablespaces = otherParams.useTablespaces;
    this.disableMultipart = otherParams.disableMultipart;
    this.enableVerboseLogs = otherParams.enableVerboseLogs;
    this.prefixUUID = otherParams.prefixUUID;
  }

  @JsonIgnore
  public RestoreBackupParams(RestoreBackupParams params) {
    // Don't need vebose, multipart, parallelism.
    // Since only using this for YBC restores.
    this.customerUUID = params.customerUUID;
    this.setUniverseUUID(params.getUniverseUUID());
    this.storageConfigUUID = params.storageConfigUUID;
    this.restoreTimeStamp = params.restoreTimeStamp;
    this.kmsConfigUUID = params.kmsConfigUUID;
    this.actionType = params.actionType;
    this.backupStorageInfoList = new ArrayList<>(params.backupStorageInfoList);
    this.disableChecksum = params.disableChecksum;
    this.useTablespaces = params.useTablespaces;
    this.prefixUUID = params.prefixUUID;
  }
}
