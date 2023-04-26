// Copyright 2020 YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.yugabyte.yw.controllers;

import com.cronutils.utils.VisibleForTesting;
import com.google.inject.Inject;
import com.yugabyte.yw.common.AlertManager;
import com.yugabyte.yw.common.AlertManager.SendNotificationResult;
import com.yugabyte.yw.common.AlertManager.SendNotificationStatus;
import com.yugabyte.yw.common.AlertTemplate;
import com.yugabyte.yw.common.AlertTemplate.TestAlertSettings;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.alerts.AlertChannelService;
import com.yugabyte.yw.common.alerts.AlertChannelTemplateService;
import com.yugabyte.yw.common.alerts.AlertConfigurationService;
import com.yugabyte.yw.common.alerts.AlertDefinitionService;
import com.yugabyte.yw.common.alerts.AlertDestinationService;
import com.yugabyte.yw.common.alerts.AlertService;
import com.yugabyte.yw.common.alerts.AlertTemplateSettingsService;
import com.yugabyte.yw.common.alerts.AlertTemplateSubstitutor;
import com.yugabyte.yw.common.alerts.AlertTemplateVariableService;
import com.yugabyte.yw.common.alerts.TestAlertTemplateSubstitutor;
import com.yugabyte.yw.common.alerts.impl.AlertChannelBase;
import com.yugabyte.yw.common.alerts.impl.AlertChannelBase.Context;
import com.yugabyte.yw.common.metrics.MetricLabelsBuilder;
import com.yugabyte.yw.common.metrics.MetricService;
import com.yugabyte.yw.forms.AlertChannelFormData;
import com.yugabyte.yw.forms.AlertChannelTemplatesExt;
import com.yugabyte.yw.forms.AlertDestinationFormData;
import com.yugabyte.yw.forms.AlertTemplateSettingsFormData;
import com.yugabyte.yw.forms.AlertTemplateSystemVariable;
import com.yugabyte.yw.forms.AlertTemplateVariablesFormData;
import com.yugabyte.yw.forms.AlertTemplateVariablesList;
import com.yugabyte.yw.forms.NotificationPreview;
import com.yugabyte.yw.forms.NotificationPreviewFormData;
import com.yugabyte.yw.forms.PlatformResults;
import com.yugabyte.yw.forms.PlatformResults.YBPSuccess;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.filters.AlertApiFilter;
import com.yugabyte.yw.forms.filters.AlertConfigurationApiFilter;
import com.yugabyte.yw.forms.filters.AlertTemplateApiFilter;
import com.yugabyte.yw.forms.paging.AlertConfigurationPagedApiQuery;
import com.yugabyte.yw.forms.paging.AlertPagedApiQuery;
import com.yugabyte.yw.metrics.MetricUrlProvider;
import com.yugabyte.yw.models.Alert;
import com.yugabyte.yw.models.AlertChannel;
import com.yugabyte.yw.models.AlertChannel.ChannelType;
import com.yugabyte.yw.models.AlertChannelTemplates;
import com.yugabyte.yw.models.AlertConfiguration;
import com.yugabyte.yw.models.AlertConfiguration.Severity;
import com.yugabyte.yw.models.AlertDefinition;
import com.yugabyte.yw.models.AlertDestination;
import com.yugabyte.yw.models.AlertLabel;
import com.yugabyte.yw.models.AlertTemplateSettings;
import com.yugabyte.yw.models.AlertTemplateVariable;
import com.yugabyte.yw.models.Audit;
import com.yugabyte.yw.models.Audit.ActionType;
import com.yugabyte.yw.models.Audit.TargetType;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.extended.AlertConfigurationTemplate;
import com.yugabyte.yw.models.extended.AlertData;
import com.yugabyte.yw.models.filters.AlertConfigurationFilter;
import com.yugabyte.yw.models.filters.AlertDefinitionFilter;
import com.yugabyte.yw.models.filters.AlertFilter;
import com.yugabyte.yw.models.filters.AlertTemplateFilter;
import com.yugabyte.yw.models.filters.AlertTemplateSettingsFilter;
import com.yugabyte.yw.models.helpers.CommonUtils;
import com.yugabyte.yw.models.helpers.KnownAlertLabels;
import com.yugabyte.yw.models.paging.AlertConfigurationPagedQuery;
import com.yugabyte.yw.models.paging.AlertConfigurationPagedResponse;
import com.yugabyte.yw.models.paging.AlertDataPagedResponse;
import com.yugabyte.yw.models.paging.AlertPagedQuery;
import com.yugabyte.yw.models.paging.AlertPagedResponse;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Result;

