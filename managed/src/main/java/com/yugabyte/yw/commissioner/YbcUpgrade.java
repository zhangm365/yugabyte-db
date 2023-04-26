// Copyright (c) YugaByte, Inc

package com.yugabyte.yw.commissioner;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.yugabyte.yw.commissioner.tasks.InstallYbcSoftwareOnK8s;
import com.yugabyte.yw.common.KubernetesManagerFactory;
import com.yugabyte.yw.common.NodeUniverseManager;
import com.yugabyte.yw.common.PlatformScheduler;
import com.yugabyte.yw.common.ReleaseManager;
import com.yugabyte.yw.common.ShellProcessContext;
import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.common.config.GlobalConfKeys;
import com.yugabyte.yw.common.config.RuntimeConfGetter;
import com.yugabyte.yw.common.config.UniverseConfKeys;
import com.yugabyte.yw.common.services.YbcClientService;
import com.yugabyte.yw.common.ybc.YbcManager;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Universe.UniverseUpdater;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.TaskType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import lombok.extern.slf4j.Slf4j;
import org.yb.client.YbcClient;
import org.yb.ybc.ControllerStatus;
import org.yb.ybc.UpgradeRequest;
import org.yb.ybc.UpgradeRequest.Builder;
import org.yb.ybc.UpgradeResponse;
import org.yb.ybc.UpgradeResultRequest;
import org.yb.ybc.UpgradeResultResponse;

@Singleton
@Slf4j
public class YbcUpgrade {

  private final PlatformScheduler platformScheduler;
  private final RuntimeConfGetter confGetter;
  private final YbcClientService ybcClientService;
  private final YbcManager ybcManager;
  private final NodeUniverseManager nodeUniverseManager;
  private final KubernetesManagerFactory kubernetesManagerFactory;
  private final ReleaseManager releaseManager;
  private final Commissioner commissioner;

  public static final String YBC_UPGRADE_INTERVAL = "ybc.upgrade.scheduler_interval";
  public static final String YBC_UNIVERSE_UPGRADE_BATCH_SIZE_PATH =
      "ybc.upgrade.universe_batch_size";
  public static final String YBC_ALLOW_SCHEDULED_UPGRADE_PATH =
      "ybc.upgrade.allow_scheduled_upgrade";
  public static final String YBC_NODE_UPGRADE_BATCH_SIZE_PATH = "ybc.upgrade.node_batch_size";

  private final int YBC_UNIVERSE_UPGRADE_BATCH_SIZE;
  private final int YBC_NODE_UPGRADE_BATCH_SIZE;
  public final int MAX_YBC_UPGRADE_POLL_RESULT_TRIES = 30;
  public final long YBC_UPGRADE_POLL_RESULT_SLEEP_MS = 10000;
  private final long YBC_REMOTE_TIMEOUT_SEC = 60;
  private final int MAX_NUM_RETRIES = 50;
  private final String PACKAGE_PERMISSIONS = "755";
  private final String PLAT_YBC_PACKAGE_URL;

  private final CopyOnWriteArraySet<UUID> ybcUpgradeUniverseSet = new CopyOnWriteArraySet<>();
  private final Set<UUID> failedYBCUpgradeUniverseSet = new HashSet<>();
  private final Map<UUID, Set<String>> unreachableNodes = new ConcurrentHashMap<>();
  private final Map<UUID, Set<NodeDetails>> nodesToBeUpgradedWithLocalPackage = new HashMap<>();

  @Inject
  public YbcUpgrade(
      PlatformScheduler platformScheduler,
      RuntimeConfGetter confGetter,
      YbcClientService ybcClientService,
      YbcManager ybcManager,
      NodeUniverseManager nodeUniverseManager,
      KubernetesManagerFactory kubernetesManagerFactory,
      ReleaseManager releaseManager,
      Commissioner commissioner) {
    this.platformScheduler = platformScheduler;
    this.confGetter = confGetter;
    this.ybcClientService = ybcClientService;
    this.ybcManager = ybcManager;
    this.nodeUniverseManager = nodeUniverseManager;
    this.kubernetesManagerFactory = kubernetesManagerFactory;
    this.releaseManager = releaseManager;
    this.commissioner = commissioner;
    this.YBC_UNIVERSE_UPGRADE_BATCH_SIZE = getYBCUniverseBatchSize();
    this.YBC_NODE_UPGRADE_BATCH_SIZE = getYBCNodeBatchSize();
    this.PLAT_YBC_PACKAGE_URL = "http://" + Util.getHostIP() + ":9000/api/v1/fetch_package";
  }

