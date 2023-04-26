// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.commissioner.tasks.subtasks;

import com.google.common.collect.ImmutableList;
import com.yugabyte.yw.commissioner.AbstractTaskBase;
import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.tasks.params.NodeTaskParams;
import com.yugabyte.yw.common.NodeAgentManager;
import com.yugabyte.yw.common.NodeAgentManager.InstallerFiles;
import com.yugabyte.yw.common.NodeUniverseManager;
import com.yugabyte.yw.common.ShellProcessContext;
import com.yugabyte.yw.models.NodeAgent;
import com.yugabyte.yw.models.NodeAgent.ArchType;
import com.yugabyte.yw.models.NodeAgent.OSType;
import com.yugabyte.yw.models.NodeAgent.State;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.NodeDetails;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class InstallNodeAgent extends AbstractTaskBase {
  public static final int DEFAULT_NODE_AGENT_PORT = 9070;

  private final NodeUniverseManager nodeUniverseManager;
  private final NodeAgentManager nodeAgentManager;
  private final ShellProcessContext shellContext =
      ShellProcessContext.builder()
          .logCmdOutput(true)
          .defaultSshPort(true)
          .customUser(true)
          .build();

  @Inject
  protected InstallNodeAgent(
      BaseTaskDependencies baseTaskDependencies,
      NodeUniverseManager nodeUniverseManager,
      NodeAgentManager nodeAgentManager) {
    super(baseTaskDependencies);
    this.nodeUniverseManager = nodeUniverseManager;
    this.nodeAgentManager = nodeAgentManager;
  }

  public static class Params extends NodeTaskParams {
    public int nodeAgentPort = DEFAULT_NODE_AGENT_PORT;
    public String nodeAgentHome;
    public UUID customerUuid;
  }

  @Override
  protected Params taskParams() {
    return (Params) taskParams;
  }

  private NodeAgent createNodeAgent(Universe universe, NodeDetails node) {
    String output =
        nodeUniverseManager
            .runCommand(node, universe, Arrays.asList("uname", "-sm"), shellContext)
            .processErrors()
            .extractRunCommandOutput();
    if (StringUtils.isBlank(output)) {
      throw new RuntimeException("Unknown OS and Arch output: " + output);
    }
    // Output is like Linux x86_64.
    String[] parts = output.split("\\s+", 2);
    if (parts.length != 2) {
      throw new RuntimeException("Unknown OS and Arch output: " + output);
    }
    NodeAgent nodeAgent = new NodeAgent();
    nodeAgent.setIp(node.cloudInfo.private_ip);
    nodeAgent.setName(node.nodeName);
    nodeAgent.setPort(taskParams().nodeAgentPort);
    nodeAgent.setCustomerUuid(taskParams().customerUuid);
    nodeAgent.setOsType(OSType.parse(parts[0].trim()));
    nodeAgent.setArchType(ArchType.parse(parts[1].trim()));
    nodeAgent.setVersion(nodeAgentManager.getSoftwareVersion());
    nodeAgent.setHome(taskParams().nodeAgentHome);
    return nodeAgentManager.create(nodeAgent, false);
  }

  @Override
  public void run() {
    Universe universe = Universe.getOrBadRequest(taskParams().getUniverseUUID());
    NodeDetails node = universe.getNodeOrBadRequest(taskParams().nodeName);
    Optional<NodeAgent> optional = NodeAgent.maybeGetByIp(node.cloudInfo.private_ip);
    if (optional.isPresent()) {
      NodeAgent nodeAgent = optional.get();
      if (nodeAgent.getState() == State.READY) {
        return;
      }
      nodeAgentManager.purge(nodeAgent);
    }
    NodeAgent nodeAgent = createNodeAgent(universe, node);
    Path baseTargetDir = Paths.get("/tmp", "node-agent-" + System.currentTimeMillis());
    InstallerFiles installerFiles = nodeAgentManager.getInstallerFiles(nodeAgent, baseTargetDir);
    Set<String> dirs =
        installerFiles.getCreateDirs().stream()
            .map(dir -> dir.toString())
            .collect(Collectors.toSet());
    StringBuilder sb = new StringBuilder();
    sb.append("mkdir -p ").append(baseTargetDir);
    sb.append(" && chmod 777 ").append(baseTargetDir);
    String baseTargetDirCommand = sb.toString();
    // Create the base directory with sudo first, make it writable for all users.
    // This is done because some on-prem nodes may not have write permission to /tmp.
    List<String> command = ImmutableList.of("sudo", "/bin/bash", "-c", baseTargetDirCommand);
    log.info("Creating base target directory: {}", baseTargetDirCommand);
    nodeUniverseManager.runCommand(node, universe, command, shellContext).processErrors();
    // Create the child folders as the current SSH user so that the files can be uploaded.
    command = ImmutableList.<String>builder().add("mkdir", "-p").addAll(dirs).build();
    log.info("Creating directories {} for node agent {}", dirs, nodeAgent.getUuid());
    nodeUniverseManager.runCommand(node, universe, command, shellContext).processErrors();
    installerFiles.getCopyFileInfos().stream()
        .forEach(
            f -> {
              log.info(
                  "Uploading {} to {} on node agent {}",
                  f.getSourcePath(),
                  f.getTargetPath(),
                  nodeAgent.getUuid());
              String filePerm = StringUtils.isBlank(f.getPermission()) ? "755" : f.getPermission();
              nodeUniverseManager
                  .uploadFileToNode(
                      node,
                      universe,
                      f.getSourcePath().toString(),
                      f.getTargetPath().toString(),
                      filePerm,
                      shellContext)
                  .processErrors();
            });
    sb.setLength(0);
    sb.append("rm -rf /tmp/node-agent-installer.sh /root/node-agent");
    sb.append(" && tar -zxf ").append(installerFiles.getPackagePath());
    sb.append(" --strip-components=3 -C /tmp --wildcards */node-agent-installer.sh");
    sb.append(" && chmod +x /tmp/node-agent-installer.sh");
    sb.append(" && mv -f ").append(baseTargetDir).append("/node-agent");
    sb.append(" ").append(taskParams().nodeAgentHome);
    sb.append(" && rm -rf ").append(baseTargetDir);
    sb.append(" && /tmp/node-agent-installer.sh -c install");
    sb.append(" --skip_verify_cert --disable_egress");
    sb.append(" --id ").append(nodeAgent.getUuid());
    sb.append(" --cert_dir ").append(installerFiles.getCertDir());
    sb.append(" --node_ip ").append(node.cloudInfo.private_ip);
    sb.append(" --node_port ").append(String.valueOf(taskParams().nodeAgentPort));
    sb.append(" && chmod 755 /root ").append(taskParams().nodeAgentHome);
    String installCommand = sb.toString();
    log.debug("Running node agent installation command: {}", installCommand);
    command = ImmutableList.of("sudo", "-H", "/bin/bash", "-c", installCommand);
    nodeUniverseManager.runCommand(node, universe, command, shellContext).processErrors();
  }
}