@Api(value = "Alerts", authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
public class AlertController extends AuthenticatedController {

  @Inject private MetricService metricService;

  @Inject private AlertConfigurationService alertConfigurationService;

  @Inject private AlertDefinitionService alertDefinitionService;

  @Inject private AlertService alertService;

  @Inject private AlertChannelService alertChannelService;

  @Inject private AlertChannelTemplateService alertChannelTemplateService;

  @Inject private AlertDestinationService alertDestinationService;

  @Inject private AlertManager alertManager;

  @Inject private MetricUrlProvider metricUrlProvider;

  @Inject private AlertTemplateSettingsService alertTemplateSettingsService;

  @Inject private AlertTemplateVariableService alertTemplateVariableService;

  @ApiOperation(value = "Get details of an alert", response = Alert.class)
  public Result get(UUID customerUUID, UUID alertUUID) {
    Customer.getOrBadRequest(customerUUID);

    Alert alert = alertService.getOrBadRequest(alertUUID);
    return PlatformResults.withData(convert(alert));
  }

  /** Lists alerts for given customer. */
  @ApiOperation(
      value = "List all alerts",
      response = Alert.class,
      responseContainer = "List",
      nickname = "listOfAlerts")
  public Result list(UUID customerUUID) {
    Customer.getOrBadRequest(customerUUID);

    AlertFilter filter = AlertFilter.builder().customerUuid(customerUUID).build();
    List<Alert> alerts = alertService.list(filter);

    return PlatformResults.withData(convert(alerts));
  }

  @ApiOperation(value = "List active alerts", response = Alert.class, responseContainer = "List")
  public Result listActive(UUID customerUUID) {
    Customer.getOrBadRequest(customerUUID);

    AlertFilter filter = AlertFilter.builder().customerUuid(customerUUID).build();
    List<Alert> alerts = alertService.listNotResolved(filter);

    return PlatformResults.withData(convert(alerts));
  }

  @ApiOperation(value = "Count alerts", response = Integer.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "CountAlertsRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.filters.AlertApiFilter",
          required = true))
  public Result countAlerts(UUID customerUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);

    AlertApiFilter apiFilter = parseJsonAndValidate(request, AlertApiFilter.class);

    AlertFilter filter = apiFilter.toFilter().toBuilder().customerUuid(customerUUID).build();

    int alertCount = alertService.count(filter);

    return PlatformResults.withData(alertCount);
  }

  @ApiOperation(value = "List alerts (paginated)", response = AlertPagedResponse.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "PageAlertsRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.paging.AlertPagedApiQuery",
          required = true))
  public Result pageAlerts(UUID customerUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);

    AlertPagedApiQuery apiQuery = parseJsonAndValidate(request, AlertPagedApiQuery.class);
    AlertApiFilter apiFilter = apiQuery.getFilter();
    AlertFilter filter = apiFilter.toFilter().toBuilder().customerUuid(customerUUID).build();
    AlertPagedQuery query = apiQuery.copyWithFilter(filter, AlertPagedQuery.class);

    AlertPagedResponse alerts = alertService.pagedList(query);
    List<AlertData> alertDataList = convert(alerts.getEntities());
    AlertDataPagedResponse alertDataPagedResponse =
        alerts.setData(alertDataList, new AlertDataPagedResponse());

    return PlatformResults.withData(alertDataPagedResponse);
  }

  @ApiOperation(value = "Acknowledge an alert", response = Alert.class)
  public Result acknowledge(UUID customerUUID, UUID alertUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);

    AlertFilter filter = AlertFilter.builder().uuid(alertUUID).build();
    alertService.acknowledge(filter);

    Alert alert = alertService.getOrBadRequest(alertUUID);
    auditService()
        .createAuditEntry(
            request, Audit.TargetType.Alert, alertUUID.toString(), Audit.ActionType.Acknowledge);
    return PlatformResults.withData(convert(alert));
  }

  @ApiOperation(
      value = "Acknowledge all alerts",
      response = Alert.class,
      responseContainer = "List")
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "AcknowledgeAlertsRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.filters.AlertApiFilter",
          required = true))
  public Result acknowledgeByFilter(UUID customerUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);

    AlertApiFilter apiFilter = parseJsonAndValidate(request, AlertApiFilter.class);
    AlertFilter filter = apiFilter.toFilter().toBuilder().customerUuid(customerUUID).build();

    alertService.acknowledge(filter);

    String alertUUIDs = "";
    for (UUID uuid : filter.getUuids()) {
      alertUUIDs += uuid.toString() + " ";
    }
    auditService()
        .createAuditEntryWithReqBody(
            request, Audit.TargetType.Alert, alertUUIDs, Audit.ActionType.Acknowledge);
    return YBPSuccess.empty();
  }

  @ApiOperation(value = "Get an alert configuration", response = AlertConfiguration.class)
  public Result getAlertConfiguration(UUID customerUUID, UUID configurationUUID) {
    Customer.getOrBadRequest(customerUUID);

    AlertConfiguration configuration = alertConfigurationService.getOrBadRequest(configurationUUID);

    return PlatformResults.withData(configuration);
  }

  @ApiOperation(
      value = "Get filtered list of alert configuration templates",
      response = AlertConfigurationTemplate.class,
      responseContainer = "List")
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "ListTemplatesRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.filters.AlertTemplateApiFilter",
          required = true))
  public Result listAlertTemplates(UUID customerUUID, Http.Request request) {
    Customer customer = Customer.getOrBadRequest(customerUUID);

    AlertTemplateApiFilter apiFilter = parseJsonAndValidate(request, AlertTemplateApiFilter.class);
    AlertTemplateFilter filter = apiFilter.toFilter();

    List<AlertConfigurationTemplate> templates =
        Arrays.stream(AlertTemplate.values())
            .filter(filter::matches)
            .map(
                template ->
                    alertConfigurationService.createConfigurationTemplate(customer, template))
            .collect(Collectors.toList());

    return PlatformResults.withData(templates);
  }

  @ApiOperation(
      value = "List all alert configurations (paginated)",
      response = AlertConfigurationPagedResponse.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "PageAlertConfigurationsRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.paging.AlertConfigurationPagedApiQuery",
          required = true))
  public Result pageAlertConfigurations(UUID customerUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);

    AlertConfigurationPagedApiQuery apiQuery =
        parseJsonAndValidate(request, AlertConfigurationPagedApiQuery.class);
    AlertConfigurationApiFilter apiFilter = apiQuery.getFilter();
    AlertConfigurationFilter filter =
        apiFilter.toFilter().toBuilder().customerUuid(customerUUID).build();
    AlertConfigurationPagedQuery query =
        apiQuery.copyWithFilter(filter, AlertConfigurationPagedQuery.class);

    AlertConfigurationPagedResponse configurations = alertConfigurationService.pagedList(query);

    return PlatformResults.withData(configurations);
  }

  @ApiOperation(
      value = "Get filtered list of alert configurations",
      response = AlertConfiguration.class,
      responseContainer = "List")
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "ListAlertConfigurationsRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.filters.AlertConfigurationApiFilter",
          required = true))
  public Result listAlertConfigurations(UUID customerUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);

    AlertConfigurationApiFilter apiFilter =
        parseJsonAndValidate(request, AlertConfigurationApiFilter.class);
    AlertConfigurationFilter filter =
        apiFilter.toFilter().toBuilder().customerUuid(customerUUID).build();

    List<AlertConfiguration> configurations = alertConfigurationService.list(filter);

    return PlatformResults.withData(configurations);
  }

  @ApiOperation(value = "Create an alert configuration", response = AlertConfiguration.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "CreateAlertConfigurationRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.models.AlertConfiguration",
          required = true))
  public Result createAlertConfiguration(UUID customerUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);

    AlertConfiguration configuration = parseJson(request, AlertConfiguration.class);

    if (configuration.getUuid() != null) {
      throw new PlatformServiceException(BAD_REQUEST, "Can't create configuration with uuid set");
    }

    configuration = alertConfigurationService.save(configuration);

    auditService()
        .createAuditEntryWithReqBody(
            request,
            Audit.TargetType.Alert,
            Objects.toString(configuration.getUuid(), null),
            Audit.ActionType.Create);
    return PlatformResults.withData(configuration);
  }

  @ApiOperation(value = "Update an alert configuration", response = AlertConfiguration.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "UpdateAlertConfigurationRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.models.AlertConfiguration",
          required = true))
  public Result updateAlertConfiguration(
      UUID customerUUID, UUID configurationUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);
    alertConfigurationService.getOrBadRequest(configurationUUID);

    AlertConfiguration configuration = parseJson(request, AlertConfiguration.class);

    if (configuration.getUuid() == null) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Can't update configuration with missing uuid");
    }

    if (!configuration.getUuid().equals(configurationUUID)) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Configuration UUID from path should be consistent with body");
    }

    configuration = alertConfigurationService.save(configuration);

    auditService()
        .createAuditEntryWithReqBody(
            request, Audit.TargetType.Alert, configurationUUID.toString(), Audit.ActionType.Update);
    return PlatformResults.withData(configuration);
  }

  @ApiOperation(value = "Delete an alert configuration", response = YBPSuccess.class)
  public Result deleteAlertConfiguration(
      UUID customerUUID, UUID configurationUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);

    alertConfigurationService.getOrBadRequest(configurationUUID);

    alertConfigurationService.delete(configurationUUID);

    auditService()
        .createAuditEntry(
            request, Audit.TargetType.Alert, configurationUUID.toString(), Audit.ActionType.Delete);
    return YBPSuccess.empty();
  }

  @ApiOperation(value = "Send test alert for alert configuration", response = YBPSuccess.class)
  public Result sendTestAlert(UUID customerUUID, UUID configurationUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);

    AlertConfiguration configuration = alertConfigurationService.getOrBadRequest(configurationUUID);
    Alert alert = createTestAlert(customer, configuration, true);
    SendNotificationResult result = alertManager.sendNotification(alert);
    if (result.getStatus() != SendNotificationStatus.SUCCEEDED) {
      throw new PlatformServiceException(BAD_REQUEST, result.getMessage());
    }
    return YBPSuccess.withMessage(result.getMessage());
  }

  @ApiOperation(value = "Create an alert channel", response = AlertChannel.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "CreateAlertChannelRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.AlertChannelFormData",
          required = true))
  public Result createAlertChannel(UUID customerUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);
    AlertChannelFormData data = parseJsonAndValidate(request, AlertChannelFormData.class);
    AlertChannel channel =
        new AlertChannel().setCustomerUUID(customerUUID).setName(data.name).setParams(data.params);
    alertChannelService.save(channel);
    auditService()
        .createAuditEntryWithReqBody(
            request,
            Audit.TargetType.AlertChannel,
            Objects.toString(channel.getUuid(), null),
            Audit.ActionType.Create);
    return PlatformResults.withData(channel);
  }

  @ApiOperation(value = "Get an alert channel", response = AlertChannel.class)
  public Result getAlertChannel(UUID customerUUID, UUID alertChannelUUID) {
    Customer.getOrBadRequest(customerUUID);
    return PlatformResults.withData(
        CommonUtils.maskObject(
            alertChannelService.getOrBadRequest(customerUUID, alertChannelUUID)));
  }

  @ApiOperation(value = "Update an alert channel", response = AlertChannel.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "UpdateAlertChannelRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.AlertChannelFormData",
          required = true))
  public Result updateAlertChannel(UUID customerUUID, UUID alertChannelUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);
    AlertChannel channel = alertChannelService.getOrBadRequest(customerUUID, alertChannelUUID);
    AlertChannelFormData data = parseJsonAndValidate(request, AlertChannelFormData.class);
    channel
        .setName(data.name)
        .setParams(CommonUtils.unmaskObject(channel.getParams(), data.params));
    alertChannelService.save(channel);
    auditService()
        .createAuditEntryWithReqBody(
            request,
            Audit.TargetType.AlertChannel,
            alertChannelUUID.toString(),
            Audit.ActionType.Update);
    return PlatformResults.withData(CommonUtils.maskObject(channel));
  }

  @ApiOperation(value = "Delete an alert channel", response = YBPSuccess.class)
  public Result deleteAlertChannel(UUID customerUUID, UUID alertChannelUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);
    AlertChannel channel = alertChannelService.getOrBadRequest(customerUUID, alertChannelUUID);
    alertChannelService.delete(customerUUID, alertChannelUUID);
    metricService.markSourceRemoved(channel.getCustomerUUID(), alertChannelUUID);
    auditService()
        .createAuditEntry(
            request,
            Audit.TargetType.AlertChannel,
            alertChannelUUID.toString(),
            Audit.ActionType.Delete);
    return YBPSuccess.empty();
  }

  @ApiOperation(
      value = "List all alert channels",
      response = AlertChannel.class,
      responseContainer = "List")
  public Result listAlertChannels(UUID customerUUID) {
    Customer.getOrBadRequest(customerUUID);
    return PlatformResults.withData(
        alertChannelService.list(customerUUID).stream()
            .map(channel -> CommonUtils.maskObject(channel))
            .collect(Collectors.toList()));
  }

  @ApiOperation(value = "Get alert channel templates", response = AlertChannelTemplatesExt.class)
  public Result getAlertChannelTemplates(UUID customerUUID, String channelTypeStr) {
    Customer.getOrBadRequest(customerUUID);
    ChannelType channelType = parseChannelType(channelTypeStr);
    return PlatformResults.withData(
        alertChannelTemplateService.getWithDefaults(customerUUID, channelType));
  }

  @ApiOperation(value = "Set alert channel templates", response = AlertChannelTemplates.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "SetAlertChannelTemplatesRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.models.AlertChannelTemplates",
          required = true))
  public Result setAlertChannelTemplates(
      UUID customerUUID, String channelTypeStr, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);
    ChannelType channelType = parseChannelType(channelTypeStr);
    AlertChannelTemplates data = parseJson(request, AlertChannelTemplates.class);
    data.setType(channelType);
    data.setCustomerUUID(customerUUID);
    AlertChannelTemplates result = alertChannelTemplateService.save(data);
    auditService()
        .createAuditEntryWithReqBody(
            request, Audit.TargetType.AlertChannelTemplates, data.getType().name(), ActionType.Set);
    return PlatformResults.withData(CommonUtils.maskObject(result));
  }

  @ApiOperation(value = "Delete alert channel templates", response = YBPSuccess.class)
  public Result deleteAlertChannelTemplates(
      UUID customerUUID, String channelTypeStr, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);
    ChannelType channelType = parseChannelType(channelTypeStr);
    alertChannelTemplateService.delete(customerUUID, channelType);
    auditService()
        .createAuditEntry(
            request, TargetType.AlertChannelTemplates, channelTypeStr, Audit.ActionType.Delete);
    return YBPSuccess.empty();
  }

  @ApiOperation(
      value = "List all alert channel templates",
      response = AlertChannelTemplatesExt.class,
      responseContainer = "List")
  public Result listAlertChannelTemplates(UUID customerUUID) {
    Customer.getOrBadRequest(customerUUID);
    return PlatformResults.withData(alertChannelTemplateService.listWithDefaults(customerUUID));
  }

  @ApiOperation(value = "Create an alert destination", response = AlertDestination.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "CreateAlertDestinationRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.AlertDestinationFormData",
          required = true))
  public Result createAlertDestination(UUID customerUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);
    AlertDestinationFormData data =
        formFactory.getFormDataOrBadRequest(request, AlertDestinationFormData.class).get();
    AlertDestination destination =
        new AlertDestination()
            .setCustomerUUID(customerUUID)
            .setName(data.name)
            .setChannelsList(alertChannelService.getOrBadRequest(customerUUID, data.channels))
            .setDefaultDestination(data.defaultDestination);
    alertDestinationService.save(destination);
    auditService()
        .createAuditEntryWithReqBody(
            request,
            Audit.TargetType.AlertDestination,
            Objects.toString(destination.getUuid(), null),
            Audit.ActionType.Create);
    return PlatformResults.withData(destination);
  }

  @ApiOperation(value = "Get an alert destination", response = AlertDestination.class)
  public Result getAlertDestination(UUID customerUUID, UUID alertDestinationUUID) {
    Customer.getOrBadRequest(customerUUID);
    return PlatformResults.withData(
        alertDestinationService.getOrBadRequest(customerUUID, alertDestinationUUID));
  }

  @ApiOperation(value = "Update an alert destination", response = AlertDestination.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "UpdateAlertDestinationRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.AlertDestinationFormData",
          required = true))
  public Result updateAlertDestination(
      UUID customerUUID, UUID alertDestinationUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);
    AlertDestinationFormData data =
        formFactory.getFormDataOrBadRequest(request, AlertDestinationFormData.class).get();
    AlertDestination destination =
        alertDestinationService.getOrBadRequest(customerUUID, alertDestinationUUID);
    destination
        .setName(data.name)
        .setDefaultDestination(data.defaultDestination)
        .setChannelsList(alertChannelService.getOrBadRequest(customerUUID, data.channels));
    alertDestinationService.save(destination);
    auditService()
        .createAuditEntryWithReqBody(
            request,
            Audit.TargetType.AlertDestination,
            alertDestinationUUID.toString(),
            Audit.ActionType.Update);
    return PlatformResults.withData(destination);
  }

  @ApiOperation(value = "Delete an alert destination", response = YBPSuccess.class)
  public Result deleteAlertDestination(
      UUID customerUUID, UUID alertDestinationUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);
    alertDestinationService.delete(customerUUID, alertDestinationUUID);
    auditService()
        .createAuditEntry(
            request,
            Audit.TargetType.AlertDestination,
            alertDestinationUUID.toString(),
            Audit.ActionType.Delete);
    return YBPSuccess.empty();
  }

  @ApiOperation(
      value = "List alert destinations",
      response = AlertDefinition.class,
      responseContainer = "List")
  public Result listAlertDestinations(UUID customerUUID) {
    Customer.getOrBadRequest(customerUUID);
    return PlatformResults.withData(alertDestinationService.listByCustomer(customerUUID));
  }

  @ApiOperation(
      value = "Get alert template settings",
      response = AlertTemplateSettings.class,
      responseContainer = "List")
  public Result listAlertTemplateSettings(UUID customerUUID) {
    Customer.getOrBadRequest(customerUUID);

    List<AlertTemplateSettings> settings =
        alertTemplateSettingsService.list(
            AlertTemplateSettingsFilter.builder().customerUuid(customerUUID).build());

    return PlatformResults.withData(settings);
  }

  @ApiOperation(
      value = "Crete or update alert template settings list",
      response = AlertTemplateSettings.class,
      responseContainer = "List")
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "EditAlertTemplateSettingsRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.AlertTemplateSettingsFormData",
          required = true))
  public Result editAlertTemplateSettings(UUID customerUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);

    AlertTemplateSettingsFormData data = parseJson(request, AlertTemplateSettingsFormData.class);

    List<AlertTemplateSettings> settings =
        alertTemplateSettingsService.save(customerUUID, data.settings);

    auditService()
        .createAuditEntryWithReqBody(
            request,
            TargetType.AlertTemplateSettings,
            Objects.toString(customerUUID, null),
            Audit.ActionType.Edit);
    return PlatformResults.withData(settings);
  }

  @ApiOperation(value = "Delete an alert template settings", response = YBPSuccess.class)
  public Result deleteAlertTemplateSettings(
      UUID customerUUID, UUID settingsUuid, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);
    alertTemplateSettingsService.delete(settingsUuid);
    auditService()
        .createAuditEntry(
            request,
            TargetType.AlertTemplateSettings,
            settingsUuid.toString(),
            Audit.ActionType.Delete);
    return YBPSuccess.empty();
  }

  @ApiOperation(
      value = "List alert template variables",
      response = AlertTemplateVariablesList.class)
  public Result listAlertTemplateVariables(UUID customerUUID) {
    Customer.getOrBadRequest(customerUUID);

    List<AlertTemplateVariable> customVariables = alertTemplateVariableService.list(customerUUID);

    AlertTemplateVariablesList alertTemplateVariablesList =
        new AlertTemplateVariablesList()
            .setCustomVariables(customVariables)
            .setSystemVariables(Arrays.asList(AlertTemplateSystemVariable.values()));
    return PlatformResults.withData(alertTemplateVariablesList);
  }

  @ApiOperation(
      value = "Create or update alert template variables",
      response = AlertTemplateVariable.class,
      responseContainer = "List")
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "EditAlertTemplateVariablesRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.AlertTemplateVariablesFormData",
          required = true))
  public Result editAlertTemplateVariables(UUID customerUUID, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);

    AlertTemplateVariablesFormData data = parseJson(request, AlertTemplateVariablesFormData.class);

    List<AlertTemplateVariable> variables =
        alertTemplateVariableService.save(customerUUID, data.variables);

    auditService()
        .createAuditEntryWithReqBody(
            request,
            TargetType.AlertTemplateVariables,
            Objects.toString(customerUUID, null),
            Audit.ActionType.Edit);
    return PlatformResults.withData(variables);
  }

  @ApiOperation(value = "Delete an alert template variables", response = YBPSuccess.class)
  public Result deleteAlertTemplateVariables(
      UUID customerUUID, UUID variablesUuid, Http.Request request) {
    Customer.getOrBadRequest(customerUUID);
    alertTemplateVariableService.delete(variablesUuid);
    auditService()
        .createAuditEntry(
            request,
            TargetType.AlertTemplateVariables,
            variablesUuid.toString(),
            Audit.ActionType.Delete);
    return YBPSuccess.empty();
  }

  @ApiOperation(
      value = "Prepare alert notification preview",
      response = AlertTemplateVariablesList.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "NotificationPreviewRequest",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.AlertTemplateVariablesFormData",
          required = true))
  public Result alertNotificationPreview(UUID customerUUID, Request request) {
    Customer customer = Customer.getOrBadRequest(customerUUID);

    NotificationPreviewFormData previewFormData =
        parseJsonAndValidate(request, NotificationPreviewFormData.class);

    AlertConfiguration alertConfiguration =
        alertConfigurationService.getOrBadRequest(previewFormData.getAlertConfigUuid());

    AlertChannelTemplates channelTemplates = previewFormData.getAlertChannelTemplates();
    Alert testAlert = createTestAlert(customer, alertConfiguration, false);
    AlertChannel channel = new AlertChannel().setName("Channel name");
    List<AlertTemplateVariable> variables = alertTemplateVariableService.list(customerUUID);
    AlertChannelTemplatesExt templatesExt =
        alertChannelTemplateService.appendDefaults(
            channelTemplates, customerUUID, channelTemplates.getType());
    Context context = new Context(channel, templatesExt, variables);

    NotificationPreview preview = new NotificationPreview();
    if (channelTemplates.getType().isHasTitle()) {
      preview.setTitle(AlertChannelBase.getNotificationTitle(testAlert, context));
    }
    preview.setText(AlertChannelBase.getNotificationText(testAlert, context));
    return PlatformResults.withData(preview);
  }

  private AlertData convert(Alert alert) {
    return convert(Collections.singletonList(alert)).get(0);
  }

  private List<AlertData> convert(List<Alert> alerts) {
    List<Alert> alertsWithoutExpressionLabel =
        alerts.stream()
            .filter(alert -> alert.getLabelValue(KnownAlertLabels.ALERT_EXPRESSION) == null)
            .collect(Collectors.toList());
    List<UUID> alertsConfigurationUuids =
        alertsWithoutExpressionLabel.stream()
            .map(Alert::getConfigurationUuid)
            .collect(Collectors.toList());
    List<UUID> alertDefinitionUuids =
        alertsWithoutExpressionLabel.stream()
            .map(Alert::getDefinitionUuid)
            .collect(Collectors.toList());
    Map<UUID, AlertConfiguration> alertConfigurationMap =
        CollectionUtils.isNotEmpty(alertsConfigurationUuids)
            ? alertConfigurationService
                .list(AlertConfigurationFilter.builder().uuids(alertsConfigurationUuids).build())
                .stream()
                .collect(Collectors.toMap(AlertConfiguration::getUuid, Function.identity()))
            : Collections.emptyMap();
    Map<UUID, AlertDefinition> alertDefinitionMap =
        CollectionUtils.isNotEmpty(alertDefinitionUuids)
            ? alertDefinitionService
                .list(AlertDefinitionFilter.builder().uuids(alertDefinitionUuids).build()).stream()
                .collect(Collectors.toMap(AlertDefinition::getUuid, Function.identity()))
            : Collections.emptyMap();
    return alerts.stream()
        .map(
            alert ->
                convertInternal(
                    alert,
                    alertConfigurationMap.get(alert.getConfigurationUuid()),
                    alertDefinitionMap.get(alert.getDefinitionUuid())))
        .collect(Collectors.toList());
  }

  private AlertData convertInternal(
      Alert alert, AlertConfiguration alertConfiguration, AlertDefinition alertDefinition) {
    return new AlertData()
        .setAlert(alert)
        .setAlertExpressionUrl(getAlertExpressionUrl(alert, alertConfiguration, alertDefinition));
  }

  private String getAlertExpressionUrl(
      Alert alert, AlertConfiguration alertConfiguration, AlertDefinition alertDefinition) {
    String expression = alert.getLabelValue(KnownAlertLabels.ALERT_EXPRESSION);
    if (expression == null) {
      // Old resolved alerts may not have alert expression.
      // Will try to get from configuration and definition.
      // Thresholds and even expression itself may already be changed - but that's best we can do.
      if (alertConfiguration != null && alertDefinition != null) {
        expression =
            alertDefinition.getQueryWithThreshold(
                alertConfiguration.getThresholds().get(alert.getSeverity()));
      } else {
        return null;
      }
    }
    Long startUnixTime = TimeUnit.MILLISECONDS.toSeconds(alert.getCreateTime().getTime());
    Long endUnixTime =
        TimeUnit.MILLISECONDS.toSeconds(
            alert.getResolvedTime() != null
                ? alert.getResolvedTime().getTime()
                : System.currentTimeMillis());
    return metricUrlProvider.getExpressionUrl(expression, startUnixTime, endUnixTime);
  }

  @VisibleForTesting
  Alert createTestAlert(
      Customer customer, AlertConfiguration configuration, boolean testAlertPrefix) {
    AlertDefinition definition =
        alertDefinitionService
            .list(
                AlertDefinitionFilter.builder().configurationUuid(configuration.getUuid()).build())
            .stream()
            .findFirst()
            .orElse(null);
    if (definition == null) {
      if (configuration.getTargetType() == AlertConfiguration.TargetType.UNIVERSE) {
        Universe universe = new Universe();
        universe.setUniverseUUID(UUID.randomUUID());
        UniverseDefinitionTaskParams details = new UniverseDefinitionTaskParams();
        details.nodePrefix = "node_prefix";
        universe.setUniverseDetails(details);
        definition = new AlertDefinition();
        definition.setCustomerUUID(customer.getUuid());
        definition.setConfigurationUUID(configuration.getUuid());
        definition.setQuery(configuration.getTemplate().buildTemplate(customer, universe));
        definition.generateUUID();
        definition.setLabels(
            MetricLabelsBuilder.create()
                .appendSource(getOrCreateUniverseForTestAlert(customer))
                .getDefinitionLabels());
      } else {
        throw new PlatformServiceException(
            INTERNAL_SERVER_ERROR, "Missing definition for Platform alert configuration");
      }
    }

    Severity severity =
        configuration.getThresholds().containsKey(Severity.SEVERE)
            ? Severity.SEVERE
            : Severity.WARNING;
    AlertTemplateSettings alertTemplateSettings =
        alertTemplateSettingsService.get(
            configuration.getCustomerUUID(), configuration.getTemplate().name());
    List<AlertLabel> labels =
        definition.getEffectiveLabels(configuration, alertTemplateSettings, severity).stream()
            .map(label -> new AlertLabel(label.getName(), label.getValue()))
            .collect(Collectors.toList());
    labels.add(new AlertLabel(KnownAlertLabels.ALERTNAME.labelName(), configuration.getName()));
    labels.addAll(configuration.getTemplate().getTestAlertSettings().getAdditionalLabels());
    Map<String, String> alertLabels =
        labels.stream().collect(Collectors.toMap(AlertLabel::getName, AlertLabel::getValue));
    Alert alert =
        new Alert()
            .generateUUID()
            .setCreateTime(new Date())
            .setCustomerUUID(configuration.getCustomerUUID())
            .setDefinitionUuid(definition.getUuid())
            .setConfigurationUuid(configuration.getUuid())
            .setName(configuration.getName())
            .setSourceName(alertLabels.get(KnownAlertLabels.SOURCE_NAME.labelName()))
            .setSeverity(severity)
            .setConfigurationType(configuration.getTargetType())
            .setLabels(labels);
    String sourceUuid = alertLabels.get(KnownAlertLabels.SOURCE_UUID.labelName());
    if (StringUtils.isNotEmpty(sourceUuid)) {
      alert.setSourceUUID(UUID.fromString(sourceUuid));
    }
    alert.setMessage(buildTestAlertMessage(configuration, alert, testAlertPrefix));
    return alert;
  }

  private String buildTestAlertMessage(
      AlertConfiguration configuration, Alert alert, boolean testAlertPrefix) {
    AlertTemplate template = configuration.getTemplate();
    TestAlertSettings settings = template.getTestAlertSettings();
    if (settings.getCustomMessage() != null) {
      return settings.getCustomMessage();
    }
    String messageTemplate = template.getSummaryTemplate();
    AlertTemplateSubstitutor<Alert> alertTemplateSubstitutor =
        new AlertTemplateSubstitutor<>(alert);
    String message = alertTemplateSubstitutor.replace(messageTemplate);
    TestAlertTemplateSubstitutor testAlertTemplateSubstitutor =
        new TestAlertTemplateSubstitutor(alert, configuration);
    message = testAlertTemplateSubstitutor.replace(message);
    return testAlertPrefix ? "[TEST ALERT!!!] " + message : message;
  }

  private Universe getOrCreateUniverseForTestAlert(Customer customer) {
    Set<Universe> allUniverses = Universe.getAllWithoutResources(customer);
    Universe firstUniverse =
        allUniverses.stream()
            .filter(universe -> universe.getUniverseDetails().nodePrefix != null)
            .min(Comparator.comparing(universe -> universe.getCreationDate()))
            .orElse(null);
    if (firstUniverse != null) {
      return firstUniverse;
    }
    Universe universe = new Universe();
    universe.setName("some-universe");
    universe.setUniverseUUID(UUID.randomUUID());
    UniverseDefinitionTaskParams universeDefinitionTaskParams = new UniverseDefinitionTaskParams();
    universeDefinitionTaskParams.nodePrefix = "some-universe-node";
    universe.setUniverseDetails(universeDefinitionTaskParams);
    return universe;
  }

  private ChannelType parseChannelType(String channelTypeStr) {
    try {
      return ChannelType.valueOf(channelTypeStr);
    } catch (Exception e) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Channel type " + channelTypeStr + " does not exist");
    }
  }
}
