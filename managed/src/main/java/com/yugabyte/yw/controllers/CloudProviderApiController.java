/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.util.Throwables;
import com.google.inject.Inject;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.tasks.CloudBootstrap;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.controllers.handlers.CloudProviderHandler;
import com.yugabyte.yw.forms.EditAccessKeyRotationScheduleParams;
import com.yugabyte.yw.forms.PlatformResults;
import com.yugabyte.yw.forms.PlatformResults.YBPSuccess;
import com.yugabyte.yw.forms.PlatformResults.YBPTask;
import com.yugabyte.yw.forms.RotateAccessKeyFormData;
import com.yugabyte.yw.forms.ScheduledAccessKeyRotateFormData;
import com.yugabyte.yw.models.Audit;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Schedule;
import com.yugabyte.yw.models.helpers.CloudInfoInterface;
import com.yugabyte.yw.models.helpers.TaskType;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;

@Api(
    value = "Cloud providers",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
@Slf4j
public class CloudProviderApiController extends AuthenticatedController {

  @Inject private CloudProviderHandler cloudProviderHandler;
  @Inject private RuntimeConfigFactory runtimeConfigFactory;

  @ApiOperation(
      value = "List cloud providers",
      response = Provider.class,
      responseContainer = "List",
      nickname = "getListOfProviders")
  public Result list(UUID customerUUID, String name, String code) {
    CloudType providerCode = code == null ? null : CloudType.valueOf(code);
    List<Provider> providers = Provider.getAll(customerUUID, name, providerCode);
    providers.forEach(CloudInfoInterface::mayBeMassageResponse);
    return PlatformResults.withData(providers);
  }

  @ApiOperation(value = "Get a cloud provider", response = Provider.class, nickname = "getProvider")
  public Result index(UUID customerUUID, UUID providerUUID) {
    Customer.getOrBadRequest(customerUUID);
    Provider provider = Provider.getOrBadRequest(customerUUID, providerUUID);
    CloudInfoInterface.mayBeMassageResponse(provider);
    return PlatformResults.withData(provider);
  }

  @ApiOperation(value = "Delete a cloud provider", response = YBPSuccess.class)
  public Result delete(UUID customerUUID, UUID providerUUID, Http.Request request) {
    Customer customer = Customer.getOrBadRequest(customerUUID);

    UUID taskUUID = cloudProviderHandler.delete(customer, providerUUID);
    auditService()
        .createAuditEntry(
            request,
            Audit.TargetType.CloudProvider,
            providerUUID.toString(),
            Audit.ActionType.Delete,
            taskUUID);
    return new YBPTask(taskUUID, providerUUID).asResult();
  }

  @ApiOperation(
      value = "Refresh pricing",
      notes = "Refresh provider pricing info",
      response = YBPSuccess.class)
  public Result refreshPricing(UUID customerUUID, UUID providerUUID, Http.Request request) {
    Provider provider = Provider.getOrBadRequest(customerUUID, providerUUID);
    cloudProviderHandler.refreshPricing(customerUUID, provider);
    auditService()
        .createAuditEntry(
            request,
            Audit.TargetType.CloudProvider,
            providerUUID.toString(),
            Audit.ActionType.RefreshPricing);
    return YBPSuccess.withMessage(provider.getCode().toUpperCase() + " Initialized");
  }

  @ApiOperation(value = "Update a provider", response = YBPTask.class, nickname = "editProvider")
  @ApiImplicitParams(
      @ApiImplicitParam(
          value = "edit provider form data",
          name = "EditProviderRequest",
          dataType = "com.yugabyte.yw.models.Provider",
          required = true,
          paramType = "body"))
  public Result edit(
      UUID customerUUID,
      UUID providerUUID,
      boolean validate,
      boolean ignoreValidationErrors,
      Http.Request request) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Provider provider = Provider.getOrBadRequest(customerUUID, providerUUID);

    if (!runtimeConfigFactory
        .globalRuntimeConf()
        .getBoolean("yb.provider.allow_used_provider_edit")) {
      // Relaxing the edit provider call for used provider based on runtime flag
      // If disabled we will not allow editing of used providers.
      long universeCount = provider.getUniverseCount();
      if (universeCount > 0) {
        throw new PlatformServiceException(
            FORBIDDEN,
            String.format(
                "There %s %d universe%s using this provider, cannot modify",
                universeCount > 1 ? "are" : "is", universeCount, universeCount > 1 ? "s" : ""));
      }
    }
    JsonNode requestBody = mayBeMassageRequest(request.body().asJson(), true);

    Provider editProviderReq = formFactory.getFormDataOrBadRequest(requestBody, Provider.class);
    UUID taskUUID =
        cloudProviderHandler.editProvider(
            customer, provider, editProviderReq, validate, ignoreValidationErrors);
    auditService()
        .createAuditEntryWithReqBody(
            request,
            Audit.TargetType.CloudProvider,
            providerUUID.toString(),
            Audit.ActionType.Update);
    return new YBPTask(taskUUID, providerUUID).asResult();
  }

  @ApiOperation(value = "Create a provider", response = YBPTask.class, nickname = "createProviders")
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "CreateProviderRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.models.Provider",
          required = true))
  public Result create(
      UUID customerUUID, boolean validate, boolean ignoreValidationErrors, Http.Request request) {
    JsonNode requestBody = mayBeMassageRequest(request.body().asJson(), false);
    Provider reqProvider =
        formFactory.getFormDataOrBadRequest(request.body().asJson(), Provider.class);
    Customer customer = Customer.getOrBadRequest(customerUUID);
    reqProvider.setCustomerUUID(customerUUID);
    CloudType providerCode = CloudType.valueOf(reqProvider.getCode());
    Provider providerEbean;
    if (providerCode.equals(CloudType.kubernetes)) {
      providerEbean = cloudProviderHandler.createKubernetesNew(customer, reqProvider);
    } else {
      providerEbean =
          cloudProviderHandler.createProvider(
              customer,
              providerCode,
              reqProvider.getName(),
              reqProvider,
              validate,
              ignoreValidationErrors);
    }

    if (providerCode.isRequiresBootstrap()) {
      UUID taskUUID = null;
      try {
        CloudBootstrap.Params taskParams =
            CloudBootstrap.Params.fromProvider(providerEbean, reqProvider);

        taskUUID = cloudProviderHandler.bootstrap(customer, providerEbean, taskParams);
        auditService()
            .createAuditEntryWithReqBody(
                request,
                Audit.TargetType.CloudProvider,
                Objects.toString(providerEbean.getUuid(), null),
                Audit.ActionType.Create,
                requestBody,
                taskUUID);
      } catch (Throwable e) {
        log.warn("Bootstrap failed. Deleting provider");
        providerEbean.delete();
        Throwables.propagate(e);
      }
      return new YBPTask(taskUUID, providerEbean.getUuid()).asResult();
    } else {
      auditService()
          .createAuditEntryWithReqBody(
              request,
              Audit.TargetType.CloudProvider,
              Objects.toString(providerEbean.getUuid(), null),
              Audit.ActionType.Create,
              requestBody,
              null);
      return new YBPTask(null, providerEbean.getUuid()).asResult();
    }
  }

  @ApiOperation(
      nickname = "accessKeyRotation",
      value = "Rotate access key for a provider",
      response = YBPTask.class)
  public Result accessKeysRotation(UUID customerUUID, UUID providerUUID, Http.Request request) {
    RotateAccessKeyFormData params = parseJsonAndValidate(request, RotateAccessKeyFormData.class);
    Customer customer = Customer.getOrBadRequest(customerUUID);
    String newKeyCode = params.newKeyCode;
    boolean rotateAllUniverses = params.rotateAllUniverses;
    if (!rotateAllUniverses && params.universeUUIDs.size() == 0) {
      throw new PlatformServiceException(
          BAD_REQUEST,
          "Need to specify universeUUIDs"
              + " for access key rotation or set rotateAllUniverses to true!");
    }
    List<UUID> universeUUIDs =
        rotateAllUniverses
            ? customer.getUniversesForProvider(providerUUID).stream()
                .map(universe -> universe.getUniverseUUID())
                .collect(Collectors.toList())
            : params.universeUUIDs;

    Map<UUID, UUID> tasks =
        cloudProviderHandler.rotateAccessKeys(
            customerUUID, providerUUID, universeUUIDs, newKeyCode);

    // contains taskUUID and resourceUUID (universeUUID) for each universe
    List<YBPTask> tasksResponseList = new ArrayList<>();
    tasks.forEach(
        (universeUUID, taskUUID) -> tasksResponseList.add(new YBPTask(taskUUID, universeUUID)));
    auditService()
        .createAuditEntryWithReqBody(
            request,
            Audit.TargetType.CloudProvider,
            Objects.toString(providerUUID, null),
            Audit.ActionType.RotateAccessKey);
    return PlatformResults.withData(tasksResponseList);
  }

  @ApiOperation(
      nickname = "scheduledAccessKeyRotation",
      value = "Rotate access key for a provider - Scheduled",
      response = Schedule.class)
  public Result scheduledAccessKeysRotation(
      UUID customerUUID, UUID providerUUID, Http.Request request) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    ScheduledAccessKeyRotateFormData params =
        parseJsonAndValidate(request, ScheduledAccessKeyRotateFormData.class);
    int schedulingFrequencyDays = params.schedulingFrequencyDays;
    boolean rotateAllUniverses = params.rotateAllUniverses;
    if (!rotateAllUniverses && params.universeUUIDs.size() == 0) {
      throw new PlatformServiceException(
          BAD_REQUEST,
          "Need to specify universeUUIDs"
              + " to schedule access key rotation or set rotateAllUniverses to true!");
    }
    List<UUID> universeUUIDs =
        rotateAllUniverses
            ? customer.getUniversesForProvider(providerUUID).stream()
                .map(universe -> universe.getUniverseUUID())
                .collect(Collectors.toList())
            : params.universeUUIDs;
    Schedule schedule =
        cloudProviderHandler.scheduleAccessKeysRotation(
            customerUUID, providerUUID, universeUUIDs, schedulingFrequencyDays, rotateAllUniverses);
    UUID scheduleUUID = schedule.getScheduleUUID();
    log.info(
        "Created access key rotation schedule for customer {}, schedule uuid = {}.",
        customerUUID,
        scheduleUUID);
    auditService()
        .createAuditEntryWithReqBody(
            request,
            Audit.TargetType.CloudProvider,
            Objects.toString(providerUUID, null),
            Audit.ActionType.CreateAndRotateAccessKey);
    return PlatformResults.withData(schedule);
  }

  @ApiOperation(
      value = "List all schedules for a provider's access key rotation",
      response = Schedule.class,
      responseContainer = "List",
      nickname = "listSchedules")
  public Result listAccessKeyRotationSchedules(UUID customerUUID, UUID providerUUID) {
    Customer.getOrBadRequest(customerUUID);
    Provider.getOrBadRequest(customerUUID, providerUUID);
    List<Schedule> accessKeyRotationSchedules =
        Schedule.getAllByCustomerUUIDAndType(customerUUID, TaskType.CreateAndRotateAccessKey)
            .stream()
            .filter(schedule -> (schedule.getOwnerUUID().equals(providerUUID)))
            .collect(Collectors.toList());
    return PlatformResults.withData(accessKeyRotationSchedules);
  }

  @ApiOperation(
      value = "Edit a access key rotation schedule",
      response = Schedule.class,
      nickname = "editAccessKeyRotationSchedule")
  @ApiImplicitParams({
    @ApiImplicitParam(
        required = true,
        dataType = "com.yugabyte.yw.forms.EditAccessKeyRotationScheduleParams",
        paramType = "body")
  })
  public Result editAccessKeyRotationSchedule(
      UUID customerUUID, UUID providerUUID, UUID scheduleUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);
    Provider.getOrBadRequest(customerUUID, providerUUID);
    EditAccessKeyRotationScheduleParams params =
        parseJsonAndValidate(request, EditAccessKeyRotationScheduleParams.class);

    Schedule schedule =
        cloudProviderHandler.editAccessKeyRotationSchedule(
            customerUUID, providerUUID, scheduleUUID, params);

    auditService()
        .createAuditEntryWithReqBody(
            request, Audit.TargetType.Schedule, scheduleUUID.toString(), Audit.ActionType.Edit);
    return PlatformResults.withData(schedule);
  }

  // v2 API version 1 backward compatibility support.
  private JsonNode mayBeMassageRequest(JsonNode requestBody, Boolean forEdit) {
    JsonNode config = requestBody.get("config");
    if (forEdit && config != null) {
      ((ObjectNode) requestBody).remove("config");
      // Clear the deprecated top level fields that are supported only on create.
      // Edit is a new API and we wont allow changing these fields at top-level during
      // the edit operation.
      ((ObjectNode) requestBody).remove("sshUser");
      ((ObjectNode) requestBody).remove("sshPort");
      ((ObjectNode) requestBody).remove("airGapInstall");
      ((ObjectNode) requestBody).remove("ntpServers");
      ((ObjectNode) requestBody).remove("setUpChrony");
      ((ObjectNode) requestBody).remove("showSetUpChrony");
      ((ObjectNode) requestBody).remove("keyPairName");
      ((ObjectNode) requestBody).remove("sshPrivateKeyContent");
    }
    String providerCode = requestBody.get("code").asText();
    ObjectMapper mapper = Json.mapper();
    JsonNode regions = requestBody.get("regions");
    ArrayNode regionsNode = mapper.createArrayNode();
    if (regions != null && regions.isArray()) {
      for (JsonNode region : regions) {
        ObjectNode regionWithProviderCode = mapper.createObjectNode();
        regionWithProviderCode.put("providerCode", providerCode);
        if (region.has("config") && forEdit) {
          ((ObjectNode) region).remove("config");
        }
        regionWithProviderCode.setAll((ObjectNode) region);
        JsonNode zones = region.get("zones");
        ArrayNode zonesNode = mapper.createArrayNode();
        if (zones != null && zones.isArray()) {
          for (JsonNode zone : zones) {
            ObjectNode zoneWithProviderCode = mapper.createObjectNode();
            if (zone.has("config") && forEdit) {
              ((ObjectNode) zone).remove("config");
            }
            zoneWithProviderCode.put("providerCode", providerCode);
            zoneWithProviderCode.setAll((ObjectNode) zone);
            zonesNode.add(zoneWithProviderCode);
          }
        }
        regionWithProviderCode.remove("zones");
        regionWithProviderCode.put("zones", zonesNode);
        regionsNode.add(regionWithProviderCode);
      }
    }
    ((ObjectNode) requestBody).remove("regions");
    ((ObjectNode) requestBody).put("regions", regionsNode);

    return CloudInfoInterface.mayBeMassageRequest(requestBody, true);
  }
}
