package com.yugabyte.yw.common.supportbundle;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.typesafe.config.Config;
import com.yugabyte.yw.common.NodeUniverseManager;
import com.yugabyte.yw.common.SupportBundleUtil;
import com.yugabyte.yw.controllers.handlers.UniverseInfoHandler;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.NodeDetails;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
class YbcLogsComponent implements SupportBundleComponent {

  private final UniverseInfoHandler universeInfoHandler;
  private final NodeUniverseManager nodeUniverseManager;
  protected final Config config;
  private final SupportBundleUtil supportBundleUtil;
  public final String NODE_UTILS_SCRIPT = "bin/node_utils.sh";

  @Inject
  YbcLogsComponent(
      UniverseInfoHandler universeInfoHandler,
      NodeUniverseManager nodeUniverseManager,
      Config config,
      SupportBundleUtil supportBundleUtil) {
    this.universeInfoHandler = universeInfoHandler;
    this.nodeUniverseManager = nodeUniverseManager;
    this.config = config;
    this.supportBundleUtil = supportBundleUtil;
  }

  @Override
  public void downloadComponent(
      Customer customer, Universe universe, Path bundlePath, NodeDetails node) throws Exception {
    String errMsg =
        String.format(
            "downloadComponent() method not applicable "
                + "for 'YbcLogsComponent' without start and end date, on universe = '%s'",
            universe.getName());
    throw new RuntimeException(errMsg);
  }

  @Override
  public void downloadComponentBetweenDates(
      Customer customer,
      Universe universe,
      Path bundlePath,
      Date startDate,
      Date endDate,
      NodeDetails node)
      throws Exception {
    // Downloads the /mnt/d0/ybc-data/controller/logs from each node
    // in the universe into the bundle path
    // Get source file path prefix
    String mountPath =
        supportBundleUtil.getDataDirPath(universe, node, nodeUniverseManager, config);
    String nodeHomeDir = mountPath + "/ybc-data";

    // Get target file path
    String nodeName = node.getNodeName();
    Path nodeTargetFile =
        Paths.get(bundlePath.toString(), this.getClass().getSimpleName() + ".tar.gz");

    log.debug(
        "Gathering YB-Controller logs for node: {}, source path: {}, target path: {}, "
            + "between start date: {}, end date: {}",
        nodeName,
        nodeHomeDir,
        nodeTargetFile.toString(),
        startDate,
        endDate);

    String ybcLogsRegexPattern = config.getString("yb.support_bundle.ybc_logs_regex_pattern");

    // Get and filter YB-Controller log files that fall within given dates
    String ybcLogsPath = nodeHomeDir + "/controller/logs";
    List<Path> ybcLogFilePaths = new ArrayList<>();
    if (nodeUniverseManager.checkNodeIfFileExists(node, universe, ybcLogsPath)) {
      ybcLogFilePaths =
          nodeUniverseManager.getNodeFilePaths(
              node, universe, ybcLogsPath, /*maxDepth*/ 1, /*fileType*/ "f");
      ybcLogFilePaths =
          supportBundleUtil.filterFilePathsBetweenDates(
              ybcLogFilePaths, Arrays.asList(ybcLogsRegexPattern), startDate, endDate);
    }

    if (ybcLogFilePaths.size() > 0) {
      Path targetFile =
          universeInfoHandler.downloadNodeFile(
              customer,
              universe,
              node,
              nodeHomeDir,
              String.join(
                  ";",
                  ybcLogFilePaths.stream()
                      .map(filePath -> Paths.get(nodeHomeDir).relativize(filePath))
                      .map(Path::toString)
                      .collect(Collectors.toList())),
              nodeTargetFile);
      try {
        if (Files.exists(targetFile)) {
          File unZippedFile =
              supportBundleUtil.unGzip(
                  new File(targetFile.toAbsolutePath().toString()),
                  new File(bundlePath.toAbsolutePath().toString()));
          Files.delete(targetFile);
          supportBundleUtil.unTar(unZippedFile, new File(bundlePath.toAbsolutePath().toString()));
          unZippedFile.delete();
        } else {
          log.debug(
              String.format(
                  "No ybc files downloaded from the source path '%s' for universe '%s'.",
                  nodeHomeDir, universe.getName()));
        }
      } catch (Exception e) {
        log.error(
            "Something went wrong while trying to untar the files from "
                + "component 'YbcLogsComponent' in the DB node: ",
            e);
      }
    } else {
      log.debug(
          "Found no matching YB-Controller logs for node: {}, source path: {}, target path: {}, "
              + "between start date: {}, end date: {}",
          nodeName,
          nodeHomeDir,
          nodeTargetFile.toString(),
          startDate,
          endDate);
    }
  }
}