  public void start() {
    Duration duration = this.upgradeInterval();
    platformScheduler.schedule("Ybc Upgrade", Duration.ZERO, duration, this::scheduleRunner);
  }

  private Duration upgradeInterval() {
    return confGetter.getGlobalConf(GlobalConfKeys.ybcUpgradeInterval);
  }

  private int getYBCUniverseBatchSize() {
    return confGetter.getGlobalConf(GlobalConfKeys.ybcUniverseBatchSize);
  }

  private int getYBCNodeBatchSize() {
    return confGetter.getGlobalConf(GlobalConfKeys.ybcNodeBatchSize);
  }

  public synchronized void setYBCUpgradeProcess(UUID universeUUID) {
    ybcUpgradeUniverseSet.add(universeUUID);
    unreachableNodes.put(universeUUID, new HashSet<>());
  }

  public synchronized void removeYBCUpgradeProcess(UUID universeUUID) {
    ybcUpgradeUniverseSet.remove(universeUUID);
    nodesToBeUpgradedWithLocalPackage.remove(universeUUID);
  }

  public synchronized boolean checkYBCUpgradeProcessExists(UUID universeUUID) {
    return ybcUpgradeUniverseSet.contains(universeUUID);
  }

  void scheduleRunner() {
    log.info("Running YBC Upgrade schedule");
    try {
      List<UUID> targetUniverseList = new ArrayList<UUID>();
      List<UUID> k8sUniverseList = new ArrayList<UUID>();
      int nodeCount = 0;
      String ybcVersion = ybcManager.getStableYbcVersion();
      for (Customer customer : Customer.getAll()) {
        for (Universe universe : Universe.getAllWithoutResources(customer)) {
          if (!canUpgradeYBC(universe, ybcVersion)) {
            continue;
          }
          int numNodesInUniverse = universe.getNodes().size();
          if (universe
              .getUniverseDetails()
              .getPrimaryCluster()
              .userIntent
              .providerType
              .equals(Common.CloudType.kubernetes)) {
            k8sUniverseList.add(universe.getUniverseUUID());
          } else if (targetUniverseList.size() > YBC_UNIVERSE_UPGRADE_BATCH_SIZE) {
            break;
          } else if (targetUniverseList.size() > 0
              && nodeCount + numNodesInUniverse > YBC_NODE_UPGRADE_BATCH_SIZE) {
            break;
          } else {
            nodeCount += numNodesInUniverse;
            targetUniverseList.add(universe.getUniverseUUID());
          }
        }
      }

      if (targetUniverseList.size() == 0) {
        failedYBCUpgradeUniverseSet.clear();
      }

      k8sUniverseList.forEach(
          (universeUUID) -> {
            try {
              this.upgradeYbcOnK8s(universeUUID, ybcVersion, false);
            } catch (Exception e) {
              log.error(
                  "YBC Upgrade request failed for universe {} with error: {}", universeUUID, e);
            }
          });

      targetUniverseList.forEach(
          (universeUUID) -> {
            try {
              this.upgradeYBC(universeUUID, ybcVersion, false);
            } catch (Exception e) {
              log.error(
                  "YBC Upgrade request failed for universe {} with error: {}", universeUUID, e);
              failedYBCUpgradeUniverseSet.add(universeUUID);
              removeYBCUpgradeProcess(universeUUID);
            }
          });

      int numRetries = 0;
      while (ybcUpgradeUniverseSet.size() > 0 && numRetries < MAX_YBC_UPGRADE_POLL_RESULT_TRIES) {
        numRetries++;
        boolean found = false;
        Iterator<UUID> iter = targetUniverseList.iterator();
        while (iter.hasNext()) {
          UUID universeUUID = iter.next();
          if (checkYBCUpgradeProcessExists(universeUUID)) {
            found = true;
            Optional<Universe> optional = Universe.maybeGet(universeUUID);
            if (!optional.isPresent()) {
              iter.remove();
            } else {
              pollUpgradeTaskResult(universeUUID, ybcVersion, false);
            }
          }
        }
        if (!found) {
          break;
        }
        Thread.sleep(YBC_UPGRADE_POLL_RESULT_SLEEP_MS);
      }

      targetUniverseList.forEach(
          (universeUUID) -> {
            if (checkYBCUpgradeProcessExists(universeUUID)) {
              pollUpgradeTaskResult(universeUUID, ybcVersion, true);
              removeYBCUpgradeProcess(universeUUID);
              failedYBCUpgradeUniverseSet.add(universeUUID);
            }
          });

    } catch (Exception e) {
      log.error("Error occurred while running YBC upgrade scheduler", e);
    }
  }

