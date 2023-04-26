/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.common;

import static com.yugabyte.yw.common.utils.FileUtils.writeFile;
import static com.yugabyte.yw.common.utils.FileUtils.writeJsonFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.PatternFilenameFilter;
import com.typesafe.config.Config;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.common.alerts.AlertRuleTemplateSubstitutor;
import com.yugabyte.yw.common.config.RuntimeConfGetter;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.common.config.UniverseConfKeys;
import com.yugabyte.yw.models.AlertConfiguration;
import com.yugabyte.yw.models.AlertDefinition;
import com.yugabyte.yw.models.AlertTemplateSettings;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.MetricCollectionLevel;
import com.yugabyte.yw.models.helpers.NodeDetails;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Environment;
import play.libs.Json;

@Singleton
public class SwamperHelper {
  public static final Logger LOG = LoggerFactory.getLogger(SwamperHelper.class);

  private static final String UUID_PATTERN =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

  @VisibleForTesting static final String ALERT_CONFIG_FILE_PREFIX = "yugaware.ad.";
  @VisibleForTesting static final String ALERT_CONFIG_FILE_PREFIX_PATTERN = "yugaware\\.ad\\.";
  @VisibleForTesting static final String RECORDING_RULES_FILE = "yugaware.recording-rules.yml";
  private static final Pattern ALERT_CONFIG_FILE_PATTERN =
      Pattern.compile("^yugaware\\.ad\\." + UUID_PATTERN + "\\.yml$");

  @VisibleForTesting static final String TARGET_FILE_NODE_PREFIX = "node.";
  @VisibleForTesting static final String TARGET_FILE_YUGABYTE_PREFIX = "yugabyte.";
  @VisibleForTesting static final String TARGET_FILE_PREFIX_PATTERN = "(node|yugabyte)\\.";
  private static final Pattern TARGET_FILE_PATTERN =
      Pattern.compile("^(node|yugabyte)\\." + UUID_PATTERN + ".json$");

  private static final String TARGET_PATH_PARAM = "yb.swamper.targetPath";
  private static final String RULES_PATH_PARAM = "yb.swamper.rulesPath";
  public static final String COLLECTION_LEVEL_PARAM = "yb.metrics.collection_level";
  public static final String SCRAPE_INTERVAL_PARAM = "yb.metrics.scrape_interval";
  public static final String RANGE_PLACEHOLDER = "\\{\\{ range \\}\\}";

  private static final String PARAMETER_LABEL_PREFIX = "__param_";

  private static final int IRATE_SCRAPE_PERIODS = 5;

  /*
     Sample targets file
    [
      {
        "targets": [
          '10.150.0.64:9300', '10.150.0.49:9300', '10.150.0.62:9300',
          '10.150.0.64:7000', '10.150.0.49:7000', '10.150.0.62:7000',
          '10.150.0.64:9000', '10.150.0.49:9000', '10.150.0.62:9000',
          '10.150.0.64:11000', '10.150.0.49:11000', '10.150.0.62:11000',
          '10.150.0.64:12000', '10.150.0.49:12000', '10.150.0.62:12000'
        ],
        "labels": {
          "node_prefix": "1-a-b"
        }
      }
    ]
  */

  private final RuntimeConfigFactory runtimeConfigFactory;
  private final Environment environment;
  private final RuntimeConfGetter confGetter;

  @Inject
  public SwamperHelper(
      RuntimeConfigFactory runtimeConfigFactory,
      Environment environment,
      RuntimeConfGetter confGetter) {
    this.runtimeConfigFactory = runtimeConfigFactory;
    this.environment = environment;
    this.confGetter = confGetter;
  }

  @Getter
  public enum TargetType {
    INVALID_EXPORT(false),
    NODE_EXPORT(false),
    MASTER_EXPORT(true),
    TSERVER_EXPORT(true),
    REDIS_EXPORT(true),
    CQL_EXPORT(true),
    YSQL_EXPORT(true);

