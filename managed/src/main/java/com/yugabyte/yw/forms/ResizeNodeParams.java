// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.forms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.typesafe.config.Config;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.common.gflags.GFlagsUtil;
import com.yugabyte.yw.common.inject.StaticInjectorHolder;
import com.yugabyte.yw.common.utils.Pair;
import com.yugabyte.yw.models.InstanceType;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.DeviceInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import play.mvc.Http.Status;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(converter = ResizeNodeParams.Converter.class)
@Data
@EqualsAndHashCode(callSuper = true)
@Slf4j
public class ResizeNodeParams extends UpgradeTaskParams {

  private static final Set<Common.CloudType> SUPPORTED_CLOUD_TYPES =
      EnumSet.of(Common.CloudType.gcp, Common.CloudType.aws, Common.CloudType.kubernetes);

  private boolean forceResizeNode;
  public Map<String, String> masterGFlags;
  public Map<String, String> tserverGFlags;

  @Override
  public boolean isKubernetesUpgradeSupported() {
    return true;
  }

  @Override
  public void verifyParams(Universe universe) {
    verifyParams(universe, null);
  }

  @Override
  public void verifyParams(Universe universe, NodeDetails.NodeState nodeState) {
    super.verifyParams(universe, nodeState); // we call verifyParams which will fail

    RuntimeConfigFactory runtimeConfigFactory =
        StaticInjectorHolder.injector().instanceOf(RuntimeConfigFactory.class);

    // Both master and tserver can be null. But if one is provided, both should be provided.
    if ((masterGFlags == null && tserverGFlags != null)
        || (tserverGFlags == null && masterGFlags != null)) {
      throw new PlatformServiceException(
          Status.BAD_REQUEST, "Either none or both master and tserver gflags are required");
    }
    if (masterGFlags != null) {
      // We want this flow to only be enabled for cloud in the first go.
      if (!runtimeConfigFactory.forUniverse(universe).getBoolean("yb.cloud.enabled")) {
        throw new PlatformServiceException(
            Status.METHOD_NOT_ALLOWED, "Cannot resize with gflag changes.");
      }
      masterGFlags = GFlagsUtil.trimFlags(masterGFlags);
      tserverGFlags = GFlagsUtil.trimFlags(tserverGFlags);
      GFlagsUtil.checkConsistency(masterGFlags, tserverGFlags);
    }

    if (upgradeOption != UpgradeOption.ROLLING_UPGRADE) {
      throw new IllegalArgumentException(
          "Only ROLLING_UPGRADE option is supported for resizing node (changing VM type).");
    }

    boolean hasClustersToResize = false;
    for (Cluster cluster : clusters) {
      UserIntent newUserIntent = cluster.userIntent;
      UserIntent currentUserIntent =
          universe.getUniverseDetails().getClusterByUuid(cluster.uuid).userIntent;
      if (!hasResizeChanges(currentUserIntent, newUserIntent)) {
        continue;
      }

      String errorStr =
          getResizeIsPossibleError(
              currentUserIntent, newUserIntent, universe, runtimeConfigFactory, true);
      if (errorStr != null) {
        throw new IllegalArgumentException(errorStr);
      }
      hasClustersToResize = true;
    }
    if (!hasClustersToResize && !forceResizeNode) {
      throw new IllegalArgumentException("No changes!");
    }
  }

  private boolean hasResizeChanges(UserIntent currentUserIntent, UserIntent newUserIntent) {
    if (currentUserIntent == null || newUserIntent == null) {
      return false;
    }
    return !(Objects.equals(currentUserIntent.instanceType, newUserIntent.instanceType)
        && Objects.equals(currentUserIntent.masterInstanceType, newUserIntent.masterInstanceType)
        && Objects.equals(currentUserIntent.deviceInfo, newUserIntent.deviceInfo)
        && Objects.equals(currentUserIntent.masterDeviceInfo, newUserIntent.masterDeviceInfo));
  }

  /**
   * Checks if smart resize is available
   *
   * @param currentUserIntent current user intent
   * @param newUserIntent desired user intent
   * @param universe current universe
   * @param verifyVolumeSize whether to check volume size
   * @return
   */
  public static boolean checkResizeIsPossible(
      UserIntent currentUserIntent,
      UserIntent newUserIntent,
      Universe universe,
      boolean verifyVolumeSize) {

    RuntimeConfigFactory runtimeConfigFactory =
        StaticInjectorHolder.injector().instanceOf(RuntimeConfigFactory.class);

    return checkResizeIsPossible(
        currentUserIntent, newUserIntent, universe, runtimeConfigFactory, verifyVolumeSize);
  }

