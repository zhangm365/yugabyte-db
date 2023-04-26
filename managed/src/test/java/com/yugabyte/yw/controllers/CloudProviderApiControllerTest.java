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

import static com.yugabyte.yw.common.AssertHelper.assertAuditEntry;
import static com.yugabyte.yw.common.AssertHelper.assertBadRequest;
import static com.yugabyte.yw.common.AssertHelper.assertInternalServerError;
import static com.yugabyte.yw.common.AssertHelper.assertOk;
import static com.yugabyte.yw.common.AssertHelper.assertPlatformException;
import static com.yugabyte.yw.common.AssertHelper.assertValue;
import static com.yugabyte.yw.common.AssertHelper.assertValues;
import static com.yugabyte.yw.common.AssertHelper.assertYBPSuccess;
import static com.yugabyte.yw.common.ModelFactory.createUniverse;
import static com.yugabyte.yw.common.TestHelper.createTempFile;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.test.Helpers.contentAsString;

import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.ec2.model.Vpc;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.typesafe.config.Config;
import com.yugabyte.yw.cloud.CloudAPI;
import com.yugabyte.yw.cloud.PublicCloudConstants.Architecture;
import com.yugabyte.yw.cloud.gcp.GCPCloudImpl;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.tasks.CloudBootstrap;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.forms.PlatformResults.YBPTask;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.AccessKey;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.ImageBundle;
import com.yugabyte.yw.models.ImageBundleDetails;
import com.yugabyte.yw.models.InstanceType;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.ProviderDetails;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.RegionDetails;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Users;
import com.yugabyte.yw.models.helpers.CloudInfoInterface;
import com.yugabyte.yw.models.helpers.TaskType;
import com.yugabyte.yw.models.helpers.provider.AWSCloudInfo;
import com.yugabyte.yw.models.helpers.provider.GCPCloudInfo;
import com.yugabyte.yw.models.helpers.provider.region.AWSRegionCloudInfo;
import com.yugabyte.yw.models.helpers.provider.region.AzureRegionCloudInfo;
import com.yugabyte.yw.models.helpers.provider.region.GCPRegionCloudInfo;
import com.yugabyte.yw.models.helpers.provider.region.KubernetesRegionInfo;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Result;

@RunWith(JUnitParamsRunner.class)
@Slf4j
public class CloudProviderApiControllerTest extends FakeDBApplication {
  public static final Logger LOG = LoggerFactory.getLogger(CloudProviderApiControllerTest.class);
  private static final ImmutableList<String> REGION_CODES_FROM_CLOUD_API =
      ImmutableList.of("region1", "region2");

  @Mock Config mockConfig;

  Customer customer;
  Users user;

  @Before
  public void setUp() {
    customer = ModelFactory.testCustomer();
    user = ModelFactory.testUser(customer);
    try {
      String kubeFile = createTempFile("test2.conf", "test5678");
      when(mockAccessManager.createKubernetesConfig(anyString(), anyMap(), anyBoolean()))
          .thenReturn(kubeFile);
    } catch (Exception e) {
      // Do nothing
    }
    String gcpCredentialFile = createTempFile("gcpCreds.json", "test5678");
    when(mockAccessManager.createGCPCredentialsFile(any(), any())).thenReturn(gcpCredentialFile);
  }

  private Result listProviders() {
    return doRequestWithAuthToken(
        "GET", "/api/customers/" + customer.getUuid() + "/providers", user.createAuthToken());
  }

  private Result createProvider(JsonNode bodyJson) {
    return createProvider(bodyJson, false);
  }

  private Result createProvider(JsonNode bodyJson, boolean ignoreValidation) {
    return doRequestWithAuthTokenAndBody(
        "POST",
        "/api/customers/"
            + customer.getUuid()
            + "/providers?validate=true&ignoreValidationErrors="
            + ignoreValidation,
        user.createAuthToken(),
        bodyJson);
  }

  private Result createKubernetesProvider(JsonNode bodyJson) {
    return doRequestWithAuthTokenAndBody(
        "POST",
        "/api/customers/" + customer.getUuid() + "/providers/kubernetes",
        user.createAuthToken(),
        bodyJson);
  }

  private Result getKubernetesSuggestedConfig() {
    return doRequestWithAuthToken(
        "GET",
        "/api/customers/" + customer.getUuid() + "/providers/suggested_kubernetes_config",
        user.createAuthToken());
  }

  private Result getProvider(UUID providerUUID) {
    return doRequestWithAuthToken(
        "GET",
        "/api/customers/" + customer.getUuid() + "/providers/" + providerUUID,
        user.createAuthToken());
  }

  private Result deleteProvider(UUID providerUUID) {
    return doRequestWithAuthToken(
        "DELETE",
        "/api/customers/" + customer.getUuid() + "/providers/" + providerUUID,
        user.createAuthToken());
  }

  private Result editProvider(JsonNode bodyJson, UUID providerUUID, boolean validate) {
    return editProvider(bodyJson, providerUUID, validate, false);
  }

  private Result editProvider(JsonNode bodyJson, UUID providerUUID) {
    return editProvider(bodyJson, providerUUID, true, false);
  }

  private Result editProvider(
      JsonNode bodyJson, UUID providerUUID, boolean validate, boolean ignoreValidation) {
    return doRequestWithAuthTokenAndBody(
        "PUT",
        "/api/customers/"
            + customer.getUuid()
            + "/providers/"
            + providerUUID
            + String.format(
                "/edit?validate=%s&ignoreValidationErrors=%s", validate, ignoreValidation),
        user.createAuthToken(),
        bodyJson);
  }

  private Result bootstrapProviderXX(JsonNode bodyJson, Provider provider) {
    return doRequestWithAuthTokenAndBody(
        "POST",
        "/api/customers/" + customer.getUuid() + "/providers/" + provider.getUuid() + "/bootstrap",
        user.createAuthToken(),
        bodyJson);
  }

  //  @Test
  public void testListEmptyProviders() {
    Result result = listProviders();
    JsonNode json = Json.parse(contentAsString(result));

    assertOk(result);
    assertTrue(json.isArray());
    assertEquals(0, json.size());
    assertAuditEntry(0, customer.getUuid());
  }

  //  @Test
  public void testListProviders() {
    Provider p1 = ModelFactory.awsProvider(customer);
    p1.setConfigMap(
        ImmutableMap.of("MY_KEY_DATA", "SENSITIVE_DATA", "MY_SECRET_DATA", "SENSITIVE_DATA"));
    p1.save();
    Provider p2 = ModelFactory.gcpProvider(customer);
    p2.setConfigMap(ImmutableMap.of("FOO", "BAR"));
    p2.save();
    Result result = listProviders();
    JsonNode json = Json.parse(contentAsString(result));

    assertOk(result);
    assertAuditEntry(0, customer.getUuid());
    assertEquals(2, json.size());
    assertValues(json, "uuid", ImmutableList.of(p1.getUuid().toString(), p2.getUuid().toString()));
    assertValues(json, "name", ImmutableList.of(p1.getName(), p2.getName()));
    json.forEach(
        (providerJson) -> {
          JsonNode config = providerJson.get("config");
          if (UUID.fromString(providerJson.get("uuid").asText()).equals(p1.getUuid())) {
            assertValue(config, "MY_KEY_DATA", "SE**********TA");
            assertValue(config, "MY_SECRET_DATA", "SE**********TA");
          } else {
            assertValue(config, "FOO", "BAR");
          }
        });
  }