  public boolean canUpgradeYBC(Universe universe, String ybcVersion) {
    return universe.isYbcEnabled()
        && !universe.getUniverseDetails().universePaused
        && !universe.getUniverseDetails().updateInProgress
        && !universe.getUniverseDetails().getYbcSoftwareVersion().equals(ybcVersion)
        && !failedYBCUpgradeUniverseSet.contains(universe.getUniverseUUID());
  }

  public synchronized void upgradeYBC(UUID universeUUID, String ybcVersion, boolean force)
      throws Exception {
    Universe universe = Universe.getOrBadRequest(universeUUID);
    if (!force
        && !confGetter.getConfForScope(universe, UniverseConfKeys.ybcAllowScheduledUpgrade)) {
      log.debug(
          "Skipping scheduled ybc upgrade on universe {} as it was disabled.",
          universe.getUniverseUUID());
      return;
    }
    if (checkYBCUpgradeProcessExists(universeUUID)) {
      log.warn("YBC upgrade process already exists for universe {}", universeUUID);
      return;
    } else {
      setYBCUpgradeProcess(universeUUID);
    }
    universe
        .getNodes()
        .forEach(
            (node) -> {
              try {
                upgradeYbcOnNode(universe, node, ybcVersion, false);
              } catch (Exception e) {
                log.error(
                    "Ybc upgrade request failed on node: {} universe: {}, with error: {}",
                    node.nodeName,
                    universe.getUniverseUUID(),
                    e);
              }
            });
  }

  public synchronized void upgradeYbcOnK8s(UUID universeUUID, String ybcVersion, boolean force)
      throws Exception {
    Universe universe = Universe.getOrBadRequest(universeUUID);
    if (!force
        && !confGetter.getConfForScope(universe, UniverseConfKeys.ybcAllowScheduledUpgrade)) {
      log.debug("Skipping scheduled ybc upgrade on universe {} as it was disabled.", universeUUID);
      return;
    }
    if (checkYBCUpgradeProcessExists(universeUUID)) {
      log.warn("YBC upgrade process already exists for universe {}", universeUUID);
      return;
    } else {
      setYBCUpgradeProcess(universeUUID);
    }
    InstallYbcSoftwareOnK8s.Params taskParams = new InstallYbcSoftwareOnK8s.Params();
    taskParams.setUniverseUUID(universeUUID);
    taskParams.setYbcSoftwareVersion(ybcVersion);
    taskParams.lockUniverse = true;
    commissioner.submit(TaskType.InstallYbcSoftwareOnK8s, taskParams);
  }