    private final boolean collectionLevelSupported;

    TargetType(boolean collectionLevelSupported) {
      this.collectionLevelSupported = collectionLevelSupported;
    }

    public int getPort(NodeDetails nodeDetails) {
      switch (this) {
        case INVALID_EXPORT:
          return 0;
        case NODE_EXPORT:
          return nodeDetails.nodeExporterPort;
        case MASTER_EXPORT:
          return nodeDetails.masterHttpPort;
        case TSERVER_EXPORT:
          return nodeDetails.tserverHttpPort;
        case REDIS_EXPORT:
          return nodeDetails.redisServerHttpPort;
        case CQL_EXPORT:
          return nodeDetails.yqlServerHttpPort;
        case YSQL_EXPORT:
          return nodeDetails.ysqlServerHttpPort;
        default:
          return 0;
      }
    }
  }

  public enum LabelType {
    NODE_PREFIX,
    EXPORT_TYPE,
    EXPORTED_INSTANCE,
    UNIVERSE_UUID
  }

  private ObjectNode getIndividualConfig(Universe universe, TargetType t, NodeDetails nodeDetails) {
    ObjectNode target = Json.newObject();
    ArrayNode targetNodes = Json.newArray();
    int port = t.getPort(nodeDetails);
    if (nodeDetails.isActive() && port > 0) {
      targetNodes.add(nodeDetails.cloudInfo.private_ip + ":" + port);
    }

    ObjectNode labels = Json.newObject();
    labels.put(
        LabelType.UNIVERSE_UUID.toString().toLowerCase(), universe.getUniverseUUID().toString());
    labels.put(
        LabelType.NODE_PREFIX.toString().toLowerCase(), universe.getUniverseDetails().nodePrefix);
    labels.put(LabelType.EXPORT_TYPE.toString().toLowerCase(), t.toString().toLowerCase());
    if (nodeDetails.nodeName != null) {
      labels.put(LabelType.EXPORTED_INSTANCE.toString().toLowerCase(), nodeDetails.nodeName);
    }
    if (t.isCollectionLevelSupported()) {
      MetricCollectionLevel level = getLevel(universe);
      appendCollectionLevelLabels(level, labels);
    }

    target.set("targets", targetNodes);
    target.set("labels", labels);
    return target;
  }

  private File getSwamperTargetDirectory() {
    return getOrCreateDirectory(TARGET_PATH_PARAM);
  }

  private String getSwamperFile(UUID universeUUID, String prefix) {
    File swamperTargetDirectory = getSwamperTargetDirectory();

    if (swamperTargetDirectory != null) {
      return String.format("%s/%s%s.json", swamperTargetDirectory, prefix, universeUUID.toString());
    }
    return null;
  }

  public void writeUniverseTargetJson(UUID universeUUID) {
    Universe universe = Universe.getOrBadRequest(universeUUID);
    writeUniverseTargetJson(universe);
  }

