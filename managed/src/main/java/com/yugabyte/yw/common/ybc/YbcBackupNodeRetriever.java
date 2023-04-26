// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common.ybc;

import com.google.common.annotations.VisibleForTesting;
import com.yugabyte.yw.common.inject.StaticInjectorHolder;
import com.yugabyte.yw.forms.BackupRequestParams.ParallelBackupState;
import com.yugabyte.yw.models.Universe;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class YbcBackupNodeRetriever {
  // For backups in parallel, we move all subtasks to a single subtask group.
  // Initialise a node-ip queue, with the size of queue being equal to parallelism.
  // A consumer picks the node-ip, starts execution.
  // Upto parallel number, this goes on, until no more ips to pick from queue.
  // After completing task, ips returned to pool, so that next subtask blocked on it can pick it up.

  private final LinkedBlockingQueue<String> universeTserverIPs;
  private final UUID universeUUID;
  private final YbcManager ybcManager;

  public YbcBackupNodeRetriever(UUID universeUUID, int parallelism) {
    this.universeTserverIPs = new LinkedBlockingQueue<>(parallelism);
    this.universeUUID = universeUUID;
    this.ybcManager = StaticInjectorHolder.injector().instanceOf(YbcManager.class);
  }

  public void initializeNodePoolForBackups(Map<String, ParallelBackupState> backupDBStates) {
    Set<String> nodeIPsAlreadyAssigned =
        backupDBStates.entrySet().stream()
            .filter(
                bDBS ->
                    StringUtils.isNotBlank(bDBS.getValue().nodeIp)
                        && !bDBS.getValue().alreadyScheduled)
            .map(bDBS -> bDBS.getValue().nodeIp)
            .collect(Collectors.toSet());
    int nodeIPsToAdd = universeTserverIPs.remainingCapacity() - nodeIPsAlreadyAssigned.size();
    Universe universe = Universe.getOrBadRequest(universeUUID);
    String certFile = universe.getCertificateNodetoNode();
    int ybcPort = universe.getUniverseDetails().communicationPorts.ybControllerrRpcPort;
    universe.getLiveTServersInPrimaryCluster().stream()
        .map(nD -> nD.cloudInfo.private_ip)
        .filter(
            ip ->
                !nodeIPsAlreadyAssigned.contains(ip)
                    && ybcManager.ybcPingCheck(ip, certFile, ybcPort))
        .limit(nodeIPsToAdd)
        .forEach(ip -> universeTserverIPs.add(ip));

    if (nodeIPsToAdd != 0) {
      if (universeTserverIPs.size() == 0) {
        throw new RuntimeException("YB-Controller servers unavailable.");
      }
      if (universeTserverIPs.size() < nodeIPsToAdd) {
        log.warn(
            "Found unhealthy nodes, using fewer YB-Controller"
                + " orchestrators: {} than desired parallelism.",
            nodeIPsAlreadyAssigned.size() + universeTserverIPs.size());
      }
    }
  }

  public String getNodeIpForBackup() {
    try {
      return universeTserverIPs.take();
    } catch (InterruptedException e) {
      throw new CancellationException("Aborted while waiting for YBC Orchestrator node-ip.");
    }
  }

  public void putNodeIPBackToPool(String nodeIP) {
    universeTserverIPs.add(nodeIP);
  }

  @VisibleForTesting
  protected String peekNodeIpForBackup() {
    return universeTserverIPs.peek();
  }
}