  private void upgradeYbcOnNode(
      Universe universe, NodeDetails node, String ybcVersion, boolean localPackage) {
    if (!universe.getUniverseDetails().getPrimaryCluster().userIntent.useSystemd) {
      checkCronStatus(universe, node);
    }
    Integer ybcPort = universe.getUniverseDetails().communicationPorts.ybControllerrRpcPort;
    String certFile = universe.getCertificateNodetoNode();
    String nodeIp = node.cloudInfo.private_ip;
    Builder builder =
        UpgradeRequest.newBuilder()
            .setYbcVersion(ybcVersion)
            .setHomeDir(Util.getNodeHomeDir(universe.getUniverseUUID(), node));
    if (localPackage) {
      String location = ybcManager.getYbcPackageTmpLocation(universe, node, ybcVersion);
      builder.setLocation(location);
    } else {
      builder.setLocation(PLAT_YBC_PACKAGE_URL);
    }
    UpgradeRequest upgradeRequest = builder.build();
    YbcClient client = null;
    UpgradeResponse resp = null;
    try {
      client = ybcClientService.getNewClient(nodeIp, ybcPort, certFile);
      resp = client.Upgrade(upgradeRequest);
      if (resp == null) {
        unreachableNodes.getOrDefault(universe.getUniverseUUID(), new HashSet<>()).add(nodeIp);
        log.warn("Skipping node {} for YBC upgrade as it is unreachable", nodeIp);
        return;
      }
      // yb-controller throws this error only when we tries to upgrade it to same version.
      if (resp.getStatus().getCode().equals(ControllerStatus.HTTP_BAD_REQUEST)) {
        log.warn(
            "YBC {} version is already present on the node {} of universe {}",
            ybcVersion,
            nodeIp,
            universe.getUniverseUUID());
      } else if (!resp.getStatus().getCode().equals(ControllerStatus.OK)) {
        throw new RuntimeException(
            "Error occurred while sending  ybc update request: "
                + resp.getStatus().getCode().toString());
      }
    } catch (Exception e) {
      throw e;
    } finally {
      ybcClientService.closeClient(client);
    }
  }

  public synchronized boolean pollUpgradeTaskResult(
      UUID universeUUID, String ybcVersion, boolean verbose) {
    try {
      Universe universe = Universe.getOrBadRequest(universeUUID);
      UpgradeResultRequest request =
          UpgradeResultRequest.newBuilder().setYbcVersion(ybcVersion).build();
      Integer ybcPort = universe.getUniverseDetails().communicationPorts.ybControllerrRpcPort;
      String certFile = universe.getCertificateNodetoNode();
      boolean success = true;
      for (NodeDetails node : universe.getNodes()) {
        String nodeIp = node.cloudInfo.private_ip;
        if (unreachableNodes.getOrDefault(universeUUID, new HashSet<>()).contains(nodeIp)) {
          continue;
        }
        YbcClient client = null;
        try {
          client = ybcClientService.getNewClient(nodeIp, ybcPort, certFile);
          UpgradeResultResponse resp = client.UpgradeResult(request);
          if (resp == null) {
            throw new RuntimeException(
                "Could not get upgrade task result on node " + nodeIp + " as it is not reachable");
          }
          if (resp.getStatus().equals(ControllerStatus.IN_PROGRESS)) {
            success = false;
            continue;
          } else if (resp.getStatus().equals(ControllerStatus.COMMAND_FAILED)) {
            if (!nodesToBeUpgradedWithLocalPackage
                .getOrDefault(universeUUID, new HashSet<>())
                .contains(node)) {
              // We will retry the upgrade only once by placing the ybc package on DB nodes.
              nodesToBeUpgradedWithLocalPackage
                  .getOrDefault(universeUUID, new HashSet<>())
                  .add(node);
              log.info(
                  "Trying Ybc upgrade again on node {} by uploading ybc package {}",
                  node.nodeName,
                  ybcVersion);
              placeYbcPackageOnDBNode(universe, node, ybcVersion);
              upgradeYbcOnNode(universe, node, ybcVersion, true);
              success = false;
              continue;
            } else {
              removeYBCUpgradeProcess(universeUUID);
              failedYBCUpgradeUniverseSet.add(universeUUID);
              throw new RuntimeException(
                  "YBC upgrade task failed on node " + nodeIp + "  universe " + universeUUID);
            }
          } else if (!resp.getStatus().equals(ControllerStatus.COMPLETE)) {
            throw new RuntimeException(
                "YBC Upgrade is not completed on node "
                    + nodeIp
                    + " universe "
                    + universeUUID
                    + ".");
          }
        } catch (Exception e) {
          success = false;
          if (verbose) {
            log.error("Upgrade ybc task failed due to error: {}", e);
          } else {
            break;
          }
        } finally {
          ybcClientService.closeClient(client);
        }
      }
      if (success) {
        removeYBCUpgradeProcess(universeUUID);
      }
      if (!success
          || (unreachableNodes.getOrDefault(universeUUID, new HashSet<>()) != null
              && unreachableNodes.getOrDefault(universeUUID, new HashSet<>()).size() > 0)) {
        return false;
      }
      // we will update the ybc version in universe detail only when the ybc is upgraded on all DB
      // nodes.
      updateUniverseYBCVersion(universeUUID, ybcVersion);
      log.info(
          "YBC is upgraded successfully on universe {} to version {}", universeUUID, ybcVersion);
    } catch (Exception e) {
      log.error("YBC upgrade on universe {} errored out with: {}", universeUUID, e);
      return false;
    }
    return true;
  }

