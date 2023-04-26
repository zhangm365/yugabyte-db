// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.forms;

import static play.mvc.Http.Status.BAD_REQUEST;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.util.StdConverter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.yugabyte.yw.cloud.PublicCloudConstants;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.tasks.UniverseTaskBase;
import com.yugabyte.yw.commissioner.tasks.XClusterConfigTaskBase;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.gflags.GFlagsUtil;
import com.yugabyte.yw.common.gflags.SpecificGFlags;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.XClusterConfig;
import com.yugabyte.yw.models.helpers.ClusterAZ;
import com.yugabyte.yw.models.helpers.DeviceInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.TaskType;
import io.swagger.annotations.ApiModelProperty;
import io.swagger.annotations.ApiModelProperty.AccessMode;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.validation.constraints.Size;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import play.data.validation.Constraints;

/**
 * This class captures the user intent for creation of the universe. Note some nuances in the way
 * the intent is specified.
 *
 * <p>Single AZ deployments: Exactly one region should be specified in the 'regionList'.
 *
 * <p>Multi-AZ deployments: 1. There is at least one region specified which has a at least
 * 'replicationFactor' number of AZs.
 *
 * <p>2. There are multiple regions specified, and the sum total of all AZs in those regions is
 * greater than or equal to 'replicationFactor'. In this case, the preferred region can be specified
 * to hint which region needs to have a majority of the data copies if applicable, as well as
 * serving as the primary leader. Note that we do not currently support ability to place leaders in
 * a preferred region.
 *
 * <p>NOTE #1: The regions can potentially be present in different clouds.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(converter = UniverseDefinitionTaskParams.BaseConverter.class)
public class UniverseDefinitionTaskParams extends UniverseTaskParams {

  private static final Set<String> AWS_INSTANCE_WITH_EPHEMERAL_STORAGE_ONLY =
      ImmutableSet.of("i3.", "c5d.", "c6gd.");

  public static final String UPDATING_TASK_UUID_FIELD = "updatingTaskUUID";

  @Constraints.Required()
  @Size(min = 1)
  public List<Cluster> clusters = new LinkedList<>();

  // This is set during configure to figure out which cluster type is intended to be modified.
  @ApiModelProperty public ClusterType currentClusterType;

  public ClusterType getCurrentClusterType() {
    return currentClusterType == null ? ClusterType.PRIMARY : currentClusterType;
  }

  // This should be a globally unique name - it is a combination of the customer id and the universe
  // id. This is used as the prefix of node names in the universe.
  @ApiModelProperty public String nodePrefix = null;

  // Runtime flags to be set when creating the Universe
  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @ApiModelProperty
  public Map<String, String> runtimeFlags = null;

  // The UUID of the rootCA to be used to generate node certificates and facilitate TLS
  // communication between database nodes.
  @ApiModelProperty public UUID rootCA = null;

  // The UUID of the clientRootCA to be used to generate client certificates and facilitate TLS
  // communication between server and client.
  // This is made 'protected' to make sure there is no direct setting/getting
  @ApiModelProperty protected UUID clientRootCA = null;

  // This flag represents whether user has chosen to use same certificates for node to node and
  // client to server communication.
  // Default is set to true to ensure backward compatability
  @ApiModelProperty public boolean rootAndClientRootCASame = true;

  // This flag represents whether user has chosen to provide placement info
  // In Edit Universe if this flag is set we go through the NEW_CONFIG_FROM_PLACEMENT_INFO path
  @ApiModelProperty public boolean userAZSelected = false;

  // Set to true if resetting Universe form (in EDIT mode), false otherwise.
  @ApiModelProperty public boolean resetAZConfig = false;

  // TODO: Add a version number to prevent stale updates.
  // Set to true when an create/edit/destroy intent on the universe is started.
  @ApiModelProperty public boolean updateInProgress = false;

  // Type of task which set updateInProgress flag.
  @ApiModelProperty public TaskType updatingTask = null;

  // UUID of task which set updateInProgress flag.
  @ApiModelProperty public UUID updatingTaskUUID = null;

  // This tracks that if latest operation on this universe has successfully completed. This flag is
  // reset each time a new operation on the universe starts, and is set at the very end of that
  // operation.
  @ApiModelProperty public boolean updateSucceeded = true;

  // This tracks whether the universe is in the paused state or not.
  @ApiModelProperty public boolean universePaused = false;

  // The next cluster index to be used when a new read-only cluster is added.
  @ApiModelProperty public int nextClusterIndex = 1;

  // Flag to mark if the universe was created with insecure connections allowed.
  // Ideally should be false since we would never want to allow insecure connections,
  // but defaults to true since we want universes created through pre-TLS YW to be
  // unaffected.
  @ApiModelProperty public boolean allowInsecure = true;

  // Flag to check whether the txn_table_wait_ts_count gflag has to be set
  // while creating the universe or not. By default it should be false as we
  // should not set this flag for operations other than create universe.
  @ApiModelProperty public boolean setTxnTableWaitCountFlag = false;

  // Development flag to download package from s3 bucket.
  @ApiModelProperty public String itestS3PackagePath = "";

  @ApiModelProperty public String remotePackagePath = "";

  // EDIT mode: Set to true if nodes could be resized without full move.
  @ApiModelProperty public boolean nodesResizeAvailable = false;

  // This flag indicates whether the Kubernetes universe will use new
  // naming style of the Helm chart. The value cannot be changed once
  // set during universe creation. Default is set to false for
  // backward compatibility.
  @ApiModelProperty public boolean useNewHelmNamingStyle = false;

  // Place all masters into default region flag.
  @ApiModelProperty public boolean mastersInDefaultRegion = true;

  // true iff created through a k8s CR and controlled by the
  // Kubernetes Operator.
  @ApiModelProperty public boolean isKubernetesOperatorControlled = false;

  @ApiModelProperty public Map<ClusterAZ, String> existingLBs = null;

  // Override the default DB present in pre-built Ami
  @ApiModelProperty(hidden = true)
  public boolean overridePrebuiltAmiDBVersion = false;
  // if we want to use a different SSH_USER instead of  what is defined in the accessKey
  // Use imagebundle to overwrite the sshPort
  @Nullable @ApiModelProperty @Deprecated public String sshUserOverride;

  /** Allowed states for an imported universe. */
  public enum ImportedState {
    NONE, // Default, and for non-imported universes.
    STARTED,
    MASTERS_ADDED,
    TSERVERS_ADDED,
    IMPORTED
  }

  // State of the imported universe.
  @ApiModelProperty public ImportedState importedState = ImportedState.NONE;

  /** Type of operations that can be performed on the universe. */
  public enum Capability {
    READ_ONLY,
    EDITS_ALLOWED // Default, and for non-imported universes.
  }

  // Capability of the universe.
  @ApiModelProperty public Capability capability = Capability.EDITS_ALLOWED;

  /** Types of Clusters that can make up a universe. */
  public enum ClusterType {
    PRIMARY,
    ASYNC,
    ADDON
  }

  /** Allowed states for an exposing service of a universe */
  public enum ExposingServiceState {
    NONE, // Default, and means the universe was created before addition of the flag.
    EXPOSED,
    UNEXPOSED
  }

  /**
   * This are available update options when user clicks "Save" on EditUniverse page. UPDATE and
   * FULL_MOVE are handled by the same task (EditUniverse), the difference is that for FULL_MOVE ui
   * acts a little different. SMART_RESIZE_NON_RESTART - we don't need any confirmations for that as
   * it is non-restart. SMART_RESIZE - upgrade that handled by ResizeNode task GFLAGS_UPGRADE - for
   * the case of toggling "enable YSQ" and so on.
   */
  public enum UpdateOptions {
    UPDATE,
    FULL_MOVE,
    SMART_RESIZE_NON_RESTART,
    SMART_RESIZE,
    GFLAGS_UPGRADE
  }

  @ApiModelProperty public Set<UpdateOptions> updateOptions = new HashSet<>();

  /** A wrapper for all the clusters that will make up the universe. */
  @JsonInclude(value = JsonInclude.Include.NON_NULL)
  public static class Cluster {

    public UUID uuid = UUID.randomUUID();

    @ApiModelProperty(required = false)
    public void setUuid(UUID uuid) {
      this.uuid = uuid;
    }

    // The type of this cluster.
    @Constraints.Required() public final ClusterType clusterType;

    // The configuration for the universe the user intended.
    @Constraints.Required() public UserIntent userIntent;

    // The placement information computed from the user intent.
    @ApiModelProperty public PlacementInfo placementInfo = null;

    // The cluster index by which node names are sorted when shown in UI.
    // This is set internally by the placement util in the server, client should not set it.
    @ApiModelProperty public int index = 0;

    @ApiModelProperty(accessMode = AccessMode.READ_ONLY)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public List<Region> regions;

    /** Default to PRIMARY. */
    private Cluster() {
      this(ClusterType.PRIMARY, new UserIntent());
    }

    /**
     * @param clusterType One of [PRIMARY, ASYNC]
     * @param userIntent Customized UserIntent describing the desired state of this Cluster.
     */
    public Cluster(ClusterType clusterType, UserIntent userIntent) {
      assert clusterType != null && userIntent != null;
      this.clusterType = clusterType;
      this.userIntent = userIntent;
    }

    public boolean equals(Cluster other) {
      return uuid.equals(other.uuid);
    }

    /**
     * Check if instance tags are same as the passed in cluster.
     *
     * @param cluster another cluster to check against.
     * @return true if the tag maps are same for aws provider, false otherwise. This is because
     *     modify tags not implemented in devops for any cloud other than AWS.
     */
    public boolean areTagsSame(Cluster cluster) {
      if (cluster == null) {
        throw new IllegalArgumentException("Invalid cluster to compare.");
      }

      if (!cluster.userIntent.providerType.equals(userIntent.providerType)) {
        throw new IllegalArgumentException(
            "Mismatched provider types, expected "
                + userIntent.providerType.name()
                + " but got "
                + cluster.userIntent.providerType.name());
      }

      // Check if Provider supports instance tags and the instance tags match.
      if (!Provider.InstanceTagsModificationEnabledProviders.contains(userIntent.providerType)
          || userIntent.instanceTags.equals(cluster.userIntent.instanceTags)) {
        return true;
      }

      return false;
    }

    public void validate(boolean validateGFlagsConsistency, boolean isAuthEnforced) {
      if (uuid == null) {
        throw new IllegalStateException("Cluster uuid should not be null");
      }
      if (placementInfo == null) {
        throw new IllegalStateException("Placement should be provided");
      }
      checkDeviceInfo();
      checkStorageType();
      validateAuth(isAuthEnforced);
      if (validateGFlagsConsistency) {
        GFlagsUtil.checkGflagsAndIntentConsistency(userIntent);
      }
      if (userIntent.specificGFlags != null) {
        if (clusterType == ClusterType.PRIMARY
            && userIntent.specificGFlags.isInheritFromPrimary()) {
          throw new IllegalStateException("Cannot inherit gflags for primary cluster");
        }
        userIntent.specificGFlags.validateConsistency();
      }
    }

    /**
     * Validate to ensure that the user is not able to create a universe via API when they disable
     * YSQL Auth or YCQL Auth but yb.universe.auth.is_enforced runtime config value is true
     *
     * @param isAuthEnforced Runtime config value denoting if user is manadated to have auth.
     */
    private void validateAuth(boolean isAuthEnforced) {
      if (isAuthEnforced) {
        boolean enableYSQLAuth = userIntent.enableYSQLAuth;
        boolean enableYCQLAuth = userIntent.enableYCQLAuth;
        if ((userIntent.enableYSQL && !enableYSQLAuth)
            || (userIntent.enableYCQL && !enableYCQLAuth)) {
          throw new PlatformServiceException(
              BAD_REQUEST,
              "Global Policy mandates auth-enforced universes."
                  + "Make sure to enableAuth in request.");
        }
      }
    }

    private void checkDeviceInfo() {
      CloudType cloudType = userIntent.providerType;
      DeviceInfo deviceInfo = userIntent.deviceInfo;
      if (cloudType.isRequiresDeviceInfo()) {
        if (deviceInfo == null) {
          throw new PlatformServiceException(
              BAD_REQUEST, "deviceInfo can't be empty for universe on " + cloudType + " provider");
        }
        if (cloudType == CloudType.onprem && StringUtils.isEmpty(deviceInfo.mountPoints)) {
          throw new PlatformServiceException(
              BAD_REQUEST, "Mount points are mandatory for onprem cluster");
        }
        deviceInfo.validate();
      }
    }

    private void checkStorageType() {
      if (userIntent.deviceInfo == null) {
        return;
      }
      DeviceInfo deviceInfo = userIntent.deviceInfo;
      CloudType cloudType = userIntent.providerType;
      if (cloudType == CloudType.aws && hasEphemeralStorage(userIntent)) {
        // Ephemeral storage AWS instances should not have storage type
        if (deviceInfo.storageType != null) {
          throw new PlatformServiceException(
              BAD_REQUEST, "AWS instance with ephemeral storage can't have" + " storageType set");
        }
      } else {
        if (cloudType.isRequiresStorageType() && deviceInfo.storageType == null) {
          throw new PlatformServiceException(
              BAD_REQUEST, "storageType can't be empty for universe on " + cloudType + " provider");
        }
      }
      PublicCloudConstants.StorageType storageType = deviceInfo.storageType;
      if (storageType != null) {
        if (storageType.getCloudType() != cloudType) {
          throw new PlatformServiceException(
              BAD_REQUEST,
              "Cloud type "
                  + cloudType.name()
                  + " is not compatible with storage type "
                  + storageType.name());
        }
      }
    }
  }

  public static boolean hasEphemeralStorage(UserIntent userIntent) {
    boolean result =
        hasEphemeralStorage(
            userIntent.providerType, userIntent.instanceType, userIntent.deviceInfo);
    if (userIntent.dedicatedNodes) {
      result =
          result
              || hasEphemeralStorage(
                  userIntent.providerType,
                  userIntent.masterInstanceType,
                  userIntent.masterDeviceInfo);
    }
    return result;
  }

  public static boolean hasEphemeralStorage(
      CloudType providerType, String instanceType, DeviceInfo deviceInfo) {
    if (providerType == CloudType.aws && instanceType != null) {
      for (String prefix : AWS_INSTANCE_WITH_EPHEMERAL_STORAGE_ONLY) {
        if (instanceType.startsWith(prefix)) {
          return true;
        }
      }
      return false;
    } else if (providerType == CloudType.gcp) {
      if (deviceInfo != null
          && deviceInfo.storageType == PublicCloudConstants.StorageType.Scratch) {
        return true;
      }
    }
    return false;
  }

  /** The user defined intent for the universe. */
  public static class UserIntent {

    // Nice name for the universe.
    @Constraints.Required() @ApiModelProperty public String universeName;

    // TODO: https://github.com/yugabyte/yugabyte-db/issues/8190
    // The cloud provider UUID.
    @ApiModelProperty public String provider;

    // The cloud provider type as an enum. This is set in the middleware from the provider UUID
    // field above.
    @ApiModelProperty public CloudType providerType = CloudType.unknown;

    // The replication factor.
    @Constraints.Min(1)
    @ApiModelProperty
    public int replicationFactor = 3;

    // The list of regions that the user wants to place data replicas into.
    @ApiModelProperty public List<UUID> regionList;

    // The regions that the user wants to nominate as the preferred region. This makes sense only
    // for a multi-region setup.
    @ApiModelProperty public UUID preferredRegion;

    // Cloud Instance Type that the user wants for tserver nodes.
    @Constraints.Required() @ApiModelProperty public String instanceType;

    // The number of nodes to provision. These include ones for both masters and tservers.
    @Constraints.Min(1)
    @ApiModelProperty
    public int numNodes;

    // Universe level overrides for kubernetes universes.
    @ApiModelProperty public String universeOverrides;

    // AZ level overrides for kubernetes universes.
    @ApiModelProperty public Map<String, String> azOverrides;

    // The software version of YB to install.
    @Constraints.Required() @ApiModelProperty public String ybSoftwareVersion;

    @Constraints.Required() @ApiModelProperty public String accessKeyCode;

    @ApiModelProperty public DeviceInfo deviceInfo;

    @ApiModelProperty(notes = "default: true")
    public boolean assignPublicIP = true;

    @ApiModelProperty(value = "Whether to assign static public IP")
    public boolean assignStaticPublicIP = false;

    @ApiModelProperty(notes = "default: false")
    public boolean useSpotInstance = false;

    @ApiModelProperty(notes = "Max price we are willing to pay for spot instance")
    public Double spotPrice = 0.0;

    @ApiModelProperty() public boolean useTimeSync = false;

    @ApiModelProperty() public boolean enableYCQL = true;

    @ApiModelProperty() public String ysqlPassword;

    @ApiModelProperty() public String ycqlPassword;

    @ApiModelProperty() public Long kubernetesOperatorVersion;

    @ApiModelProperty() public boolean enableYSQLAuth = false;

    @ApiModelProperty() public boolean enableYCQLAuth = false;

    @ApiModelProperty() public boolean enableYSQL = true;

    @ApiModelProperty(notes = "default: true")
    public boolean enableYEDIS = true;

    @ApiModelProperty() public boolean enableNodeToNodeEncrypt = false;

    @ApiModelProperty() public boolean enableClientToNodeEncrypt = false;

    @ApiModelProperty() public boolean enableVolumeEncryption = false;

    @ApiModelProperty() public boolean enableIPV6 = false;

    @ApiModelProperty() public UUID imageBundleUUID;

    // Flag to use if we need to deploy a loadbalancer/some kind of
    // exposing service for the cluster.
    // Defaults to NONE since that was the behavior before.
    // NONE for k8s means it was enabled, NONE for VMs means disabled.
    // Can eventually be used when we create loadbalancer services for
    // our cluster deployments.
    // Setting at user intent level since it can be unique across types of clusters.
    @ApiModelProperty(notes = "default: NONE")
    public ExposingServiceState enableExposingService = ExposingServiceState.NONE;

    @ApiModelProperty public String awsArnString;

    @ApiModelProperty() public boolean enableLB = false;

    // When this is set to true, YW will setup the universe to communicate by way of hostnames
    // instead of ip addresses. These hostnames will have been provided during on-prem provider
    // setup and will be in-place of privateIP
    @Deprecated @ApiModelProperty() public boolean useHostname = false;

    @ApiModelProperty() public Boolean useSystemd = false;

    // Info of all the gflags that the user would like to save to the universe. These will be
    // used during edit universe, for example, to set the flags on new nodes to match
    // existing nodes' settings.
    @ApiModelProperty public Map<String, String> masterGFlags = new HashMap<>();
    @ApiModelProperty public Map<String, String> tserverGFlags = new HashMap<>();

    // Flags for YB-Controller.
    @ApiModelProperty public Map<String, String> ybcFlags = new HashMap<>();

    // Instance tags (used for AWS only).
    @ApiModelProperty public Map<String, String> instanceTags = new HashMap<>();

    // True if user wants to have dedicated nodes for master and tserver processes.
    @ApiModelProperty public boolean dedicatedNodes = false;

    // Instance type used for dedicated master nodes.
    @Nullable @ApiModelProperty public String masterInstanceType;

    // Device info for dedicated master nodes.
    @Nullable @ApiModelProperty public DeviceInfo masterDeviceInfo;

    // New version of gflags. If present - replaces old masterGFlags/tserverGFlags thing
    @ApiModelProperty public SpecificGFlags specificGFlags;

    @Override
    public String toString() {
      return "UserIntent "
          + "for universe="
          + universeName
          + " type="
          + instanceType
          + ", spotInstance="
          + useSpotInstance
          + ", spotPrice="
          + spotPrice
          + ", numNodes="
          + numNodes
          + ", prov="
          + provider
          + ", provType="
          + providerType
          + ", RF="
          + replicationFactor
          + ", regions="
          + regionList
          + ", pref="
          + preferredRegion
          + ", ybVersion="
          + ybSoftwareVersion
          + ", accessKey="
          + accessKeyCode
          + ", deviceInfo='"
          + deviceInfo
          + "', timeSync="
          + useTimeSync
          + ", publicIP="
          + assignPublicIP
          + ", staticPublicIP="
          + assignStaticPublicIP
          + ", tags="
          + instanceTags
          + ", masterInstanceType="
          + masterInstanceType
          + ", kubernetesOperatorVersion="
          + kubernetesOperatorVersion;
    }

    @Override
    public UserIntent clone() {
      UserIntent newUserIntent = new UserIntent();
      newUserIntent.universeName = universeName;
      newUserIntent.provider = provider;
      newUserIntent.providerType = providerType;
      newUserIntent.replicationFactor = replicationFactor;
      newUserIntent.regionList = new ArrayList<>(regionList);
      newUserIntent.preferredRegion = preferredRegion;
      newUserIntent.instanceType = instanceType;
      newUserIntent.numNodes = numNodes;
      newUserIntent.ybSoftwareVersion = ybSoftwareVersion;
      newUserIntent.useSystemd = useSystemd;
      newUserIntent.accessKeyCode = accessKeyCode;
      newUserIntent.assignPublicIP = assignPublicIP;
      newUserIntent.useSpotInstance = useSpotInstance;
      newUserIntent.spotPrice = spotPrice;
      newUserIntent.assignStaticPublicIP = assignStaticPublicIP;
      newUserIntent.specificGFlags = specificGFlags == null ? null : specificGFlags.clone();
      newUserIntent.masterGFlags = new HashMap<>(masterGFlags);
      newUserIntent.tserverGFlags = new HashMap<>(tserverGFlags);
      newUserIntent.useTimeSync = useTimeSync;
      newUserIntent.enableYSQL = enableYSQL;
      newUserIntent.enableYCQL = enableYCQL;
      newUserIntent.enableYSQLAuth = enableYSQLAuth;
      newUserIntent.enableYCQLAuth = enableYCQLAuth;
      newUserIntent.ysqlPassword = ysqlPassword;
      newUserIntent.ycqlPassword = ycqlPassword;
      newUserIntent.enableYEDIS = enableYEDIS;
      newUserIntent.enableNodeToNodeEncrypt = enableNodeToNodeEncrypt;
      newUserIntent.enableClientToNodeEncrypt = enableClientToNodeEncrypt;
      newUserIntent.instanceTags = new HashMap<>(instanceTags);
      newUserIntent.enableLB = enableLB;
      if (deviceInfo != null) {
        newUserIntent.deviceInfo = deviceInfo.clone();
      }
      newUserIntent.masterInstanceType = masterInstanceType;
      if (masterDeviceInfo != null) {
        newUserIntent.masterDeviceInfo = masterDeviceInfo.clone();
      }
      newUserIntent.dedicatedNodes = dedicatedNodes;
      return newUserIntent;
    }

    public String getInstanceTypeForNode(NodeDetails nodeDetails) {
      return getInstanceTypeForProcessType(nodeDetails.dedicatedTo);
    }

    public String getInstanceTypeForProcessType(@Nullable UniverseTaskBase.ServerType type) {
      if (type == UniverseTaskBase.ServerType.MASTER && masterInstanceType != null) {
        return masterInstanceType;
      }
      return instanceType;
    }

    public DeviceInfo getDeviceInfoForNode(NodeDetails nodeDetails) {
      return getDeviceInfoForProcessType(nodeDetails.dedicatedTo);
    }

    public DeviceInfo getDeviceInfoForProcessType(@Nullable UniverseTaskBase.ServerType type) {
      if (type == UniverseTaskBase.ServerType.MASTER && masterDeviceInfo != null) {
        return masterDeviceInfo;
      }
      return deviceInfo;
    }

    // NOTE: If new fields are checked, please add them to the toString() as well.
    public boolean equals(UserIntent other) {
      if (universeName.equals(other.universeName)
          && provider.equals(other.provider)
          && providerType == other.providerType
          && replicationFactor == other.replicationFactor
          && compareRegionLists(regionList, other.regionList)
          && Objects.equals(preferredRegion, other.preferredRegion)
          && instanceType.equals(other.instanceType)
          && numNodes == other.numNodes
          && ybSoftwareVersion.equals(other.ybSoftwareVersion)
          && (accessKeyCode == null || accessKeyCode.equals(other.accessKeyCode))
          && assignPublicIP == other.assignPublicIP
          && useSpotInstance == other.useSpotInstance
          && spotPrice.equals(other.spotPrice)
          && assignStaticPublicIP == other.assignStaticPublicIP
          && useTimeSync == other.useTimeSync
          && useSystemd == other.useSystemd
          && dedicatedNodes == other.dedicatedNodes
          && Objects.equals(masterInstanceType, other.masterInstanceType)) {
        return true;
      }
      return false;
    }

    public boolean onlyRegionsChanged(UserIntent other) {
      if (universeName.equals(other.universeName)
          && provider.equals(other.provider)
          && providerType == other.providerType
          && replicationFactor == other.replicationFactor
          && newRegionsAdded(regionList, other.regionList)
          && Objects.equals(preferredRegion, other.preferredRegion)
          && instanceType.equals(other.instanceType)
          && numNodes == other.numNodes
          && ybSoftwareVersion.equals(other.ybSoftwareVersion)
          && (accessKeyCode == null || accessKeyCode.equals(other.accessKeyCode))
          && assignPublicIP == other.assignPublicIP
          && useSpotInstance == other.useSpotInstance
          && spotPrice.equals(other.spotPrice)
          && assignStaticPublicIP == other.assignStaticPublicIP
          && useTimeSync == other.useTimeSync
          && dedicatedNodes == other.dedicatedNodes
          && Objects.equals(masterInstanceType, other.masterInstanceType)) {
        return true;
      }
      return false;
    }

    private static boolean newRegionsAdded(List<UUID> left, List<UUID> right) {
      return (new HashSet<>(left)).containsAll(new HashSet<>(right));
    }

    /**
     * Helper API to check if the set of regions is the same in two lists. Does not validate that
     * the UUIDs correspond to actual, existing Regions.
     *
     * @param left First list of Region UUIDs.
     * @param right Second list of Region UUIDs.
     * @return true if the unordered, unique set of UUIDs is the same in both lists, else false.
     */
    private static boolean compareRegionLists(List<UUID> left, List<UUID> right) {
      return (new HashSet<>(left)).equals(new HashSet<>(right));
    }

    @JsonIgnore
    public boolean isYSQLAuthEnabled() {
      return tserverGFlags.getOrDefault("ysql_enable_auth", "false").equals("true")
          || enableYSQLAuth;
    }
  }

  @JsonIgnore
  // Returns true if universe object is in any known import related state.
  public boolean isImportedUniverse() {
    return importedState != ImportedState.NONE;
  }

  @JsonIgnore
  // Returns true if universe is allowed edits or node-actions.
  public boolean isUniverseEditable() {
    return capability == Capability.EDITS_ALLOWED;
  }

  /**
   * Helper API to remove node from nodeDetailSet
   *
   * @param nodeName Name of a particular node to remove from the containing nodeDetailsSet.
   */
  public void removeNode(String nodeName) {
    if (nodeDetailsSet != null) {
      nodeDetailsSet.removeIf(node -> node.nodeName.equals(nodeName));
    }
  }

  /**
   * Add a primary cluster with the specified UserIntent and PlacementInfo to the list of clusters
   * if one does not already exist. Otherwise, update the existing primary cluster with the
   * specified UserIntent and PlacementInfo.
   *
   * @param userIntent UserIntent describing the primary cluster.
   * @param placementInfo PlacementInfo describing the placement of the primary cluster.
   * @return the updated/inserted primary cluster.
   */
  public Cluster upsertPrimaryCluster(UserIntent userIntent, PlacementInfo placementInfo) {
    Cluster primaryCluster = getPrimaryCluster();
    if (primaryCluster != null) {
      if (userIntent != null) {
        primaryCluster.userIntent = userIntent;
      }
      if (placementInfo != null) {
        primaryCluster.placementInfo = placementInfo;
      }
    } else {
      primaryCluster =
          new Cluster(ClusterType.PRIMARY, (userIntent == null) ? new UserIntent() : userIntent);
      primaryCluster.placementInfo = placementInfo;
      clusters.add(primaryCluster);
    }
    return primaryCluster;
  }

  /**
   * Add a cluster with the specified UserIntent, PlacementInfo, and uuid to the list of clusters if
   * one does not already exist. Otherwise, update the existing cluster with the specified
   * UserIntent and PlacementInfo.
   *
   * @param userIntent UserIntent describing the cluster.
   * @param placementInfo PlacementInfo describing the placement of the cluster.
   * @param clusterUuid uuid of the cluster we want to change.
   * @return the updated/inserted cluster.
   */
  public Cluster upsertCluster(
      UserIntent userIntent, PlacementInfo placementInfo, UUID clusterUuid) {
    return upsertCluster(userIntent, placementInfo, clusterUuid, ClusterType.ASYNC);
  }

  /**
   * Add a cluster with the specified UserIntent, PlacementInfo, and uuid to the list of clusters if
   * one does not already exist. Otherwise, update the existing cluster with the specified
   * UserIntent and PlacementInfo.
   *
   * @param userIntent UserIntent describing the cluster.
   * @param placementInfo PlacementInfo describing the placement of the cluster.
   * @param clusterUuid uuid of the cluster we want to change.
   * @param clusterType type of the cluster we want to change.
   * @return the updated/inserted cluster.
   */
  public Cluster upsertCluster(
      UserIntent userIntent,
      PlacementInfo placementInfo,
      UUID clusterUuid,
      ClusterType clusterType) {
    Cluster cluster = getClusterByUuid(clusterUuid);
    if (cluster != null) {
      if (userIntent != null) {
        cluster.userIntent = userIntent;
      }
      if (placementInfo != null) {
        cluster.placementInfo = placementInfo;
      }
    } else {
      cluster = new Cluster(clusterType, userIntent == null ? new UserIntent() : userIntent);
      cluster.placementInfo = placementInfo;
      clusters.add(cluster);
    }
    cluster.setUuid(clusterUuid);
    return cluster;
  }

  /**
   * Delete a cluster with the specified uuid from the list of clusters.
   *
   * @param clusterUuid the uuid of the cluster we are deleting.
   */
  public void deleteCluster(UUID clusterUuid) {
    Cluster cluster = getClusterByUuid(clusterUuid);
    if (cluster == null) {
      throw new IllegalArgumentException(
          "UUID " + clusterUuid + " not found in universe " + getUniverseUUID());
    }

    clusters.remove(cluster);
  }

  /**
   * Helper API to retrieve the Primary Cluster in the Universe represented by these Params.
   *
   * @return the Primary Cluster in the Universe represented by these Params or null if none exists.
   */
  @JsonIgnore
  public Cluster getPrimaryCluster() {
    List<Cluster> foundClusters =
        clusters.stream()
            .filter(c -> c.clusterType.equals(ClusterType.PRIMARY))
            .collect(Collectors.toList());
    if (foundClusters.size() > 1) {
      throw new RuntimeException(
          "Multiple primary clusters found in params for universe " + getUniverseUUID().toString());
    }
    return Iterables.getOnlyElement(foundClusters, null);
  }

  @JsonIgnore
  public Set<NodeDetails> getTServers() {
    Set<NodeDetails> Tservers = new HashSet<>();
    for (NodeDetails n : nodeDetailsSet) {
      if (n.isTserver) {
        Tservers.add(n);
      }
    }
    return Tservers;
  }

  /**
   * Helper API to retrieve all ReadOnly Clusters in the Universe represented by these Params.
   *
   * @return a list of all ReadOnly Clusters in the Universe represented by these Params.
   */
  @JsonIgnore
  public List<Cluster> getReadOnlyClusters() {
    return getClusterByType(ClusterType.ASYNC);
  }

  /**
   * Helper API to retrieve a Cluster in the Universe represented by these Params by its UUID.
   *
   * @return a list of all AddOns in the Universe represented by these Params.
   */
  @JsonIgnore
  public List<Cluster> getAddOnClusters() {
    return getClusterByType(ClusterType.ADDON);
  }

  @JsonIgnore
  public List<Cluster> getClusterByType(ClusterType clusterType) {
    return clusters.stream()
        .filter(c -> c.clusterType.equals(clusterType))
        .collect(Collectors.toList());
  }

  @JsonIgnore
  public List<Cluster> getNonPrimaryClusters() {
    return clusters.stream()
        .filter(c -> !c.clusterType.equals(ClusterType.PRIMARY))
        .collect(Collectors.toList());
  }

  /**
   * Helper API to retrieve the Cluster in the Universe corresponding to the given UUID.
   *
   * @param uuid UUID of the Cluster to retrieve.
   * @return The Cluster corresponding to the provided UUID or null if none exists.
   */
  @JsonIgnore
  public Cluster getClusterByUuid(UUID uuid) {
    if (uuid == null) {
      return getPrimaryCluster();
    }

    List<Cluster> foundClusters =
        clusters.stream().filter(c -> c.uuid.equals(uuid)).collect(Collectors.toList());

    if (foundClusters.size() > 1) {
      throw new RuntimeException(
          "Multiple clusters with uuid "
              + uuid.toString()
              + " found in params for universe "
              + getUniverseUUID().toString());
    }

    return Iterables.getOnlyElement(foundClusters, null);
  }

  // the getter has some logic built around, as there are no other layer to
  // have such logic at a common place
  public UUID getClientRootCA() {
    return (rootCA != null && rootAndClientRootCASame) ? rootCA : clientRootCA;
  }

  public void setClientRootCA(UUID clientRootCA) {
    this.clientRootCA = clientRootCA;
  }

  /**
   * Helper API to retrieve nodes that are in a specified cluster.
   *
   * @param uuid UUID of the cluster that we want nodes from.
   * @return A Set of NodeDetails that are in the specified cluster.
   */
  @JsonIgnore
  public Set<NodeDetails> getNodesInCluster(UUID uuid) {
    if (nodeDetailsSet == null) {
      return null;
    }
    return nodeDetailsSet.stream().filter(n -> n.isInPlacement(uuid)).collect(Collectors.toSet());
  }

  @JsonIgnore
  public void setExistingLBs(List<Cluster> clusters) {
    Map<ClusterAZ, String> existingLBsMap = new HashMap<>();
    for (Cluster cluster : clusters) {
      if (cluster.userIntent.enableLB) {
        // Get AZs in cluster
        List<PlacementInfo.PlacementAZ> azList =
            PlacementInfoUtil.getAZsSortedByNumNodes(cluster.placementInfo);
        for (PlacementInfo.PlacementAZ placementAZ : azList) {
          String lbName = placementAZ.lbName;
          AvailabilityZone az = AvailabilityZone.getOrBadRequest(placementAZ.uuid);
          if (!Strings.isNullOrEmpty(lbName)) {
            ClusterAZ clusterAZ = new ClusterAZ(cluster.uuid, az);
            existingLBsMap.computeIfAbsent(clusterAZ, v -> lbName);
          }
        }
      }
    }
    this.existingLBs = existingLBsMap;
  }

  public static class BaseConverter<T extends UniverseDefinitionTaskParams>
      extends StdConverter<T, T> {

    @Override
    public T convert(T taskParams) {
      // If there is universe level communication port set then push it down to node level
      if (taskParams.communicationPorts != null && taskParams.nodeDetailsSet != null) {
        taskParams.nodeDetailsSet.forEach(
            nodeDetails ->
                CommunicationPorts.setCommunicationPorts(
                    taskParams.communicationPorts, nodeDetails));
      }
      if (taskParams.expectedUniverseVersion == null) {
        taskParams.expectedUniverseVersion = -1;
      }
      return taskParams;
    }
  }

  // XCluster: All the xCluster related code resides in this section.
  // --------------------------------------------------------------------------------
  @ApiModelProperty("XCluster related states in this universe")
  @JsonProperty("xclusterInfo")
  public XClusterInfo xClusterInfo = new XClusterInfo();

  @JsonGetter("xclusterInfo")
  XClusterInfo getXClusterInfo() {
    this.xClusterInfo.universeUuid = this.getUniverseUUID();
    return this.xClusterInfo;
  }

  @JsonIgnoreProperties(
      value = {"sourceXClusterConfigs", "targetXClusterConfigs"},
      allowGetters = true)
  @ToString
  public static class XClusterInfo {

    @ApiModelProperty("The value of certs_for_cdc_dir gflag")
    public String sourceRootCertDirPath;

    @JsonIgnore private UUID universeUuid;

    @JsonIgnore
    public boolean isSourceRootCertDirPathGflagConfigured() {
      return StringUtils.isNotBlank(sourceRootCertDirPath);
    }

    @ApiModelProperty(value = "The target universe's xcluster replication relationships")
    @JsonProperty(value = "targetXClusterConfigs", access = JsonProperty.Access.READ_ONLY)
    public List<UUID> getTargetXClusterConfigs() {
      if (universeUuid == null) {
        return new ArrayList<>();
      }
      return XClusterConfig.getByTargetUniverseUUID(universeUuid).stream()
          .map(xClusterConfig -> xClusterConfig.getUuid())
          .collect(Collectors.toList());
    }

    @ApiModelProperty(value = "The source universe's xcluster replication relationships")
    @JsonProperty(value = "sourceXClusterConfigs", access = JsonProperty.Access.READ_ONLY)
    public List<UUID> getSourceXClusterConfigs() {
      if (universeUuid == null) {
        return Collections.emptyList();
      }
      return XClusterConfig.getBySourceUniverseUUID(universeUuid).stream()
          .map(xClusterConfig -> xClusterConfig.getUuid())
          .collect(Collectors.toList());
    }
  }

  /**
   * It returns the path to the directory containing the source universe root certificates on the
   * target universe. It must be called with target universe as context.
   *
   * @return The path to the directory containing the source universe root certificates
   */
  @JsonIgnore
  public File getSourceRootCertDirPath() {
    Map<String, String> masterGflags =
        GFlagsUtil.getBaseGFlags(UniverseTaskBase.ServerType.MASTER, getPrimaryCluster(), clusters);
    Map<String, String> tserverGflags =
        GFlagsUtil.getBaseGFlags(
            UniverseTaskBase.ServerType.TSERVER, getPrimaryCluster(), clusters);
    String gflagValueOnMasters =
        masterGflags.get(XClusterConfigTaskBase.SOURCE_ROOT_CERTS_DIR_GFLAG);
    String gflagValueOnTServers =
        tserverGflags.get(XClusterConfigTaskBase.SOURCE_ROOT_CERTS_DIR_GFLAG);
    if (gflagValueOnMasters != null || gflagValueOnTServers != null) {
      if (!Objects.equals(gflagValueOnMasters, gflagValueOnTServers)) {
        throw new IllegalStateException(
            String.format(
                "%s gflag is different on masters (%s) and tservers (%s)",
                XClusterConfigTaskBase.SOURCE_ROOT_CERTS_DIR_GFLAG,
                gflagValueOnMasters,
                gflagValueOnTServers));
      }
      return new File(gflagValueOnMasters);
    }
    if (xClusterInfo.isSourceRootCertDirPathGflagConfigured()) {
      return new File(xClusterInfo.sourceRootCertDirPath);
    }
    return null;
  }

  @JsonIgnore
  public boolean isUniverseBusyByTask() {
    return updateInProgress
        && updatingTask != TaskType.BackupTable
        && updatingTask != TaskType.MultiTableBackup
        && updatingTask != TaskType.CreateBackup
        && updatingTask != TaskType.RestoreBackup;
  }
  // --------------------------------------------------------------------------------
  // End of XCluster.
}