  public void writeUniverseTargetJson(Universe universe) {
    MetricCollectionLevel level = getLevel(universe);

    if (level.isDisableCollection()) {
      removeUniverseTargetJson(universe.getUniverseUUID());
      return;
    }

    // Write out the node specific file.
    ArrayNode nodeTargets = Json.newArray();
    String swamperFile = getSwamperFile(universe.getUniverseUUID(), TARGET_FILE_NODE_PREFIX);
    if (swamperFile == null) {
      return;
    }

    universe
        .getNodes()
        .forEach(
            (node) -> {
              if (universe.getNodeDeploymentMode(node).equals(CloudType.kubernetes)) {
                // no node exporter on k8s pods
                return;
              }
              nodeTargets.add(getIndividualConfig(universe, TargetType.NODE_EXPORT, node));
            });
    writeJsonFile(swamperFile, nodeTargets);

    // Write out the yugabyte specific file.
    ArrayNode ybTargets = Json.newArray();
    swamperFile = getSwamperFile(universe.getUniverseUUID(), TARGET_FILE_YUGABYTE_PREFIX);
    universe
        .getNodes()
        .forEach(
            (node) -> {
              // Since some nodes might not be active (for example removed),
              // we do not want to add them to the swamper targets.
              if (!node.isActive()) return;

              for (TargetType t : TargetType.values()) {
                if (t == TargetType.NODE_EXPORT || t == TargetType.INVALID_EXPORT) {
                  continue;
                }

                if (!node.isMaster && t.equals(TargetType.MASTER_EXPORT)) {
                  continue;
                }

                if (node.isTserver) {
                  if (!node.isYsqlServer && t.equals(TargetType.YSQL_EXPORT)) {
                    continue;
                  }
                  if (!node.isYqlServer && t.equals(TargetType.CQL_EXPORT)) {
                    continue;
                  }
                  if (!node.isRedisServer && t.equals(TargetType.REDIS_EXPORT)) {
                    continue;
                  }
                } else {
                  if (t.equals(TargetType.TSERVER_EXPORT)
                      || t.equals(TargetType.CQL_EXPORT)
                      || t.equals(TargetType.REDIS_EXPORT)
                      || t.equals(TargetType.YSQL_EXPORT)) {
                    continue;
                  }
                }

                ybTargets.add(getIndividualConfig(universe, t, node));
              }
            });

    writeJsonFile(swamperFile, ybTargets);
  }

  private void removeUniverseTargetJson(UUID universeUUID, String prefix) {
    String swamperFile = getSwamperFile(universeUUID, prefix);
    if (swamperFile != null) {
      File file = new File(swamperFile);

      if (file.exists()) {
        LOG.info("Deleting Swamper Target file: {}", swamperFile);
        file.delete();
      }
    }
  }

  public void removeUniverseTargetJson(UUID universeUUID) {
    // TODO: make these constants / enums.
    removeUniverseTargetJson(universeUUID, TARGET_FILE_NODE_PREFIX);
    removeUniverseTargetJson(universeUUID, TARGET_FILE_YUGABYTE_PREFIX);
  }

  private File getSwamperRuleDirectory() {
    return getOrCreateDirectory(RULES_PATH_PARAM);
  }

  private String getAlertRuleFile(UUID ruleUUID) {
    return getRulesFile(String.format("%s%s.yml", ALERT_CONFIG_FILE_PREFIX, ruleUUID.toString()));
  }

  private String getRulesFile(String filename) {
    File swamperRulesDirectory = getSwamperRuleDirectory();
    if (swamperRulesDirectory != null) {
      return String.format("%s/%s", swamperRulesDirectory, filename);
    }
    return null;
  }

  public void writeRecordingRules() {
    String rulesFile = getRulesFile(RECORDING_RULES_FILE);
    if (rulesFile == null) {
      return;
    }

    String fileContent;
    try (InputStream templateStream = environment.resourceAsStream("metric/recording_rules.yml")) {
      fileContent = IOUtils.toString(templateStream, StandardCharsets.UTF_8);
      long scrapeInterval = getScrapeIntervalSeconds(runtimeConfigFactory.staticApplicationConf());
      fileContent =
          fileContent.replaceAll(
              RANGE_PLACEHOLDER, String.format("%ds", (scrapeInterval * IRATE_SCRAPE_PERIODS)));
    } catch (IOException e) {
      throw new RuntimeException("Failed to read alert definition header template", e);
    }

    writeFile(rulesFile, fileContent);
  }