  /**
   * Checks if smart resize is available
   *
   * @param currentUserIntent current user intent
   * @param newUserIntent desired user intent
   * @param universe current universe
   * @param runtimeConfigFactory config factory
   * @param verifyVolumeSize whether to check volume size
   * @return
   */
  public static boolean checkResizeIsPossible(
      UserIntent currentUserIntent,
      UserIntent newUserIntent,
      Universe universe,
      RuntimeConfigFactory runtimeConfigFactory,
      boolean verifyVolumeSize) {
    String res =
        getResizeIsPossibleError(
            currentUserIntent, newUserIntent, universe, runtimeConfigFactory, verifyVolumeSize);
    if (res != null) {
      log.debug("resize is forbidden: " + res);
    }
    return res == null;
  }

  /**
   * Checks if smart resize is available and returns error message
   *
   * @param currentUserIntent current user intent
   * @param newUserIntent desired user intent
   * @param universe current universe
   * @param verifyVolumeSize whether to check volume size
   * @return null if available, otherwise returns error message
   */
  private static String getResizeIsPossibleError(
      UserIntent currentUserIntent,
      UserIntent newUserIntent,
      Universe universe,
      RuntimeConfigFactory runtimeConfigFactory,
      boolean verifyVolumeSize) {

    boolean allowUnsupportedInstances =
        runtimeConfigFactory
            .forUniverse(universe)
            .getBoolean("yb.internal.allow_unsupported_instances");
    if (currentUserIntent == null || newUserIntent == null) {
      return "Should have both intents, but got: " + currentUserIntent + ", " + newUserIntent;
    }
    // Check valid provider.
    if (!SUPPORTED_CLOUD_TYPES.contains(currentUserIntent.providerType)) {
      return "Smart resizing is only supported for AWS / GCP / K8S, It is: "
          + currentUserIntent.providerType.toString();
    }
    if (currentUserIntent.dedicatedNodes != newUserIntent.dedicatedNodes) {
      return "Smart resize is not possible if is dedicated mode changed";
    }

    List<String> errors = new ArrayList<>();
    // Checking disk.
    boolean diskChanged =
        checkDiskChanged(
            currentUserIntent,
            newUserIntent,
            intent -> intent.deviceInfo,
            errors::add,
            verifyVolumeSize);
    boolean masterDiskChanged =
        newUserIntent.dedicatedNodes
            ? masterDiskChanged =
                checkDiskChanged(
                    currentUserIntent,
                    newUserIntent,
                    intent -> intent.masterDeviceInfo,
                    errors::add,
                    verifyVolumeSize)
            : false;
    // Checking instance type.
    boolean instanceTypeChanged =
        checkInstanceTypeChanged(
            currentUserIntent,
            newUserIntent,
            intent -> intent.instanceType,
            errors::add,
            allowUnsupportedInstances);
    boolean masterInstanceTypeChanged =
        newUserIntent.dedicatedNodes
            ? checkInstanceTypeChanged(
                currentUserIntent,
                newUserIntent,
                intent -> intent.masterInstanceType,
                errors::add,
                allowUnsupportedInstances)
            : false;

    if (errors.size() > 0) {
      return errors.get(0);
    }
    if ((diskChanged || instanceTypeChanged)
        && hasEphemeralStorage(
            currentUserIntent.providerType,
            currentUserIntent.instanceType,
            currentUserIntent.deviceInfo)) {
      return "ResizeNode operation is not supported for instances with ephemeral drives";
    }
    if ((masterDiskChanged || masterInstanceTypeChanged)
        && hasEphemeralStorage(
            currentUserIntent.providerType,
            currentUserIntent.masterInstanceType,
            currentUserIntent.masterDeviceInfo)) {
      return "ResizeNode operation is not supported for instances with ephemeral drives";
    }
    if (verifyVolumeSize
        && !diskChanged
        && !instanceTypeChanged
        && !masterDiskChanged
        && !masterInstanceTypeChanged) {
      return "Nothing changed!";
    }
    return null;
  }

