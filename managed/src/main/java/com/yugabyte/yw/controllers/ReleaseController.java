// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.yugabyte.yw.cloud.PublicCloudConstants.Architecture;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.ReleaseManager;
import com.yugabyte.yw.common.ReleaseManager.ReleaseMetadata;
import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.common.ValidatingFormFactory;
import com.yugabyte.yw.forms.PlatformResults;
import com.yugabyte.yw.forms.PlatformResults.YBPSuccess;
import com.yugabyte.yw.forms.ReleaseFormData;
import com.yugabyte.yw.models.Audit;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.helpers.CommonUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;

@Api(
    value = "Release management",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
public class ReleaseController extends AuthenticatedController {
  public static final Logger LOG = LoggerFactory.getLogger(ReleaseController.class);

  @Inject ReleaseManager releaseManager;

  @Inject ValidatingFormFactory formFactory;

  @ApiOperation(value = "Create a release", response = YBPSuccess.class, nickname = "createRelease")
  @ApiImplicitParams({
    @ApiImplicitParam(
        name = "Release",
        value = "Release data for remote downloading to be created",
        required = true,
        dataType = "com.yugabyte.yw.forms.ReleaseFormData",
        paramType = "body")
  })
  public Result create(UUID customerUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);

    Iterator<Map.Entry<String, JsonNode>> it = request.body().asJson().fields();
    List<ReleaseFormData> versionDataList = new ArrayList<>();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> versionJson = it.next();
      ReleaseFormData formData =
          formFactory.getFormDataOrBadRequest(versionJson.getValue(), ReleaseFormData.class);
      formData.version = versionJson.getKey();
      if (!Util.isYbVersionFormatValid(formData.version)) {
        throw new PlatformServiceException(
            BAD_REQUEST, String.format("Version %s is not valid", formData.version));
      }
      LOG.info("Asked to add new release: {} ", formData.version);
      versionDataList.add(formData);
    }

    try {
      Map<String, ReleaseMetadata> releases =
          ReleaseManager.formDataToReleaseMetadata(versionDataList);
      releases.forEach(
          (version, metadata) -> {
            releaseManager.addReleaseWithMetadata(version, metadata);
            releaseManager.addGFlagsMetadataFiles(version, metadata);
          });
      releaseManager.updateCurrentReleases();
    } catch (RuntimeException re) {
      throw new PlatformServiceException(INTERNAL_SERVER_ERROR, re.getMessage());
    }

    auditService()
        .createAuditEntryWithReqBody(
            request, Audit.TargetType.Release, versionDataList.toString(), Audit.ActionType.Create);
    return YBPSuccess.empty();
  }

  @ApiOperation(
      value = "List all releases",
      response = Object.class,
      responseContainer = "Map",
      nickname = "getListOfReleases")
  public Result list(UUID customerUUID, Boolean includeMetadata) {
    Customer.getOrBadRequest(customerUUID);
    Map<String, Object> releases = releaseManager.getReleaseMetadata();

    // Filter out any deleted releases.
    Map<String, Object> filtered =
        releases.entrySet().stream()
            .filter(f -> !Json.toJson(f.getValue()).get("state").asText().equals("DELETED"))
            .collect(
                Collectors.toMap(Entry::getKey, entry -> CommonUtils.maskObject(entry.getValue())));
    return PlatformResults.withData(includeMetadata ? filtered : filtered.keySet());
  }

  @ApiOperation(
      value = "List all releases valid in region",
      response = Object.class,
      responseContainer = "Map",
      nickname = "getListOfRegionReleases")
  public Result listByProvider(UUID customerUUID, UUID providerUUID, Boolean includeMetadata) {
    Customer.getOrBadRequest(customerUUID);
    List<Region> regionList = Region.getByProvider(providerUUID);
    if (CollectionUtils.isEmpty(regionList) || regionList.get(0) == null) {
      throw new PlatformServiceException(BAD_REQUEST, "No Regions configured for provider.");
    }
    Region region = regionList.get(0);
    Map<String, Object> releases = releaseManager.getReleaseMetadata();
    Architecture arch = region.getArchitecture();
    // Old region without architecture. Return all releases.
    if (arch == null) {
      LOG.info(
          "ReleaseController: Could not determine region {} architecture. Listing all releases.",
          region.getCode());
      return list(customerUUID, includeMetadata);
    }

    // Filter for active and matching region releases.
    Map<String, Object> filtered =
        releases.entrySet().stream()
            .filter(f -> !Json.toJson(f.getValue()).get("state").asText().equals("DELETED"))
            .filter(f -> releaseManager.metadataFromObject(f.getValue()).matchesRegion(region))
            .collect(
                Collectors.toMap(Entry::getKey, entry -> CommonUtils.maskObject(entry.getValue())));
    return PlatformResults.withData(includeMetadata ? filtered : filtered.keySet());
  }

  @ApiOperation(
      value = "Update a release",
      response = ReleaseManager.ReleaseMetadata.class,
      nickname = "updateRelease")
  @ApiImplicitParams({
    @ApiImplicitParam(
        name = "Release",
        value = "Release data to be updated",
        required = true,
        dataType = "Object",
        paramType = "body")
  })
  public Result update(UUID customerUUID, String version, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);

    ObjectNode formData;
    ReleaseManager.ReleaseMetadata m = releaseManager.getReleaseByVersion(version);
    if (m == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Invalid Release version: " + version);
    }
    formData = (ObjectNode) request.body().asJson();

    // For now we would only let the user change the state on their releases.
    if (formData.has("state")) {
      String stateValue = formData.get("state").asText();
      LOG.info("Updating release state for version {} to {}", version, stateValue);
      m.state = ReleaseManager.ReleaseState.valueOf(stateValue);
      releaseManager.updateReleaseMetadata(version, m);
    } else {
      throw new PlatformServiceException(BAD_REQUEST, "Missing Required param: State");
    }
    auditService()
        .createAuditEntryWithReqBody(
            request, Audit.TargetType.Release, version, Audit.ActionType.Update);
    return PlatformResults.withData(m);
  }

  @ApiOperation(value = "Refresh a release", response = YBPSuccess.class)
  public Result refresh(UUID customerUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);

    LOG.info("ReleaseController: refresh");
    try {
      releaseManager.importLocalReleases();
      releaseManager.updateCurrentReleases();
    } catch (RuntimeException re) {
      throw new PlatformServiceException(INTERNAL_SERVER_ERROR, re.getMessage());
    }
    auditService()
        .createAuditEntry(request, Audit.TargetType.Release, null, Audit.ActionType.Refresh);
    return YBPSuccess.empty();
  }

  @ApiOperation(
      value = "Delete a release",
      response = ReleaseManager.ReleaseMetadata.class,
      nickname = "deleteRelease")
  public Result delete(UUID customerUUID, String version, Http.Request request) {
    if (releaseManager.getReleaseByVersion(version) == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Invalid Release version: " + version);
    }

    if (releaseManager.getInUse(version)) {
      throw new PlatformServiceException(BAD_REQUEST, "Release " + version + " is in use!");
    }
    try {
      releaseManager.removeRelease(version);
    } catch (RuntimeException re) {
      throw new PlatformServiceException(INTERNAL_SERVER_ERROR, re.getMessage());
    }
    auditService()
        .createAuditEntry(request, Audit.TargetType.Release, version, Audit.ActionType.Delete);
    return YBPSuccess.empty();
  }
}