  public void writeAlertDefinition(
      AlertConfiguration configuration,
      AlertDefinition definition,
      AlertTemplateSettings templateSettings) {
    String swamperFile = getAlertRuleFile(definition.getUuid());
    if (swamperFile == null) {
      return;
    }

    String fileContent;
    try (InputStream templateStream =
        environment.resourceAsStream("alert/alert_definition_header.yml")) {
      fileContent = IOUtils.toString(templateStream, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read alert definition header template", e);
    }

    String template;
    try (InputStream templateStream =
        environment.resourceAsStream("alert/alert_definition_rule.yml")) {
      template = IOUtils.toString(templateStream, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read alert definition rule template", e);
    }

    fileContent +=
        configuration.getThresholds().keySet().stream()
            .map(
                severity -> {
                  AlertRuleTemplateSubstitutor substitutor =
                      new AlertRuleTemplateSubstitutor(
                          configuration, definition, severity, templateSettings);
                  return substitutor.replace(template);
                })
            .collect(Collectors.joining());

    writeFile(swamperFile, fileContent);
  }

  public void removeAlertDefinition(UUID definitionUUID) {
    String swamperFile = getAlertRuleFile(definitionUUID);
    if (swamperFile != null) {
      File file = new File(swamperFile);

      if (file.exists()) {
        file.delete();
        LOG.info("Swamper Rules file deleted: {}", swamperFile);
      }
    }
  }

  public List<UUID> getAlertDefinitionConfigUuids() {
    return extractUuids(
        getSwamperRuleDirectory(), ALERT_CONFIG_FILE_PATTERN, ALERT_CONFIG_FILE_PREFIX_PATTERN);
  }

  public List<UUID> getTargetUniverseUuids() {
    return extractUuids(
        getSwamperTargetDirectory(), TARGET_FILE_PATTERN, TARGET_FILE_PREFIX_PATTERN);
  }

  private List<UUID> extractUuids(File directory, Pattern pattern, String prefix) {
    if (directory == null) {
      return Collections.emptyList();
    }
    String[] targetFiles = directory.list(new PatternFilenameFilter(pattern));
    if (targetFiles == null) {
      throw new RuntimeException("Failed to list files in " + directory);
    }
    return Arrays.stream(targetFiles)
        .map(FilenameUtils::removeExtension)
        .map(filename -> filename.replaceAll(prefix, ""))
        .map(UUID::fromString)
        .collect(Collectors.toList());
  }

  private File getOrCreateDirectory(String configParam) {
    String directoryPath = runtimeConfigFactory.staticApplicationConf().getString(configParam);
    if (StringUtils.isEmpty(directoryPath)) {
      return null;
    }
    File directory = new File(directoryPath);

    if (directory.isDirectory() || directory.mkdirs()) {
      return directory;
    }
    return null;
  }

  private MetricCollectionLevel getLevel(Universe universe) {
    return MetricCollectionLevel.fromString(
        confGetter.getConfForScope(universe, UniverseConfKeys.metricsCollectionLevel));
  }

  private void appendCollectionLevelLabels(MetricCollectionLevel level, ObjectNode labels) {
    String paramsFile = level.getParamsFilePath();
    if (StringUtils.isEmpty(paramsFile)) {
      return;
    }

    try (InputStream templateStream = environment.resourceAsStream(paramsFile)) {
      String paramsFileContent = IOUtils.toString(templateStream, StandardCharsets.UTF_8);
      ObjectNode params = (ObjectNode) Json.parse(paramsFileContent);
      Iterator<Entry<String, JsonNode>> fields = params.fields();
      while (fields.hasNext()) {
        Entry<String, JsonNode> field = fields.next();
        String paramName = field.getKey();
        JsonNode paramValue = field.getValue();
        String paramStringValue;
        if (paramValue.isArray()) {
          List<String> parts = new ArrayList<>();
          for (JsonNode part : paramValue) {
            parts.add(part.textValue());
          }
          paramStringValue = String.join("", parts);
        } else {
          paramStringValue = paramValue.textValue();
        }
        labels.put(PARAMETER_LABEL_PREFIX + paramName, paramStringValue);
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to read or process params file " + paramsFile, e);
    }
  }

  public static long getScrapeIntervalSeconds(Config config) {
    return Util.goDurationToJava(config.getString(SCRAPE_INTERVAL_PARAM)).getSeconds();
  }
}