  private static boolean checkDiskChanged(
      UserIntent currentUserIntent,
      UserIntent newUserIntent,
      Function<UserIntent, DeviceInfo> getter,
      Consumer<String> errorConsumer,
      boolean verifyVolumeSize) {
    DeviceInfo newDeviceInfo = getter.apply(newUserIntent);
    DeviceInfo currentDeviceInfo = getter.apply(currentUserIntent);

    // Disk will not be resized if the universe has no currently defined device info.
    if (currentDeviceInfo != null && newDeviceInfo != null) {
      DeviceInfo currentDeviceInfoCloned = currentDeviceInfo.clone();
      if (newDeviceInfo.volumeSize != null) {
        if (verifyVolumeSize && currentDeviceInfo.volumeSize > newDeviceInfo.volumeSize) {
          errorConsumer.accept(
              "Disk size cannot be decreased. It was "
                  + currentDeviceInfo.volumeSize
                  + " got "
                  + newDeviceInfo.volumeSize);
          return true;
        }
        currentDeviceInfoCloned.volumeSize = newDeviceInfo.volumeSize;
      }
      if (!Objects.equals(newDeviceInfo.diskIops, currentDeviceInfo.diskIops)) {
        if (newDeviceInfo.diskIops == null) {
          newDeviceInfo.diskIops = currentDeviceInfo.diskIops;
        }
        if (currentUserIntent.providerType != Common.CloudType.aws) {
          errorConsumer.accept("Disk IOPS provisioning is only supported for AWS");
          return true;
        }
        if (!currentDeviceInfo.storageType.isIopsProvisioning()) {
          errorConsumer.accept(
              "Disk IOPS provisioning is not allowed for storage type: "
                  + currentDeviceInfo.storageType);
          return true;
        }
        Pair<Integer, Integer> iopsRange = currentDeviceInfo.storageType.getIopsRange();
        if (newDeviceInfo.diskIops < iopsRange.getFirst()
            || newDeviceInfo.diskIops > iopsRange.getSecond()) {
          errorConsumer.accept(
              String.format(
                  "Disk IOPS value: %d is not in the acceptable range: %d - %d "
                      + "for storage type: %s",
                  newDeviceInfo.diskIops,
                  iopsRange.getFirst(),
                  iopsRange.getSecond(),
                  currentDeviceInfo.storageType));
          return true;
        }
        currentDeviceInfoCloned.diskIops = newDeviceInfo.diskIops;
      }
      if (!Objects.equals(newDeviceInfo.throughput, currentDeviceInfo.throughput)) {
        if (newDeviceInfo.throughput == null) {
          newDeviceInfo.throughput = currentDeviceInfo.throughput;
        }
        if (currentUserIntent.providerType != Common.CloudType.aws) {
          errorConsumer.accept("Disk Throughput provisioning is only supported for AWS");
          return true;
        }
        if (!currentDeviceInfo.storageType.isThroughputProvisioning()) {
          errorConsumer.accept(
              "Disk Throughput provisioning is not allowed for storage type: "
                  + currentDeviceInfo.storageType);
          return true;
        }
        Pair<Integer, Integer> throughputRange = currentDeviceInfo.storageType.getThroughputRange();
        if (newDeviceInfo.throughput < throughputRange.getFirst()
            || newDeviceInfo.throughput > throughputRange.getSecond()) {
          errorConsumer.accept(
              String.format(
                  "Disk Throughput (MiB/s) value: %d is not in the acceptable range: %d - %d"
                      + " for storage type: %s",
                  newDeviceInfo.throughput,
                  throughputRange.getFirst(),
                  throughputRange.getSecond(),
                  currentDeviceInfo.storageType));
          return true;
        }
        currentDeviceInfoCloned.throughput = newDeviceInfo.throughput;
      }

      if (!newDeviceInfo.equals(currentDeviceInfoCloned)) {
        errorConsumer.accept(
            "Smart resize only supports modifying volumeSize, diskIops, throughput");
      }
      return !currentDeviceInfo.equals(currentDeviceInfoCloned);
    }
    return false;
  }

  private static boolean checkInstanceTypeChanged(
      UserIntent currentUserIntent,
      UserIntent newUserIntent,
      Function<UserIntent, String> getter,
      Consumer<String> errorConsumer,
      boolean allowUnsupportedInstances) {
    String currentInstanceTypeCode = getter.apply(currentUserIntent);
    String newInstanceTypeCode = getter.apply(newUserIntent);
    if (newInstanceTypeCode != null
        && !Objects.equals(newInstanceTypeCode, currentInstanceTypeCode)) {
      String provider = currentUserIntent.provider;
      List<InstanceType> instanceTypes =
          InstanceType.findByProvider(
              Provider.getOrBadRequest(UUID.fromString(provider)),
              StaticInjectorHolder.injector().instanceOf(Config.class),
              allowUnsupportedInstances);
      InstanceType newInstanceType =
          instanceTypes.stream()
              .filter(type -> type.getInstanceTypeCode().equals(newInstanceTypeCode))
              .findFirst()
              .orElse(null);
      if (newInstanceType == null) {
        errorConsumer.accept(
            String.format(
                "Provider %s of type %s does not contain the intended instance type '%s'",
                currentUserIntent.provider, currentUserIntent.providerType, newInstanceTypeCode));
      }
      return true;
    }
    return false;
  }

  public boolean flagsProvided() {
    // If one is present, we know the other must be present.
    return masterGFlags != null;
  }

  public static class Converter extends BaseConverter<ResizeNodeParams> {}
}