  private void updateUniverseYBCVersion(UUID universeUUID, String ybcVersion) {
    UniverseUpdater updater =
        new UniverseUpdater() {
          @Override
          public void run(Universe universe) {
            UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
            universeDetails.setYbcSoftwareVersion(ybcVersion);
            universe.setUniverseDetails(universeDetails);
          }
        };
    Universe.saveDetails(universeUUID, updater, false);
  }

  public String getUniverseYbcVersion(UUID universeUUID) {
    return Universe.getOrBadRequest(universeUUID).getUniverseDetails().getYbcSoftwareVersion();
  }

  private void placeYbcPackageOnDBNode(Universe universe, NodeDetails node, String ybcVersion) {
    String ybcServerPackage = ybcManager.getYbcServerPackageForNode(universe, node, ybcVersion);
    ShellProcessContext context =
        ShellProcessContext.builder()
            .logCmdOutput(false)
            .traceLogging(true)
            .timeoutSecs(YBC_REMOTE_TIMEOUT_SEC)
            .build();
    String targetFile = ybcManager.getYbcPackageTmpLocation(universe, node, ybcVersion);
    nodeUniverseManager
        .uploadFileToNode(
            node, universe, ybcServerPackage, targetFile, PACKAGE_PERMISSIONS, context)
        .processErrors();
  }

  private void checkCronStatus(Universe universe, NodeDetails node) {
    ShellProcessContext context =
        ShellProcessContext.builder()
            .logCmdOutput(false)
            .traceLogging(true)
            .timeoutSecs(YBC_REMOTE_TIMEOUT_SEC)
            .build();
    List<String> cmd = new ArrayList<>();
    String homeDir = nodeUniverseManager.getYbHomeDir(node, universe);
    String ybCtlLocation = homeDir + "/bin/yb-server-ctl.sh";
    String ybCtlControllerCmd = ybCtlLocation + " controller";
    String cronCheckCmd = ybCtlControllerCmd + " cron-check";
    String cronStartCmd = ybCtlControllerCmd + " start";

    cmd.addAll(Arrays.asList("crontab", "-l", "|", "grep", "-q", cronCheckCmd));
    cmd.add("||");
    cmd.add("(");
    cmd.addAll(Arrays.asList("crontab", "-l", "2>/dev/null", "||", "true;"));
    cmd.addAll(
        Arrays.asList(
            "echo",
            "-e",
            "#Ansible: Check liveness of controller\n*/1 * * * * "
                + cronCheckCmd
                + " || "
                + cronStartCmd));
    cmd.add(")");
    cmd.add("|");
    cmd.addAll(Arrays.asList("crontab", "-", ";"));
    nodeUniverseManager.runCommand(node, universe, cmd, context).processErrors();
  }
}