  //  @Test
  public void testListProvidersWithValidCustomer() {
    Provider.create(UUID.randomUUID(), Common.CloudType.aws, "Amazon");
    Provider p = ModelFactory.gcpProvider(customer);
    Result result = listProviders();
    JsonNode json = Json.parse(contentAsString(result));

    assertOk(result);
    assertEquals(1, json.size());
    assertValues(json, "uuid", ImmutableList.of(p.getUuid().toString()));
    assertValues(json, "name", ImmutableList.of(p.getName()));
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testCreateProvider() {
    createProviderTest(
        buildProviderReq("azu", "Microsoft"),
        ImmutableList.of("region1", "region2"),
        UUID.randomUUID());
  }

  @Test
  public void testCreateGCPProviderSomeRegionInput() {
    when(mockCloudQueryHelper.getCurrentHostInfo(eq(CloudType.gcp)))
        .thenReturn(Json.newObject().put("network", "234234").put("project", "PROJ"));
    Provider provider = buildProviderReq("gcp", "Google");
    Region region = new Region();
    region.setName("region1");
    region.setProvider(provider);
    region.setCode("region1");
    provider.setRegions(ImmutableList.of(region));
    provider = createProviderTest(provider, ImmutableList.of(), UUID.randomUUID());
    assertNull(provider.getDestVpcId());
    assertNull(provider.getHostVpcId());
  }

  @Test
  public void testCreateGCPProviderHostVPC() {
    when(mockCloudQueryHelper.getCurrentHostInfo(eq(CloudType.gcp)))
        .thenReturn(Json.newObject().put("network", "234234").put("project", "PROJ"));
    Provider provider = buildProviderReq("gcp", "Google");
    Map<String, String> reqConfig = new HashMap<>();
    reqConfig.put("use_host_vpc", "true");
    reqConfig.put("use_host_credentials", "true");
    CloudInfoInterface.setCloudProviderInfoFromConfig(provider, reqConfig);
    provider = createProviderTest(provider, ImmutableList.of("region1"), UUID.randomUUID());
    Map<String, String> config = CloudInfoInterface.fetchEnvVars(provider);
    GCPCloudInfo gcpCloudInfo = CloudInfoInterface.get(provider);
    assertEquals("234234", gcpCloudInfo.getHostVpcId());
    assertEquals("234234", gcpCloudInfo.getDestVpcId());
    assertEquals("PROJ", config.get(GCPCloudImpl.GCE_PROJECT_PROPERTY));
  }

  @Test
  public void testCreateGCPProviderCreateNewVPC() {
    when(mockCloudQueryHelper.getCurrentHostInfo(eq(CloudType.gcp)))
        .thenReturn(Json.newObject().put("network", "234234").put("project", "PROJ"));
    Provider provider = buildProviderReq("gcp", "Google");
    Map<String, String> reqConfig = new HashMap<>();
    reqConfig.put("use_host_vpc", "false");
    reqConfig.put("use_host_credentials", "false");
    CloudInfoInterface.setCloudProviderInfoFromConfig(provider, reqConfig);
    provider = createProviderTest(provider, ImmutableList.of("region1"), UUID.randomUUID());
    GCPCloudInfo gcpCloudInfo = CloudInfoInterface.get(provider);
    assertEquals(CloudInfoInterface.VPCType.NEW, gcpCloudInfo.getVpcType());
  }

  @Test
  public void testCreateAWSProviderHostVPC() {
    when(mockCloudQueryHelper.getCurrentHostInfo(eq(CloudType.aws)))
        .thenReturn(Json.newObject().put("vpc-id", "234234").put("region", "VPCreg"));
    Provider provider = buildProviderReq("aws", "AWS");
    provider = createProviderTest(provider, ImmutableList.of("region1"), UUID.randomUUID());
    AWSCloudInfo awsCloudInfo = CloudInfoInterface.get(provider);
    assertEquals("234234", awsCloudInfo.getHostVpcId());
    assertEquals("VPCreg", awsCloudInfo.getHostVpcRegion());
  }

  @Test
  public void testCreateGCPProviderNoRegionInput() {
    createProviderTest(
        buildProviderReq("gcp", "Google"),
        ImmutableList.of("region1", "region2"),
        UUID.randomUUID());
  }

  @Test
  public void testAwsBootstrapWithDestVpcId() {
    Provider providerReq = buildProviderReq("aws", "Amazon");
    providerReq.setDestVpcId("nofail");
    createProviderTest(providerReq, ImmutableList.of("region1", "region2"), UUID.randomUUID());
  }

  private Provider createProviderTest(
      Provider provider, ImmutableList<String> regionCodesFromCloudAPI, UUID actualTaskUUID) {
    JsonNode bodyJson = Json.toJson(provider);
    boolean isOnprem = CloudType.onprem.name().equals(provider.getCode());
    when(mockCommissioner.submit(any(TaskType.class), any(CloudBootstrap.Params.class)))
        .thenReturn(actualTaskUUID);
    if (!isOnprem) {
      when(mockCloudQueryHelper.getRegionCodes(provider)).thenReturn(regionCodesFromCloudAPI);
    }
    Result result = createProvider(bodyJson);
    assertOk(result);
    YBPTask ybpTask = Json.fromJson(Json.parse(contentAsString(result)), YBPTask.class);
    if (!isOnprem) {
      // When regions not supplied in request then we expect a call to cloud API to get region codes
      verify(mockCloudQueryHelper, times(provider.getRegions().isEmpty() ? 1 : 0))
          .getRegionCodes(any());
    }
    assertEquals(actualTaskUUID, ybpTask.taskUUID);
    Provider createdProvider = Provider.get(customer.getUuid(), ybpTask.resourceUUID);
    assertEquals(provider.getCode(), createdProvider.getCode());
    assertEquals(provider.getName(), createdProvider.getName());
    assertAuditEntry(1, customer.getUuid());
    return createdProvider; // Note this is still partially created since our commissioner is fake.
  }

  private Provider buildProviderReq(String actualProviderCode, String actualProviderName) {
    Provider provider = new Provider();
    provider.setUuid(UUID.randomUUID());
    provider.setCode(actualProviderCode);
    provider.setName(actualProviderName);
    provider.setDetails(new ProviderDetails());
    return provider;
  }

  @Test
  public void testCreateMultiInstanceProvider() {
    ModelFactory.awsProvider(customer);
    createProviderTest(
        buildProviderReq("aws", "Amazon1"),
        ImmutableList.of("region1", "region2"),
        UUID.randomUUID());
  }

  @Test
  public void testCreateMultiInstanceProviderWithSameNameAndCloud() {
    ModelFactory.awsProvider(customer);
    Result result =
        assertPlatformException(
            () ->
                createProviderTest(
                    buildProviderReq("aws", "Amazon"),
                    ImmutableList.of("region1", "region2"),
                    UUID.randomUUID()));
    assertBadRequest(result, "Provider with the name Amazon already exists");
  }

  @Test
  public void testCreateMultiInstanceProviderWithSameNameButDifferentCloud() {
    ModelFactory.awsProvider(customer);
    createProviderTest(
        buildProviderReq("gcp", "Amazon1"),
        ImmutableList.of("region1", "region2"),
        UUID.randomUUID());
  }

  @Test
  public void testCreateProviderSameNameDiffCustomer() {
    Provider.create(UUID.randomUUID(), Common.CloudType.aws, "Amazon");
    createProviderTest(
        buildProviderReq("aws", "Amazon"), REGION_CODES_FROM_CLOUD_API, UUID.randomUUID());
  }

  @Test
  public void testCreateWithInvalidParams() {
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "aws");
    Result result =
        assertPlatformException(
            () ->
                createProviderTest(
                    buildProviderReq("aws", null), REGION_CODES_FROM_CLOUD_API, UUID.randomUUID()));
    assertBadRequest(result, "\"name\":[\"error.required\"]}");
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  @Parameters({"aws", "gcp", "onprem"})
  public void testCreateProviderWithConfig(String code) {
    String providerName = code + "-Provider";
    Provider providerReq = buildProviderReq(code, providerName);
    Map<String, String> reqConfig = new HashMap<>();
    when(mockCloudQueryHelper.getCurrentHostInfo(eq(CloudType.gcp)))
        .thenReturn(Json.newObject().put("network", "234234").put("project", "PROJ"));
    if (code.equals("gcp")) {
      // Note that we do not wrap the GCP config in API requests. Caller should do extracting
      // config file details and putting it in the config map
      reqConfig.put("use_host_credentials", "true");
      reqConfig.put("use_host_vpc", "true");
      reqConfig.put("project_id", "project");
      reqConfig.put("config_file_path", "/tmp/credentials.json");
    } else if (code.equals("aws")) {
      reqConfig.put("AWS_ACCESS_KEY_ID", "bar");
      reqConfig.put("AWS_SECRET_ACCESS_KEY", "bar2");
    } else {
      reqConfig.put("YB_HOME_DIR", "/bar");
    }
    providerReq.setCustomerUUID(customer.getUuid());
    CloudInfoInterface.setCloudProviderInfoFromConfig(providerReq, reqConfig);
    Provider createdProvider =
        createProviderTest(providerReq, REGION_CODES_FROM_CLOUD_API, UUID.randomUUID());
    Map<String, String> config = CloudInfoInterface.fetchEnvVars(createdProvider);
    assertFalse(config.isEmpty());
    if (code.equals("gcp")) {
      assertEquals(reqConfig.size() - 1, config.size());
    } else {
      assertEquals(reqConfig.size(), config.size());
    }
  }

  @Test
  public void testCreateProviderPassesInstanceTemplateToBootstrapParams() {
    // Follow-up: Bootstrapping with instance template is tested by
    // CloudBootstrapTest.testCloudBootstrapWithInstanceTemplate().
    String code = "gcp"; // Instance template feature is only implemented for GCP currently.
    String region = "us-west1";
    String instanceTemplate = "test-template";
    when(mockCloudQueryHelper.getCurrentHostInfo(eq(CloudType.gcp)))
        .thenReturn(Json.newObject().put("network", "234234").put("project", "PROJ"));
    Provider provider = buildProviderReq(code, code + "-Provider");
    Map<String, String> reqConfig = new HashMap<>();
    reqConfig.put("use_host_vpc", "true");
    reqConfig.put("use_host_credentials", "true");
    CloudInfoInterface.setCloudProviderInfoFromConfig(provider, reqConfig);

    addRegion(provider, region);
    provider
        .getRegions()
        .get(0)
        .getDetails()
        .getCloudInfo()
        .getGcp()
        .setInstanceTemplate(instanceTemplate);
    CloudAPI mockCloudAPI = mock(CloudAPI.class);
    Mockito.doNothing().when(mockCloudAPI).validateInstanceTemplate(any(), any());
    when(mockCloudAPI.isValidCreds(any(), any())).thenReturn(true);
    when(mockCloudAPIFactory.get(any())).thenReturn(mockCloudAPI);

    when(mockCloudQueryHelper.getRegionCodes(provider)).thenReturn(ImmutableList.of(region));
    when(mockCommissioner.submit(any(TaskType.class), any(CloudBootstrap.Params.class)))
        .thenAnswer(
            invocation -> {
              CloudBootstrap.Params taskParams = invocation.getArgument(1);
              CloudBootstrap.Params.PerRegionMetadata m = taskParams.perRegionMetadata.get(region);
              assertEquals(instanceTemplate, m.instanceTemplate);
              return UUID.randomUUID();
            });
    assertOk(createProvider(Json.toJson(provider)));
    verify(mockCommissioner, times(1))
        .submit(any(TaskType.class), any(CloudBootstrap.Params.class));
  }

  private void addRegion(Provider provider, String regionCode) {
    Region region = new Region();
    region.setProvider(provider);
    region.setCode(regionCode);
    region.setName("Region");
    region.setYbImage("YB Image");
    region.setLatitude(0.0);
    region.setLongitude(0.0);
    RegionDetails rd = new RegionDetails();
    RegionDetails.RegionCloudInfo rdCloudInfo = new RegionDetails.RegionCloudInfo();
    switch (provider.getCloudCode()) {
      case gcp:
        rdCloudInfo.setGcp(new GCPRegionCloudInfo());
        break;
      case aws:
        rdCloudInfo.setAws(new AWSRegionCloudInfo());
        break;
      case kubernetes:
        rdCloudInfo.setKubernetes(new KubernetesRegionInfo());
        break;
      case azu:
        rdCloudInfo.setAzu(new AzureRegionCloudInfo());
        break;
    }
    rd.setCloudInfo(rdCloudInfo);
    region.setDetails(rd);
    provider.getRegions().add(region);
  }

  // Following tests wont be migrated because no k8s multi-region support:
  //  public void testCreateKubernetesMultiRegionProvider() {
  //  public void testCreateKubernetesMultiRegionProviderFailure() {
  //  public void testGetK8sSuggestedConfig() {
  //  public void testGetK8sSuggestedConfigWithoutPullSecret() {
  //  public void testGetKubernetesConfigsDiscoveryFailure() {

  //  @Test
  public void testDeleteProviderWithAccessKey() {
    Provider p = ModelFactory.awsProvider(customer);
    AccessKey ak = AccessKey.create(p.getUuid(), "access-key-code", new AccessKey.KeyInfo());
    Result result = deleteProvider(p.getUuid());
    assertYBPSuccess(result, "Deleted provider: " + p.getUuid());
    assertEquals(0, AccessKey.getAll(p.getUuid()).size());
    assertNull(Provider.get(p.getUuid()));
    verify(mockAccessManager, times(1))
        .deleteKeyByProvider(p, ak.getKeyCode(), ak.getKeyInfo().deleteRemote);
    assertAuditEntry(1, customer.getUuid());
  }

  //  @Test
  public void testDeleteProviderWithInstanceType() {
    Provider p = ModelFactory.onpremProvider(customer);

    ObjectNode metaData = Json.newObject();
    metaData.put("numCores", 4);
    metaData.put("memSizeGB", 300);
    InstanceType.InstanceTypeDetails instanceTypeDetails = new InstanceType.InstanceTypeDetails();
    instanceTypeDetails.volumeDetailsList = new ArrayList<>();
    InstanceType.VolumeDetails volumeDetails = new InstanceType.VolumeDetails();
    volumeDetails.volumeSizeGB = 20;
    volumeDetails.volumeType = InstanceType.VolumeType.SSD;
    instanceTypeDetails.volumeDetailsList.add(volumeDetails);
    metaData.put("longitude", -119.417932);
    metaData.put("ybImage", "yb-image-1");
    metaData.set("instanceTypeDetails", Json.toJson(instanceTypeDetails));

    InstanceType.createWithMetadata(p.getUuid(), "region-1", metaData);
    AccessKey ak = AccessKey.create(p.getUuid(), "access-key-code", new AccessKey.KeyInfo());
    Result result = deleteProvider(p.getUuid());
    assertYBPSuccess(result, "Deleted provider: " + p.getUuid());

    assertEquals(0, InstanceType.findByProvider(p, mockConfig).size());
    assertNull(Provider.get(p.getUuid()));
  }

  //  @Test
  public void testDeleteProviderWithMultiRegionAccessKey() {
    Provider p = ModelFactory.awsProvider(customer);
    AccessKey ak = AccessKey.create(p.getUuid(), "access-key-code", new AccessKey.KeyInfo());
    Result result = deleteProvider(p.getUuid());
    assertYBPSuccess(result, "Deleted provider: " + p.getUuid());
    assertEquals(0, AccessKey.getAll(p.getUuid()).size());
    assertNull(Provider.get(p.getUuid()));
    verify(mockAccessManager, times(1))
        .deleteKeyByProvider(p, ak.getKeyCode(), ak.getKeyInfo().deleteRemote);
    assertAuditEntry(1, customer.getUuid());
  }

  //  @Test
  public void testDeleteProviderWithInvalidProviderUUID() {
    UUID providerUUID = UUID.randomUUID();
    Result result = assertPlatformException(() -> deleteProvider(providerUUID));
    assertBadRequest(result, "Invalid Provider UUID: " + providerUUID);
    assertAuditEntry(0, customer.getUuid());
  }

  //  @Test
  public void testDeleteProviderWithUniverses() {
    Provider p = ModelFactory.awsProvider(customer);
    Universe universe = createUniverse(customer.getId());
    UniverseDefinitionTaskParams.UserIntent userIntent =
        new UniverseDefinitionTaskParams.UserIntent();
    userIntent.provider = p.getUuid().toString();
    Region r = Region.create(p, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone az1 = AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ 1", "subnet-1");
    AvailabilityZone az2 = AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ 2", "subnet-2");
    userIntent.regionList = new ArrayList<>();
    userIntent.regionList.add(r.getUuid());
    universe =
        Universe.saveDetails(universe.getUniverseUUID(), ApiUtils.mockUniverseUpdater(userIntent));
    Result result = assertPlatformException(() -> deleteProvider(p.getUuid()));
    assertBadRequest(result, "Cannot delete Provider with Universes");
    assertAuditEntry(0, customer.getUuid());
  }

  //  @Test
  public void testDeleteProviderWithoutAccessKey() {
    Provider p = ModelFactory.awsProvider(customer);
    Result result = deleteProvider(p.getUuid());
    assertYBPSuccess(result, "Deleted provider: " + p.getUuid());
    assertNull(Provider.get(p.getUuid()));
    assertAuditEntry(1, customer.getUuid());
  }

  //  @Test
  public void testDeleteProviderWithProvisionScript() {
    Provider p = ModelFactory.newProvider(customer, Common.CloudType.onprem);
    AccessKey.KeyInfo keyInfo = new AccessKey.KeyInfo();
    String scriptFile = createTempFile("provision_instance.py", "some script");
    keyInfo.provisionInstanceScript = scriptFile;
    AccessKey.create(p.getUuid(), "access-key-code", keyInfo);
    Result result = deleteProvider(p.getUuid());
    assertOk(result);
    assertFalse(new File(scriptFile).exists());
    assertAuditEntry(1, customer.getUuid());
  }

  //  @Test
  public void testCreateAwsProviderWithInvalidAWSCredentials() {
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "aws");
    bodyJson.put("name", "aws-Provider");
    bodyJson.put("region", "ap-south-1");
    ObjectNode detailsJson = Json.newObject();
    ObjectNode CloudInfoJson = Json.newObject();
    CloudInfoJson.put("AWS_ACCESS_KEY_ID", "test");
    CloudInfoJson.put("AWS_SECRET_ACCESS_KEY", "secret");
    CloudInfoJson.put("AWS_HOSTED_ZONE_ID", "1234");
    detailsJson.set("cloudInfo", CloudInfoJson);
    bodyJson.set("details", detailsJson);
    CloudAPI mockCloudAPI = mock(CloudAPI.class);
    when(mockCloudAPIFactory.get(any())).thenReturn(mockCloudAPI);
    Result result = assertPlatformException(() -> createProvider(bodyJson));
    assertBadRequest(result, "Invalid AWS Credentials.");
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testCreateAwsProviderWithInvalidDevopsReply() {
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "aws");
    bodyJson.put("name", "aws-Provider");
    ObjectNode detailsJson = Json.newObject();
    ObjectNode CloudInfoJson = Json.newObject();
    ObjectNode awsCloudInfoJson = Json.newObject();
    awsCloudInfoJson.put("HOSTED_ZONE_ID", "1234");
    CloudInfoJson.set("aws", awsCloudInfoJson);
    detailsJson.set("cloudInfo", CloudInfoJson);
    bodyJson.set("details", detailsJson);
    mockDnsManagerListFailure("fail", 0);
    Result result = assertPlatformException(() -> createProvider(bodyJson));
    verify(mockDnsManager, times(1)).listDnsRecord(any(), any());
    assertInternalServerError(result, "Invalid devops API response: ");
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testCreateAwsProviderWithInvalidHostedZoneId() {
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "aws");
    bodyJson.put("name", "aws-Provider");
    ObjectNode detailsJson = Json.newObject();
    ObjectNode CloudInfoJson = Json.newObject();
    ObjectNode awsCloudInfoJson = Json.newObject();
    awsCloudInfoJson.put("HOSTED_ZONE_ID", "1234");
    CloudInfoJson.set("aws", awsCloudInfoJson);
    detailsJson.set("cloudInfo", CloudInfoJson);
    bodyJson.set("details", detailsJson);

    mockDnsManagerListFailure("fail", 1);
    Result result = assertPlatformException(() -> createProvider(bodyJson));
    verify(mockDnsManager, times(1)).listDnsRecord(any(), any());
    assertInternalServerError(result, "Invalid devops API response: ");
    assertAuditEntry(0, customer.getUuid());
  }

  private void mockDnsManagerListSuccess() {
    mockDnsManagerListSuccess("test");
  }

  private void mockDnsManagerListSuccess(String mockDnsName) {
    ShellResponse shellResponse = new ShellResponse();
    shellResponse.message = "{\"name\": \"" + mockDnsName + "\"}";
    shellResponse.code = 0;
    when(mockDnsManager.listDnsRecord(any(), any())).thenReturn(shellResponse);
  }

  private void mockDnsManagerListFailure(String mockFailureMessage, int successCode) {
    ShellResponse shellResponse = new ShellResponse();
    shellResponse.message = "{\"wrong_key\": \"" + mockFailureMessage + "\"}";
    shellResponse.code = successCode;
    when(mockDnsManager.listDnsRecord(any(), any())).thenReturn(shellResponse);
  }

  @Test
  public void testAddRegion() {
    when(mockCommissioner.submit(any(TaskType.class), any(CloudBootstrap.Params.class)))
        .thenReturn(UUID.randomUUID());
    Provider provider = Provider.create(customer.getUuid(), Common.CloudType.aws, "test");
    AccessKey.create(
        provider.getUuid(), AccessKey.getDefaultKeyCode(provider), new AccessKey.KeyInfo());
    String jsonString =
        String.format(
            "{\"code\":\"aws\",\"name\":\"test\",\"regions\":[{\"name\":\"us-west-1\""
                + ",\"code\":\"us-west-1\", \"details\": {\"cloudInfo\": { \"aws\": {"
                + "\"vnetName\":\"vpc-foo\","
                + "\"securityGroupId\":\"sg-foo\" }}}, "
                + "\"zones\":[{\"code\":\"us-west-1a\",\"name\":\"us-west-1a\","
                + "\"secondarySubnet\":\"subnet-foo\",\"subnet\":\"subnet-foo\"}]}],"
                + "\"version\": %d}",
            provider.getVersion());
    when(mockAWSCloudImpl.describeSecurityGroupsOrBadRequest(any(), any()))
        .thenReturn(getTestSecurityGroup(21, 24, "vpc-foo"));
    Result result = editProvider(Json.parse(jsonString), provider.getUuid());
    assertOk(result);
  }

  @Test
  public void testAddExistingRegionFail() {
    when(mockCommissioner.submit(any(TaskType.class), any(CloudBootstrap.Params.class)))
        .thenReturn(UUID.randomUUID());
    ProviderDetails providerDetails = new ProviderDetails();
    Provider provider =
        Provider.create(customer.getUuid(), Common.CloudType.aws, "test", providerDetails);
    AccessKey accessKey =
        AccessKey.create(
            provider.getUuid(), AccessKey.getDefaultKeyCode(provider), new AccessKey.KeyInfo());
    Region region = Region.create(provider, "us-west-1", "us-west-1", "foo");
    region.setVnetName("vpc-foo");
    region.setSecurityGroupId("sg-foo");
    region.save();
    AvailabilityZone.createOrThrow(region, "us-west-1a", "us-west-1a", "subnet-foo", "subnet-foo");
    String jsonString =
        String.format(
            "{\"code\":\"aws\",\"name\":\"test\",\"regions\":[{\"name\":\"us-west-1\""
                + ",\"code\":\"us-west-1\", \"details\": {\"cloudInfo\": { \"aws\": {"
                + "\"vnetName\":\"vpc-foo\", \"ybImage\":\"foo\", "
                + "\"securityGroupId\":\"sg-foo\" }}}, "
                + "\"zones\":[{\"code\":\"us-west-1a\",\"name\":\"us-west-1a\","
                + "\"secondarySubnet\":\"subnet-foo\",\"subnet\":\"subnet-foo\"}]}],"
                + "\"version\": %d}",
            provider.getVersion());
    JsonNode providerJson = Json.parse(jsonString);
    ArrayNode allAccessKeys = Json.newArray();
    allAccessKeys.add(Json.toJson(accessKey));
    ((ObjectNode) providerJson).set("allAccessKeys", allAccessKeys);

    Image image = new Image();
    image.setArchitecture("x86_64");
    image.setRootDeviceType("ebs");
    image.setPlatformDetails("linux/UNIX");
    when(mockAWSCloudImpl.describeImageOrBadRequest(any(), any(), any())).thenReturn(image);
    when(mockAWSCloudImpl.describeSecurityGroupsOrBadRequest(any(), any()))
        .thenReturn(getTestSecurityGroup(21, 24, "vpc-foo"));
    Result result =
        assertPlatformException(() -> editProvider(providerJson, provider.getUuid(), false));
    assertBadRequest(result, "No changes to be made for provider type: aws");
  }

  @Test
  public void testIncorrectFieldsForAddRegionFail() {
    when(mockCommissioner.submit(any(TaskType.class), any(CloudBootstrap.Params.class)))
        .thenReturn(UUID.randomUUID());
    Provider provider = Provider.create(customer.getUuid(), Common.CloudType.aws, "test");
    AccessKey.create(
        provider.getUuid(), AccessKey.getDefaultKeyCode(provider), new AccessKey.KeyInfo());
    String jsonString =
        "{\"code\":\"aws\",\"name\":\"test\",\"regions\":[{\"name\":\"us-west-1\""
            + ",\"code\":\"us-west-1\", \"details\": {\"cloudInfo\": { \"aws\": {"
            + "\"securityGroupId\":\"sg-foo\" }}}, "
            + "\"zones\":[{\"code\":\"us-west-1a\",\"name\":\"us-west-1a\","
            + "\"secondarySubnet\":\"subnet-foo\",\"subnet\":\"subnet-foo\"}]}]}";

    Result result =
        assertPlatformException(
            () -> editProvider(Json.parse(jsonString), provider.getUuid(), false));
    assertBadRequest(result, "Required field vnet name (VPC ID) for region: us-west-1");
  }

  @Test
  public void testCreateAWSProviderWithInvalidAccessParams() {
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "aws");
    bodyJson.put("name", "aws-Provider");
    ObjectNode detailsJson = Json.newObject();
    ObjectNode cloudInfoJson = Json.newObject();
    ObjectNode awsCloudInfoJson = Json.newObject();
    cloudInfoJson.set("aws", awsCloudInfoJson);
    detailsJson.set("cloudInfo", cloudInfoJson);
    bodyJson.set("details", detailsJson);
    ArrayNode regionsList = Json.newArray();
    ObjectNode region = Json.newObject();
    region.put("code", "us-west-2");
    regionsList.add(region);
    bodyJson.set("regions", regionsList);
    when(mockAWSCloudImpl.checkKeysExists(any())).thenReturn(false, true);
    when(mockAWSCloudImpl.getStsClientOrBadRequest(any(), any()))
        .thenThrow(
            new PlatformServiceException(
                BAD_REQUEST, "AWS access and secret keys validation failed: Invalid role"),
            new PlatformServiceException(
                BAD_REQUEST, "AWS access and secret keys validation failed: Not found"));
    Result result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result, "data.IAM", "AWS access and secret keys validation failed: Invalid role");
    assertAuditEntry(0, customer.getUuid());
    awsCloudInfoJson.put("AWS_SECRET_ACCESS_KEY", "secret_value");
    cloudInfoJson.set("aws", awsCloudInfoJson);
    detailsJson.set("cloudInfo", cloudInfoJson);
    bodyJson.set("details", detailsJson);
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result, "data.KEYS", "Please provide both access key and its secret");
    assertAuditEntry(0, customer.getUuid());
    awsCloudInfoJson.put("AWS_ACCESS_KEY_ID", "key_value");
    cloudInfoJson.set("aws", awsCloudInfoJson);
    detailsJson.set("cloudInfo", cloudInfoJson);
    bodyJson.set("details", detailsJson);
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result, "data.KEYS", "AWS access and secret keys validation failed: Not found");
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testCreateAWSProviderWithInvalidHostedZone() {
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "aws");
    bodyJson.put("name", "aws-Provider");
    ObjectNode detailsJson = Json.newObject();
    ObjectNode cloudInfoJson = Json.newObject();
    ObjectNode awsCloudInfoJson = Json.newObject();
    awsCloudInfoJson.put("HOSTED_ZONE_ID", "hosted_zone_id");
    cloudInfoJson.set("aws", awsCloudInfoJson);
    detailsJson.set("cloudInfo", cloudInfoJson);
    bodyJson.set("details", detailsJson);
    ArrayNode regionsList = Json.newArray();
    ObjectNode region = Json.newObject();
    region.put("code", "us-west-2");
    regionsList.add(region);
    bodyJson.set("regions", regionsList);
    when(mockAWSCloudImpl.getStsClientOrBadRequest(any(), any()))
        .thenReturn(new GetCallerIdentityResult());
    when(mockAWSCloudImpl.getHostedZoneOrBadRequest(any(), any(), anyString()))
        .thenThrow(
            new PlatformServiceException(BAD_REQUEST, "Hosted Zone validation failed: Invalid ID"));
    Result result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result, "data.HOSTED_ZONE", "Hosted Zone validation failed: Invalid ID");
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testCreateAWSProviderWithInvalidSSHKey() {
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "aws");
    bodyJson.put("name", "aws-Provider");
    ObjectNode detailsJson = Json.newObject();
    ObjectNode cloudInfoJson = Json.newObject();
    ObjectNode awsCloudInfoJson = Json.newObject();
    cloudInfoJson.set("aws", awsCloudInfoJson);
    detailsJson.set("cloudInfo", cloudInfoJson);
    bodyJson.set("details", detailsJson);
    bodyJson.put("sshPrivateKeyContent", "key_content");
    bodyJson.put("keyPairName", "test1");
    when(mockAWSCloudImpl.getStsClientOrBadRequest(any(), any()))
        .thenReturn(new GetCallerIdentityResult());
    when(mockAWSCloudImpl.getPrivateKeyAlgoOrBadRequest(anyString())).thenReturn("DSA");
    Result result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result, "data.SSH_PRIVATE_KEY_CONTENT", "Please provide a valid RSA key");
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testCreateAWSProviderWithRegionDetails() {
    // create provider body
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "aws");
    bodyJson.put("name", "aws-Provider");
    ObjectNode detailsJson = Json.newObject();
    ObjectNode cloudInfoJson = Json.newObject();
    ObjectNode awsCloudInfoJson = Json.newObject();
    cloudInfoJson.set("aws", awsCloudInfoJson);
    detailsJson.set("cloudInfo", cloudInfoJson);
    bodyJson.set("details", detailsJson);
    when(mockAWSCloudImpl.getStsClientOrBadRequest(any(), any()))
        .thenReturn(new GetCallerIdentityResult());
    ObjectNode regionAWSCloudInfo = Json.newObject();
    regionAWSCloudInfo.put("ybImage", "image_id");
    regionAWSCloudInfo.put("vnetName", "vpc_id");
    regionAWSCloudInfo.put("securityGroupId", "sg_id");
    ObjectNode regionCloudInfo = Json.newObject();
    regionCloudInfo.set("aws", regionAWSCloudInfo);
    ObjectNode regionDetails = Json.newObject();
    regionDetails.set("cloudInfo", regionCloudInfo);
    ObjectNode region = Json.newObject();
    region.set("details", regionDetails);
    ObjectNode az1 =
        Json.newObject()
            .put("name", "us-west-2a")
            .put("code", "us-west-2a")
            .put("subnet", "subnet-a");
    ObjectNode az2 =
        Json.newObject()
            .put("name", "us-west-2b")
            .put("code", "us-west-2b")
            .put("subnet", "subnet-b");
    ArrayNode zonesList = Json.newArray();
    zonesList.add(az1).add(az2);
    region.put("zones", zonesList);
    region.put("code", "us-west-2");
    ArrayNode regionsList = Json.newArray();
    regionsList.add(region);
    bodyJson.set("regions", regionsList);
    Image image = new Image();
    image.setArchitecture("random_arch");
    image.setRootDeviceType("random_device_type");
    image.setPlatformDetails("windows");
    when(mockAWSCloudImpl.describeImageOrBadRequest(any(), any(), anyString()))
        .thenThrow(
            new PlatformServiceException(BAD_REQUEST, "AMI details extraction failed: Not found"))
        .thenReturn(image);
    // Test image exists or not
    Result result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result, "data.REGION.us-west-2.IMAGE", "AMI details extraction failed: Not found");
    // Test image arch
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result,
        "data.REGION.us-west-2.IMAGE",
        "random_arch arch on image image_id is not supported");
    assertAuditEntry(0, customer.getUuid());
    image.setArchitecture("x86_64");
    when(mockAWSCloudImpl.describeImageOrBadRequest(any(), any(), anyString())).thenReturn(image);
    // Test image arch type
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result,
        "data.REGION.us-west-2.IMAGE",
        "random_device_type root device type on image image_id is not supported");
    image.setRootDeviceType("ebs");
    when(mockAWSCloudImpl.describeImageOrBadRequest(any(), any(), anyString())).thenReturn(image);
    // Test image platform details
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result,
        "data.REGION.us-west-2.IMAGE",
        "windows platform on image image_id is not supported");
    image.setPlatformDetails("linux/UNIX");
    when(mockAWSCloudImpl.describeImageOrBadRequest(any(), any(), anyString())).thenReturn(image);
    // Test VPC exists or not
    when(mockAWSCloudImpl.describeVpcOrBadRequest(any(), any()))
        .thenThrow(
            new PlatformServiceException(
                BAD_REQUEST, "Vpc details extraction failed: Invalid VPC ID"))
        .thenReturn(new Vpc());
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result, "data.REGION.us-west-2.VPC", "Vpc details extraction failed: Invalid VPC ID");
    when(mockAWSCloudImpl.describeSecurityGroupsOrBadRequest(any(), any()))
        .thenThrow(
            new PlatformServiceException(
                BAD_REQUEST, "Security group extraction failed: Invalid SG ID"))
        .thenReturn(getTestSecurityGroup(21, 24, null))
        .thenReturn(getTestSecurityGroup(21, 24, "vpc_id_new"))
        .thenReturn(getTestSecurityGroup(24, 24, "vpc_id"));
    // Test SG exists or not
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result,
        "data.REGION.us-west-2.SECURITY_GROUP",
        "Security group extraction failed: Invalid SG ID");
    // Test Vpc association
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result, "data.REGION.us-west-2.SECURITY_GROUP", "No vpc is attached to SG: sg_id");
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result, "data.REGION.us-west-2.SECURITY_GROUP", "sg_id is not attached to vpc: vpc_id");
    // Test SG ports
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result, "data.REGION.us-west-2.SECURITY_GROUP", "22 is not open on security group sg_id");
    when(mockAWSCloudImpl.describeSubnetsOrBadRequest(any(), any()))
        .thenThrow(
            new PlatformServiceException(
                BAD_REQUEST, "Subnet details extraction failed: Invalid Id"))
        .thenReturn(
            Arrays.asList(
                getTestSubnet("0.0.0.0/24", "subnet-a", "vpc_id", "us-west-2b"),
                getTestSubnet("0.0.0.0/24", "subnet-a", "vpc_id", "us-west-2c")))
        .thenReturn(
            Collections.singletonList(
                getTestSubnet("0.0.0.0/24", "subnet-a", "vpc_id_incorrect", "us-west-2a")));
    when(mockAWSCloudImpl.describeSecurityGroupsOrBadRequest(any(), any()))
        .thenReturn(getTestSecurityGroup(21, 24, "vpc_id"));
    // Test subnet exists or not
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result, "data.REGION.us-west-2.SUBNETS", "Subnet details extraction failed: Invalid Id");
    // Test Subnet code
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result, "data.REGION.us-west-2.SUBNETS", "Invalid AZ code for subnet: subnet-a");
    // Test subnet vpc
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result, "data.REGION.us-west-2.SUBNETS", "subnet-a is not associated with vpc_id");
    when(mockAWSCloudImpl.describeSubnetsOrBadRequest(any(), any()))
        .thenReturn(
            Arrays.asList(
                getTestSubnet("0.0.0.0/24", "subnet-a", "vpc_id", "us-west-2a"),
                getTestSubnet("0.0.0.0/24", "subnet-a", "vpc_id", "us-west-2a")));
    // Test subnet cidr blocks
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result,
        "data.REGION.us-west-2.SUBNETS",
        "Please provide non-overlapping CIDR blocks subnets");
    when(mockAWSCloudImpl.describeSubnetsOrBadRequest(any(), any()))
        .thenReturn(
            Arrays.asList(
                getTestSubnet("0.0.0.0/24", "subnet-a", "vpc_id", "us-west-2a"),
                getTestSubnet("0.0.0.0/25", "subnet-a", "vpc_id", "us-west-2a")));
    when(mockAWSCloudImpl.dryRunDescribeInstanceOrBadRequest(any(), anyString()))
        .thenThrow(
            new PlatformServiceException(
                BAD_REQUEST, "Dry run of AWS DescribeInstances failed: Invalid region"))
        .thenReturn(false, true);
    // Test failed dry run
    result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result,
        "data.REGION.us-west-2.DRY_RUN",
        "Dry run of AWS DescribeInstances failed: Invalid region");
    when(mockCommissioner.submit(any(TaskType.class), any(CloudBootstrap.Params.class)))
        .thenReturn(UUID.randomUUID());
    // Test validation pass
    result = createProvider(bodyJson);
    assertOk(result);
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testModifyGCPProviderCredentials() {
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "gcp");
    bodyJson.put("name", "gcp-Provider");
    ObjectNode detailsJson = Json.newObject();
    ObjectNode cloudInfoJson = Json.newObject();
    ObjectNode gcpCloudInfo = Json.newObject();
    ObjectNode gcpCredentials = Json.newObject();
    gcpCredentials.put("GCE_EMAIL", "test@yugabyte.com");
    gcpCredentials.put("private_key", "Private key");

    gcpCloudInfo.put("useHostCredentials", true);
    gcpCloudInfo.put("destVpcId", "test");
    gcpCloudInfo.put("gceProject", "yugabyte");
    gcpCloudInfo.put("gceApplicationCredentials", gcpCredentials);
    cloudInfoJson.put("gcp", gcpCloudInfo);
    detailsJson.set("cloudInfo", cloudInfoJson);
    bodyJson.set("details", detailsJson);

    when(mockCommissioner.submit(any(TaskType.class), any(CloudBootstrap.Params.class)))
        .thenReturn(UUID.randomUUID());

    Result result = createProvider(bodyJson);
    assertOk(result);
    YBPTask ybpTask = Json.fromJson(Json.parse(contentAsString(result)), YBPTask.class);
    Provider gcpProvider = Provider.getOrBadRequest(ybpTask.resourceUUID);

    gcpCredentials.put("client_id", "Client ID");
    gcpProvider.getDetails().getCloudInfo().getGcp().setGceApplicationCredentials(gcpCredentials);
    result = editProvider(Json.toJson(gcpProvider), gcpProvider.getUuid());
    assertOk(result);

    gcpProvider = Provider.getOrBadRequest(gcpProvider.getUuid());
    assertEquals(
        "Client ID",
        ((ObjectNode)
                gcpProvider.getDetails().getCloudInfo().getGcp().getGceApplicationCredentials())
            .get("client_id")
            .textValue());
  }

  @Test
  public void testK8sProviderEditDetails() {
    JsonNode k8sRequestBody = getK8sRequestBody();

    Result result = createProvider(k8sRequestBody);
    assertOk(result);
    YBPTask ybpTask = Json.fromJson(Json.parse(contentAsString(result)), YBPTask.class);
    assertNotNull(ybpTask.resourceUUID);

    Provider p = Provider.getOrBadRequest(ybpTask.resourceUUID);
    p.getDetails().getCloudInfo().getKubernetes().setKubernetesProvider("GKE-2");

    result = editProvider(Json.toJson(p), p.getUuid());
    assertOk(result);
    p = Provider.getOrBadRequest(ybpTask.resourceUUID);
    assertEquals(p.getDetails().getCloudInfo().getKubernetes().getKubernetesProvider(), "GKE-2");
  }

  @Test
  public void testK8sProviderEditAddZone() {
    JsonNode k8sRequestBody = getK8sRequestBody();

    Result result = createProvider(k8sRequestBody);
    assertOk(result);
    YBPTask ybpTask = Json.fromJson(Json.parse(contentAsString(result)), YBPTask.class);
    assertNotNull(ybpTask.resourceUUID);

    Result providerRes = getProvider(ybpTask.resourceUUID);
    ObjectNode providerJson = (ObjectNode) Json.parse(contentAsString(providerRes));
    JsonNode region = providerJson.get("regions").get(0);
    ArrayNode zones = (ArrayNode) region.get("zones");

    ObjectNode zone = Json.newObject();
    zone.put("name", "Zone 2");
    zone.put("code", "zone-2");
    zones.add(zone);
    ((ObjectNode) region).set("zones", zones);
    ArrayNode regionsNode = Json.newArray();
    regionsNode.add(region);
    providerJson.set("regions", regionsNode);
    Provider p = Provider.getOrBadRequest(ybpTask.resourceUUID);
    assertEquals(1, p.getRegions().get(0).getZones().size());

    result = editProvider(providerJson, ybpTask.resourceUUID);
    assertOk(result);
    p = Provider.getOrBadRequest(ybpTask.resourceUUID);
    assertEquals(2, p.getRegions().get(0).getZones().size());
    assertEquals("zone-2", p.getRegions().get(0).getZones().get(1).getCode());
  }

  @Test
  public void testK8sProviderEditModifyZone() {
    JsonNode k8sRequestBody = getK8sRequestBody();

    Result result = createProvider(k8sRequestBody);
    assertOk(result);
    YBPTask ybpTask = Json.fromJson(Json.parse(contentAsString(result)), YBPTask.class);
    assertNotNull(ybpTask.resourceUUID);

    Provider p = Provider.getOrBadRequest(ybpTask.resourceUUID);
    assertEquals(1, p.getRegions().get(0).getZones().size());
    AvailabilityZone az = p.getRegions().get(0).getZones().get(0);
    az.getDetails().getCloudInfo().getKubernetes().setKubernetesStorageClass("Storage class");

    result = editProvider(Json.toJson(p), ybpTask.resourceUUID);
    assertOk(result);
    p = Provider.getOrBadRequest(p.getUuid());

    assertEquals(1, p.getRegions().get(0).getZones().size());
    assertEquals(
        "Storage class",
        p.getRegions()
            .get(0)
            .getZones()
            .get(0)
            .getDetails()
            .getCloudInfo()
            .getKubernetes()
            .getKubernetesStorageClass());
  }

  @Test
  public void testK8sProviderDeleteZone() {
    JsonNode k8sRequestBody = getK8sRequestBody();

    Result result = createProvider(k8sRequestBody);
    assertOk(result);
    YBPTask ybpTask = Json.fromJson(Json.parse(contentAsString(result)), YBPTask.class);
    assertNotNull(ybpTask.resourceUUID);

    Provider p = Provider.getOrBadRequest(ybpTask.resourceUUID);
    assertEquals(1, p.getRegions().get(0).getZones().size());
    AvailabilityZone az = p.getRegions().get(0).getZones().get(0);
    az.setActive(false);

    result = editProvider(Json.toJson(p), ybpTask.resourceUUID);
    assertOk(result);
    List<AvailabilityZone> azs = AvailabilityZone.getAZsForRegion(p.getRegions().get(0).getUuid());

    assertEquals(0, azs.size());
  }

  @Test
  public void testK8sProviderAddRegion() {
    JsonNode k8sRequestBody = getK8sRequestBody();

    Result result = createProvider(k8sRequestBody);
    assertOk(result);
    YBPTask ybpTask = Json.fromJson(Json.parse(contentAsString(result)), YBPTask.class);
    assertNotNull(ybpTask.resourceUUID);

    Result providerRes = getProvider(ybpTask.resourceUUID);
    ObjectNode providerJson = (ObjectNode) Json.parse(contentAsString(providerRes));
    ArrayNode regions = (ArrayNode) providerJson.get("regions");

    ObjectNode region = Json.newObject();
    region.put("name", "Region 2");
    region.put("code", "region-2");

    ArrayNode zones = Json.newArray();
    ObjectNode zone = Json.newObject();
    zone.put("name", "Zone 2");
    zone.put("code", "zone-2");
    zones.add(zone);
    region.set("zones", zones);

    regions.add(region);
    providerJson.set("regions", regions);

    result = editProvider(providerJson, ybpTask.resourceUUID);
    assertOk(result);
    Provider p = Provider.getOrBadRequest(ybpTask.resourceUUID);

    assertEquals(2, p.getRegions().size());
    assertEquals("region-2", p.getRegions().get(1).getCode());
    assertEquals("zone-2", p.getRegions().get(1).getZones().get(0).getCode());
  }

  @Test
  public void testK8sProviderEditModifyRegion() {
    JsonNode k8sRequestBody = getK8sRequestBody();

    Result result = createProvider(k8sRequestBody);
    assertOk(result);
    YBPTask ybpTask = Json.fromJson(Json.parse(contentAsString(result)), YBPTask.class);
    assertNotNull(ybpTask.resourceUUID);

    Provider p = Provider.getOrBadRequest(ybpTask.resourceUUID);
    Region region = p.getRegions().get(0);
    region.setDetails(new RegionDetails());
    region.getDetails().setCloudInfo(new RegionDetails.RegionCloudInfo());
    region.getDetails().getCloudInfo().setKubernetes(new KubernetesRegionInfo());
    region
        .getDetails()
        .getCloudInfo()
        .getKubernetes()
        .setKubernetesStorageClass("Updating storage class");

    result = editProvider(Json.toJson(p), ybpTask.resourceUUID);
    assertOk(result);

    Result providerRes = getProvider(p.getUuid());
    JsonNode bodyJson = Json.parse(contentAsString(providerRes));
    p = Json.fromJson(bodyJson, Provider.class);
    assertEquals(1, p.getRegions().size());
    assertEquals(
        "Updating storage class",
        p.getRegions()
            .get(0)
            .getDetails()
            .getCloudInfo()
            .getKubernetes()
            .getKubernetesStorageClass());
  }

  @Test
  public void testK8sProviderConfigAtMultipleLevels() {
    JsonNode k8sRequestBody = getK8sRequestBody();

    Result result = createProvider(k8sRequestBody);
    assertOk(result);
    YBPTask ybpTask = Json.fromJson(Json.parse(contentAsString(result)), YBPTask.class);
    assertNotNull(ybpTask.resourceUUID);

    Provider p = Provider.getOrBadRequest(ybpTask.resourceUUID);
    p.getDetails().getCloudInfo().getKubernetes().setKubeConfigName("Test-1");
    p.getRegions()
        .get(0)
        .getZones()
        .get(0)
        .getDetails()
        .getCloudInfo()
        .getKubernetes()
        .setKubeConfigName("Test-2");

    result = assertPlatformException(() -> editProvider(Json.toJson(p), ybpTask.resourceUUID));
    assertBadRequest(result, "Kubeconfig can't be at two levels");
  }

  @Test
  public void testK8sProviderConfigEditAtZoneLevel() {
    JsonNode k8sRequestBody = getK8sRequestBody();

    Result result = createProvider(k8sRequestBody);
    assertOk(result);
    YBPTask ybpTask = Json.fromJson(Json.parse(contentAsString(result)), YBPTask.class);
    assertNotNull(ybpTask.resourceUUID);

    Provider p = Provider.getOrBadRequest(ybpTask.resourceUUID);
    p.getRegions()
        .get(0)
        .getZones()
        .get(0)
        .getDetails()
        .getCloudInfo()
        .getKubernetes()
        .setKubeConfigName("Test-2");

    result = editProvider(Json.toJson(p), ybpTask.resourceUUID);
    assertOk(result);
    Result providerRes = getProvider(p.getUuid());
    JsonNode bodyJson = Json.parse(contentAsString(providerRes));
    p = Json.fromJson(bodyJson, Provider.class);

    assertNull(
        p.getRegions()
            .get(0)
            .getZones()
            .get(0)
            .getDetails()
            .getCloudInfo()
            .getKubernetes()
            .getKubeConfigName());

    assertNotNull(
        p.getRegions()
            .get(0)
            .getZones()
            .get(0)
            .getDetails()
            .getCloudInfo()
            .getKubernetes()
            .getKubeConfig());
  }

  @Test
  public void testK8sProviderConfigEditAtProviderLevel() {
    JsonNode k8sRequestBody = getK8sRequestBody();

    Result result = createProvider(k8sRequestBody);
    assertOk(result);
    YBPTask ybpTask = Json.fromJson(Json.parse(contentAsString(result)), YBPTask.class);
    assertNotNull(ybpTask.resourceUUID);

    Provider p = Provider.getOrBadRequest(ybpTask.resourceUUID);
    KubernetesRegionInfo azConfig = CloudInfoInterface.get(p.getRegions().get(0).getZones().get(0));
    assertEquals("", azConfig.getKubeConfig());
    p.getDetails().getCloudInfo().getKubernetes().setKubernetesStorageClass("Test-2");

    result = editProvider(Json.toJson(p), ybpTask.resourceUUID);
    assertOk(result);
    p.refresh();
    assertNotNull(p.getDetails().getCloudInfo().getKubernetes().getKubernetesStorageClass());
  }

  @Test
  public void testOnPremProviderNameValidation() {
    // create provider body
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "onprem");
    bodyJson.put("name", "onprem-Provider");
    ObjectNode region = Json.newObject();
    ObjectNode az1 = Json.newObject().put("name", "us-west-2a").put("code", "us-west-2a");
    ObjectNode az2 = Json.newObject().put("name", "us-west-2b").put("code", "us-west-2b");
    ArrayNode zonesList = Json.newArray();
    zonesList.add(az1).add(az2);
    region.put("zones", zonesList);
    region.put("code", "us-west-2");
    ArrayNode regionsList = Json.newArray();
    regionsList.add(region);
    bodyJson.set("regions", regionsList);

    when(mockCommissioner.submit(any(TaskType.class), any(CloudBootstrap.Params.class)))
        .thenReturn(UUID.randomUUID());
    // Test validation pass
    Result result = createProvider(bodyJson);
    assertOk(result);
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testOnPremProviderNameValidationFail() {
    // create provider body
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "onprem");
    bodyJson.put("name", "onprem-Provider");
    ObjectNode region = Json.newObject();
    ObjectNode az1 = Json.newObject().put("name", "us-west&s2a").put("code", "us-west-2a");
    ObjectNode az2 = Json.newObject().put("name", "us-westS*D2b").put("code", "us-west-2b");
    ArrayNode zonesList = Json.newArray();
    zonesList.add(az1).add(az2);
    region.put("zones", zonesList);
    region.put("code", "us-west-2");
    ArrayNode regionsList = Json.newArray();
    regionsList.add(region);
    bodyJson.set("regions", regionsList);

    Result result = assertPlatformException(() -> createProvider(bodyJson));
    assertEquals(BAD_REQUEST, result.status());
    assertBadRequestValidationResult(
        result,
        "data.ZONE.0",
        "Zone name cannot contain any special characters except '-' and '_'.");
  }

  @Test
  public void testGCPProviderCreateWithImageBundle() {
    when(mockCloudQueryHelper.getCurrentHostInfo(eq(CloudType.gcp)))
        .thenReturn(Json.newObject().put("network", "234234").put("host_project", "PROJ"));
    Provider provider = buildProviderReq("gcp", "Google");
    Region region = new Region();
    region.setName("region1");
    region.setProvider(provider);
    region.setCode("region1");
    provider.setRegions(ImmutableList.of(region));
    provider = createProviderTest(provider, ImmutableList.of(), UUID.randomUUID());

    ImageBundleDetails details = new ImageBundleDetails();
    details.setGlobalYbImage("Global-AMI-Image");
    details.setArch(Architecture.x86_64);
    ImageBundle ib1 = new ImageBundle();
    ib1.setName("ImageBundle-1");
    ib1.setProvider(provider);
    ib1.setUseAsDefault(true);
    ib1.save();

    ImageBundle bundle = ImageBundle.getDefaultForProvider(provider.getUuid());
    assertEquals(ib1.getUuid(), bundle.getUuid());
  }

  private void assertBadRequestValidationResult(Result result, String errorCause, String errrMsg) {
    JsonNode json = Json.parse(contentAsString(result));
    assertEquals("providerValidation", json.get("error").get("errorSource").get(0).asText());
    assertEquals(errrMsg, json.get("error").get(errorCause).get(0).asText());
  }

  @Test
  public void testCreateInvalidAWSProviderIgnoreValidation() {
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "aws");
    bodyJson.put("name", "aws-Provider");
    ObjectNode detailsJson = Json.newObject();
    ObjectNode cloudInfoJson = Json.newObject();
    ObjectNode awsCloudInfoJson = Json.newObject();
    awsCloudInfoJson.put("HOSTED_ZONE_ID", "hosted_zone_id");
    cloudInfoJson.set("aws", awsCloudInfoJson);
    detailsJson.set("cloudInfo", cloudInfoJson);
    bodyJson.set("details", detailsJson);
    ArrayNode regionsList = Json.newArray();
    ObjectNode region = Json.newObject();
    region.put("code", "us-west-2");
    regionsList.add(region);
    bodyJson.set("regions", regionsList);
    when(mockAWSCloudImpl.getStsClientOrBadRequest(any(), any()))
        .thenReturn(new GetCallerIdentityResult());
    when(mockAWSCloudImpl.getHostedZoneOrBadRequest(any(), any(), anyString()))
        .thenThrow(
            new PlatformServiceException(BAD_REQUEST, "Hosted Zone validation failed: Invalid ID"));
    mockDnsManagerListSuccess();
    when(mockCommissioner.submit(any(TaskType.class), any(CloudBootstrap.Params.class)))
        .thenReturn(UUID.randomUUID());
    Result result = createProvider(bodyJson, true);
    assertOk(result);
    YBPTask ybpTask = Json.fromJson(Json.parse(contentAsString(result)), YBPTask.class);
    Provider createdProvider = Provider.get(customer.getUuid(), ybpTask.resourceUUID);
    assertEquals(Provider.UsabilityState.UPDATING, createdProvider.getUsabilityState());
    JsonNode errorNode =
        Json.parse(
            "{\"errorSource\":[\"providerValidation\"],"
                + "\"data.HOSTED_ZONE\":[\"Hosted Zone validation failed: Invalid ID\"]}");
    assertEquals(errorNode, createdProvider.getLastValidationErrors().get("error"));
  }

  @Test
  public void testAddRegionIgnoreValidationErrorToOK() {
    when(mockCommissioner.submit(any(TaskType.class), any(CloudBootstrap.Params.class)))
        .thenReturn(UUID.randomUUID());
    Provider provider = Provider.create(customer.getUuid(), Common.CloudType.aws, "test");
    provider.setLastValidationErrors(Json.newObject().put("error", "something wrong"));
    provider.setUsabilityState(Provider.UsabilityState.ERROR);
    provider.save();
    AccessKey.create(
        provider.getUuid(), AccessKey.getDefaultKeyCode(provider), new AccessKey.KeyInfo());
    String jsonString =
        String.format(
            "{\"code\":\"aws\",\"name\":\"test\",\"regions\":[{\"name\":\"us-west-1\""
                + ",\"code\":\"us-west-1\", \"details\": {\"cloudInfo\": { \"aws\": {"
                + "\"vnetName\":\"vpc-foo\","
                + "\"securityGroupId\":\"sg-foo\" }}}, "
                + "\"zones\":[{\"code\":\"us-west-1a\",\"name\":\"us-west-1a\","
                + "\"secondarySubnet\":\"subnet-foo\",\"subnet\":\"subnet-foo\"}]}],"
                + "\"version\": %d}",
            provider.getVersion());
    when(mockAWSCloudImpl.describeSecurityGroupsOrBadRequest(any(), any()))
        .thenReturn(getTestSecurityGroup(21, 24, "vpc-foo"));
    Result result = editProvider(Json.parse(jsonString), provider.getUuid(), true);
    assertOk(result);
    provider = Provider.getOrBadRequest(provider.getUuid());
    assertNull(provider.getLastValidationErrors());
    assertEquals(Provider.UsabilityState.READY, provider.getUsabilityState());
  }

  @Test
  public void testAddRegionIgnoreValidationOKToError() {
    when(mockCommissioner.submit(any(TaskType.class), any(CloudBootstrap.Params.class)))
        .thenReturn(UUID.randomUUID());
    Provider provider = Provider.create(customer.getUuid(), Common.CloudType.aws, "test");
    assertEquals(Provider.UsabilityState.READY, provider.getUsabilityState());
    assertNull(provider.getLastValidationErrors());
    provider.save();
    AccessKey.create(
        provider.getUuid(), AccessKey.getDefaultKeyCode(provider), new AccessKey.KeyInfo());
    String jsonString =
        String.format(
            "{\"code\":\"aws\",\"name\":\"test\",\"regions\":[{\"name\":\"us-west-1\""
                + ",\"code\":\"us-west-1\", \"details\": {\"cloudInfo\": { \"aws\": {"
                + "\"vnetName\":\"vpc-foo\","
                + "\"securityGroupId\":\"sg-foo\" }}}, "
                + "\"zones\":[{\"code\":\"us-west-1a\",\"name\":\"us-west-1a\","
                + "\"secondarySubnet\":\"subnet-foo\",\"subnet\":\"subnet-foo\"}]}],"
                + "\"version\": %d}",
            provider.getVersion());
    when(mockAWSCloudImpl.describeSecurityGroupsOrBadRequest(any(), any()))
        .thenThrow(new PlatformServiceException(BAD_REQUEST, "Something wrong"));
    Result result = editProvider(Json.parse(jsonString), provider.getUuid(), true, true);
    assertOk(result);
    provider = Provider.getOrBadRequest(provider.getUuid());
    assertNotNull(provider.getLastValidationErrors());
    assertEquals(
        Json.parse("[\"Something wrong\"]"),
        provider
            .getLastValidationErrors()
            .get("error")
            .get("data.REGION.us-west-1.SECURITY_GROUP"));
    assertEquals(Provider.UsabilityState.READY, provider.getUsabilityState());
  }

  @Test
  public void testAddYBAManagedAccessKeysProviderEdit() {
    Provider p = Provider.create(customer.getUuid(), Common.CloudType.aws, "test");
    Region r = Region.create(p, "us-west-2", "us-west-2", "yb-image");
    when(mockAccessManager.addKey(
            any(),
            anyString(),
            nullable(File.class),
            anyString(),
            anyInt(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            anyList(),
            anyBoolean()))
        .thenReturn(AccessKey.create(p.getUuid(), "key-code-1", new AccessKey.KeyInfo()));
    AccessKey ak = AccessKey.create(p.getUuid(), "access-key-code", new AccessKey.KeyInfo());

    Result providerRes = getProvider(p.getUuid());
    JsonNode bodyJson = Json.parse(contentAsString(providerRes));
    Provider provider = Json.fromJson(bodyJson, Provider.class);

    provider.setAllAccessKeys(null);
    Result result = editProvider(Json.toJson(provider), provider.getUuid(), false, true);
    assertOk(result);

    provider = Provider.getOrBadRequest(p.getUuid());
    assertEquals(2, provider.getAllAccessKeys().size());
    provider
        .getAllAccessKeys()
        .forEach(
            key -> {
              if (!(key.getKeyCode().equals("key-code-1")
                  || key.getKeyCode().equals("access-key-code"))) {
                fail();
              }
            });
  }

  @Test
  public void testAddSelfManagedAccessKeysProviderEdit() {
    Provider p = Provider.create(customer.getUuid(), Common.CloudType.aws, "test");
    Region r = Region.create(p, "us-west-2", "us-west-2", "yb-image");
    when(mockAccessManager.saveAndAddKey(
            any(),
            anyString(),
            anyString(),
            any(),
            anyString(),
            anyInt(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            anyList(),
            anyBoolean(),
            anyBoolean()))
        .thenReturn(AccessKey.create(p.getUuid(), "my-key", new AccessKey.KeyInfo()));

    Result providerRes = getProvider(p.getUuid());
    JsonNode bodyJson = Json.parse(contentAsString(providerRes));
    Provider provider = Json.fromJson(bodyJson, Provider.class);

    ArrayNode accessKeyList = Json.newArray();
    ObjectNode key = Json.newObject();
    ObjectNode keyInfo = Json.newObject();
    keyInfo.put("keyPairName", "my-key");
    keyInfo.put("sshPrivateKeyContent", "Test key content");
    key.set("keyInfo", keyInfo);
    accessKeyList.add(key);

    ((ObjectNode) bodyJson).set("allAccessKeys", accessKeyList);
    Result result = editProvider(bodyJson, provider.getUuid(), false, true);
    assertOk(result);

    p = Provider.getOrBadRequest(p.getUuid());
    assertEquals(1, p.getAllAccessKeys().size());
    provider
        .getAllAccessKeys()
        .forEach(
            aKey -> {
              if (!(aKey.getKeyCode().equals("my-key"))) {
                fail();
              }
            });
  }

  private SecurityGroup getTestSecurityGroup(int fromPort, int toPort, String vpcId) {
    SecurityGroup sg = new SecurityGroup();
    IpPermission ipPermission = new IpPermission();
    ipPermission.setFromPort(fromPort);
    ipPermission.setToPort(toPort);
    sg.setIpPermissions(Collections.singletonList(ipPermission));
    sg.setVpcId(vpcId);
    return sg;
  }

  private Subnet getTestSubnet(
      String cidrBlock, String subnetId, String vpcId, String availabilityZone) {
    Subnet subnet = new Subnet();
    subnet.setVpcId(vpcId);
    subnet.setSubnetId(subnetId);
    subnet.setCidrBlock(cidrBlock);
    subnet.setAvailabilityZone(availabilityZone);
    return subnet;
  }

  private JsonNode getK8sRequestBody() {
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "kubernetes");
    bodyJson.put("name", "test-k8s-provider");
    ObjectNode cloudInfo = Json.newObject();
    ObjectNode k8sConfig = Json.newObject();
    k8sConfig.put("kubernetesProvider", "GKE");
    k8sConfig.put("kubernetesPullSecret", "Kubeconfig pull secret");
    cloudInfo.set("kubernetes", k8sConfig);
    ObjectNode details = Json.newObject();
    details.set("cloudInfo", cloudInfo);
    bodyJson.set("details", details);

    ArrayNode regions = Json.newArray();
    ObjectNode region = Json.newObject();
    region.put("name", "Region-1");
    region.put("code", "region-1");

    ArrayNode zones = Json.newArray();
    ObjectNode zone = Json.newObject();
    zone.put("name", "Zone 1");
    zone.put("code", "zone-1");
    zone.set("details", details);
    zones.add(zone);
    region.set("zones", zones);

    regions.add(region);
    bodyJson.set("regions", regions);

    return bodyJson;
  }
}
