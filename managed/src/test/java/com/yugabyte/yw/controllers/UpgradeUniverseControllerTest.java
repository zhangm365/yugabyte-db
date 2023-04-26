// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers;

import static com.yugabyte.yw.common.AssertHelper.assertAuditEntry;
import static com.yugabyte.yw.common.AssertHelper.assertBadRequest;
import static com.yugabyte.yw.common.AssertHelper.assertOk;
import static com.yugabyte.yw.common.AssertHelper.assertPlatformException;
import static com.yugabyte.yw.common.AssertHelper.assertValue;
import static com.yugabyte.yw.common.ModelFactory.createUniverse;
import static com.yugabyte.yw.common.TestHelper.createTempFile;
import static com.yugabyte.yw.common.TestHelper.testDatabase;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static play.inject.Bindings.bind;
import static play.test.Helpers.contentAsString;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.typesafe.config.Config;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.HealthChecker;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.CustomWsClientFactory;
import com.yugabyte.yw.common.CustomWsClientFactoryProvider;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.common.PlatformGuiceApplicationBaseTest;
import com.yugabyte.yw.common.ReleaseManager;
import com.yugabyte.yw.common.TestHelper;
import com.yugabyte.yw.common.certmgmt.CertConfigType;
import com.yugabyte.yw.common.certmgmt.CertificateHelper;
import com.yugabyte.yw.common.config.DummyRuntimeConfigFactoryImpl;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.forms.CertificateParams;
import com.yugabyte.yw.forms.CertsRotateParams;
import com.yugabyte.yw.forms.GFlagsUpgradeParams;
import com.yugabyte.yw.forms.SoftwareUpgradeParams;
import com.yugabyte.yw.forms.TlsToggleParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import com.yugabyte.yw.forms.UpgradeTaskParams;
import com.yugabyte.yw.forms.UpgradeTaskParams.UpgradeOption;
import com.yugabyte.yw.forms.VMImageUpgradeParams;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.CertificateInfo;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.InstanceType;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Users;
import com.yugabyte.yw.models.helpers.CloudSpecificInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.NodeDetails.NodeState;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.TaskType;
import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import junitparams.JUnitParamsRunner;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.Json;
import play.mvc.Result;

@RunWith(JUnitParamsRunner.class)
public class UpgradeUniverseControllerTest extends PlatformGuiceApplicationBaseTest {

  @Rule public MockitoRule rule = MockitoJUnit.rule();

  private Customer customer;
  private String authToken;

  private static Commissioner mockCommissioner;
  private Config mockConfig;

  private final String TMP_CHART_PATH =
      "/tmp/yugaware_tests/" + getClass().getSimpleName() + "/charts";
  private final String TMP_CERTS_PATH = "/tmp/" + getClass().getSimpleName() + "/certs";

  @Mock RuntimeConfigFactory mockRuntimeConfigFactory;

  String cert1Contents =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDEjCCAfqgAwIBAgIUEdzNoxkMLrZCku6H1jQ4pUgPtpQwDQYJKoZIhvcNAQEL\n"
          + "BQAwLzERMA8GA1UECgwIWXVnYWJ5dGUxGjAYBgNVBAMMEUNBIGZvciBZdWdhYnl0\n"
          + "ZURCMB4XDTIwMTIyMzA3MjU1MVoXDTIxMDEyMjA3MjU1MVowLzERMA8GA1UECgwI\n"
          + "WXVnYWJ5dGUxGjAYBgNVBAMMEUNBIGZvciBZdWdhYnl0ZURCMIIBIjANBgkqhkiG\n"
          + "9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuLPcCR1KpVSs3B2515xNAR8ntfhOM5JjLl6Y\n"
          + "WjqoyRQ4wiOg5fGQpvjsearpIntr5t6uMevpzkDMYY4U21KbIW8Vvg/kXiASKMmM\n"
          + "W4ToH3Q0NfgLUNb5zJ8df3J2JZ5CgGSpipL8VPWsuSZvrqL7V77n+ndjMTUBNf57\n"
          + "eW4VjzYq+YQhyloOlXtlfWT6WaAsoaVOlbU0GK4dE2rdeo78p2mS2wQZSBDXovpp\n"
          + "0TX4zhT6LsJaRBZe49GE4SMkxz74alK1ezRvFcrPiNKr5NOYYG9DUUqFHWg47Bmw\n"
          + "KbiZ5dLdyxgWRDbanwl2hOMfExiJhHE7pqgr8XcizCiYuUzlDwIDAQABoyYwJDAO\n"
          + "BgNVHQ8BAf8EBAMCAuQwEgYDVR0TAQH/BAgwBgEB/wIBATANBgkqhkiG9w0BAQsF\n"
          + "AAOCAQEAVI3NTJVNX4XTcVAxXXGumqCbKu9CCLhXxaJb+J8YgmMQ+s9lpmBuC1eB\n"
          + "38YFdSEG9dKXZukdQcvpgf4ryehtvpmk03s/zxNXC5237faQQqejPX5nm3o35E3I\n"
          + "ZQqN3h+mzccPaUvCaIlvYBclUAt4VrVt/W66kLFPsfUqNKVxm3B56VaZuQL1ZTwG\n"
          + "mrIYBoaVT/SmEeIX9PNjlTpprDN/oE25fOkOxwHyI9ydVFkMCpBNRv+NisQN9c+R\n"
          + "/SBXfs+07aqFgrGTte6/I4VZ/6vz2cWMwZU+TUg/u0fc0Y9RzOuJrZBV2qPAtiEP\n"
          + "YvtLjmJF//b3rsty6NFIonSVgq6Nqw==\n"
          + "-----END CERTIFICATE-----\n";

  String cert2Contents =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDAjCCAeqgAwIBAgIGAXVCiJ4gMA0GCSqGSIb3DQEBCwUAMC4xFjAUBgNVBAMM\n"
          + "DXliLWFkbWluLXRlc3QxFDASBgNVBAoMC2V4YW1wbGUuY29tMB4XDTIwMTAxOTIw\n"
          + "MjQxMVoXDTIxMTAxOTIwMjQxMVowLjEWMBQGA1UEAwwNeWItYWRtaW4tdGVzdDEU\n"
          + "MBIGA1UECgwLZXhhbXBsZS5jb20wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEK\n"
          + "AoIBAQCw8Mk+/MK0mP67ZEKL7cGyTzggau57MzTApveLfGF1Snln/Y7wGzgbskaM\n"
          + "0udz46es9HdaC/jT+PzMAAD9MCtAe5YYSL2+lmWT+WHdeJWF4XC/AVkjqj81N6OS\n"
          + "Uxio6ww0S9cAoDmF3gZlmkRwQcsruiZ1nVyQ7l+5CerQ02JwYBIYolUu/1qMruDD\n"
          + "pLsJ9LPWXPw2JsgYWyuEB5W1xEPDl6+QLTEVCFc9oN6wJOJgf0Y6OQODBrDRxddT\n"
          + "8h0mgJ6yzmkerR8VA0bknPQFeruWNJ/4PKDO9Itk5MmmYU/olvT5zMJ79K8RSvhN\n"
          + "+3gO8N7tcswaRP7HbEUmuVTtjFDlAgMBAAGjJjAkMBIGA1UdEwEB/wQIMAYBAf8C\n"
          + "AQEwDgYDVR0PAQH/BAQDAgLkMA0GCSqGSIb3DQEBCwUAA4IBAQCB10NLTyuqSD8/\n"
          + "HmbkdmH7TM1q0V/2qfrNQW86ywVKNHlKaZp9YlAtBcY5SJK37DJJH0yKM3GYk/ee\n"
          + "4aI566aj65gQn+wte9QfqzeotfVLQ4ZlhhOjDVRqSJVUdiW7yejHQLnqexdOpPQS\n"
          + "vwi73Fz0zGNxqnNjSNtka1rmduGwP0fiU3WKtHJiPL9CQFtRKdIlskKUlXg+WulM\n"
          + "x9yw5oa6xpsbCzSoS31fxYg71KAxVvKJYumdKV3ElGU/+AK1y4loyHv/kPp+59fF\n"
          + "9Q8gq/A6vGFjoZtVuuKUlasbMocle4Y9/nVxqIxWtc+aZ8mmP//J5oVXyzPs56dM\n"
          + "E1pTE1HS\n"
          + "-----END CERTIFICATE-----\n";

  @Override
  protected Application provideApplication() {
    mockCommissioner = mock(Commissioner.class);
    mockConfig = mock(Config.class);
    ReleaseManager mockReleaseManager = mock(ReleaseManager.class);

    when(mockConfig.getBoolean("yb.cloud.enabled")).thenReturn(false);
    when(mockConfig.getString("yb.storage.path")).thenReturn("/tmp/" + getClass().getSimpleName());
    when(mockReleaseManager.getReleaseByVersion(any()))
        .thenReturn(
            ReleaseManager.ReleaseMetadata.create("1.0.0")
                .withChartPath(TMP_CHART_PATH + "/uuct_yugabyte-1.0.0-helm.tar.gz"));
    when(mockConfig.getString("yb.security.type")).thenReturn("");
    when(mockConfig.getString("yb.security.clientID")).thenReturn("");
    when(mockConfig.getString("yb.security.secret")).thenReturn("");
    when(mockConfig.getString("yb.security.oidcScope")).thenReturn("");
    when(mockConfig.getString("yb.security.discoveryURI")).thenReturn("");

    when(mockConfig.getInt("yb.fs_stateless.max_files_count_persist")).thenReturn(100);
    when(mockConfig.getBoolean("yb.fs_stateless.suppress_error")).thenReturn(true);
    when(mockConfig.getLong("yb.fs_stateless.max_file_size_bytes")).thenReturn((long) 10000);
    when(mockRuntimeConfigFactory.globalRuntimeConf()).thenReturn(mockConfig);

    return new GuiceApplicationBuilder()
        .configure(testDatabase())
        .overrides(bind(Commissioner.class).toInstance(mockCommissioner))
        .overrides(
            bind(RuntimeConfigFactory.class)
                .toInstance(new DummyRuntimeConfigFactoryImpl(mockConfig)))
        .overrides(bind(ReleaseManager.class).toInstance(mockReleaseManager))
        .overrides(bind(HealthChecker.class).toInstance(mock(HealthChecker.class)))
        .overrides(
            bind(CustomWsClientFactory.class).toProvider(CustomWsClientFactoryProvider.class))
        .build();
  }

  @Before
  public void setUp() {
    customer = ModelFactory.testCustomer();
    Users user = ModelFactory.testUser(customer);
    authToken = user.createAuthToken();
    new File(TMP_CHART_PATH).mkdirs();
    createTempFile(TMP_CHART_PATH, "uuct_yugabyte-1.0.0-helm.tar.gz", "Sample helm chart data");
  }

  @After
  public void tearDown() throws IOException {
    FileUtils.deleteDirectory(new File(TMP_CERTS_PATH));
    FileUtils.deleteDirectory(new File(TMP_CHART_PATH));
  }

  @Test
  public void testRestartUniverseRolling() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/restart";
    ObjectNode bodyJson = Json.newObject().put("upgradeOption", "Rolling");
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<UpgradeTaskParams> argCaptor = ArgumentCaptor.forClass(UpgradeTaskParams.class);
    verify(mockCommissioner, times(1)).submit(eq(TaskType.RestartUniverse), argCaptor.capture());

    UpgradeTaskParams taskParams = argCaptor.getValue();
    assertEquals(UpgradeOption.ROLLING_UPGRADE, taskParams.upgradeOption);

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(
        task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.RestartUniverse)));
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testRestartUniverseNonRolling() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/restart";
    ObjectNode bodyJson = Json.newObject().put("upgradeOption", "Non-Rolling");
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<UpgradeTaskParams> argCaptor = ArgumentCaptor.forClass(UpgradeTaskParams.class);
    verify(mockCommissioner, times(1)).submit(eq(TaskType.RestartUniverse), argCaptor.capture());

    UpgradeTaskParams taskParams = argCaptor.getValue();
    assertEquals(UpgradeOption.NON_ROLLING_UPGRADE, taskParams.upgradeOption);

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(
        task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.RestartUniverse)));
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testSoftwareUpgradeWithInvalidParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/software";
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, Json.newObject()));
    assertBadRequest(result, "Missing required creator property");

    ArgumentCaptor<SoftwareUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(SoftwareUpgradeParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.SoftwareUpgrade), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testSoftwareUpgradeWithValidParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/software";
    ObjectNode bodyJson = Json.newObject().put("ybSoftwareVersion", "0.0.1");
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<SoftwareUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(SoftwareUpgradeParams.class);
    verify(mockCommissioner, times(1)).submit(eq(TaskType.SoftwareUpgrade), argCaptor.capture());

    SoftwareUpgradeParams taskParams = argCaptor.getValue();
    assertEquals(UpgradeOption.ROLLING_UPGRADE, taskParams.upgradeOption);
    assertEquals("0.0.1", taskParams.ybSoftwareVersion);

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(
        task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.SoftwareUpgrade)));
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testSoftwareUpgradeNonRolling() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/software";
    ObjectNode bodyJson =
        Json.newObject()
            .put("ybSoftwareVersion", "new-version")
            .put("upgradeOption", "Non-Rolling");
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<SoftwareUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(SoftwareUpgradeParams.class);
    verify(mockCommissioner, times(1)).submit(eq(TaskType.SoftwareUpgrade), argCaptor.capture());

    SoftwareUpgradeParams taskParams = argCaptor.getValue();
    assertEquals(UpgradeOption.NON_ROLLING_UPGRADE, taskParams.upgradeOption);
    assertEquals("new-version", taskParams.ybSoftwareVersion);

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(
        task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.SoftwareUpgrade)));
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testSoftwareUpgradeWithKubernetesUniverse() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    Universe universe = createUniverse("Test Universe", customer.getId(), CloudType.kubernetes);
    Map<String, String> universeConfig = new HashMap<>();
    universeConfig.put(Universe.HELM2_LEGACY, "helm");
    universe.setConfig(universeConfig);
    universe.save();

    String url =
        "/api/customers/"
            + customer.getUuid()
            + "/universes/"
            + universe.getUniverseUUID()
            + "/upgrade/software";
    ObjectNode bodyJson = Json.newObject().put("ybSoftwareVersion", "new-version");
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<SoftwareUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(SoftwareUpgradeParams.class);
    verify(mockCommissioner, times(1))
        .submit(eq(TaskType.SoftwareKubernetesUpgrade), argCaptor.capture());

    SoftwareUpgradeParams taskParams = argCaptor.getValue();
    assertEquals(UpgradeOption.ROLLING_UPGRADE, taskParams.upgradeOption);
    assertEquals("new-version", taskParams.ybSoftwareVersion);

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(
        task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.SoftwareUpgrade)));
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testGFlagsUpgradeWithInvalidParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/gflags";
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, Json.newObject()));
    assertBadRequest(result, "gflags param is required");

    ArgumentCaptor<GFlagsUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(GFlagsUpgradeParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.GFlagsUpgrade), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testGFlagsUpgradeWithSameFlags() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);

    Universe universe = createUniverse(customer.getId());
    Universe.UniverseUpdater updater =
        universeObject -> {
          UniverseDefinitionTaskParams universeDetails = universeObject.getUniverseDetails();
          UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;
          userIntent.masterGFlags = ImmutableMap.of("master-flag", "123");
          userIntent.tserverGFlags = ImmutableMap.of("tserver-flag", "456");
          universeObject.setUniverseDetails(universeDetails);
        };
    Universe.saveDetails(universe.getUniverseUUID(), updater);

    String url =
        "/api/customers/"
            + customer.getUuid()
            + "/universes/"
            + universe.getUniverseUUID()
            + "/upgrade/gflags";
    JsonNode masterGFlags = Json.parse("{ \"master-flag\": \"123\"}");
    JsonNode tserverGFlags = Json.parse("{ \"tserver-flag\": \"456\"}");
    ObjectNode bodyJson = Json.newObject().put("upgradeOption", "Non-Rolling");
    bodyJson.set("masterGFlags", masterGFlags);
    bodyJson.set("tserverGFlags", tserverGFlags);
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "No gflags to change");

    ArgumentCaptor<GFlagsUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(GFlagsUpgradeParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.GFlagsUpgrade), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testDeleteGFlagsThroughNonRestartOption() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);

    Universe universe = createUniverse(customer.getId());
    Universe.UniverseUpdater updater =
        universeObject -> {
          UniverseDefinitionTaskParams universeDetails = universeObject.getUniverseDetails();
          UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;
          userIntent.masterGFlags = ImmutableMap.of("master-flag", "123");
          userIntent.tserverGFlags = ImmutableMap.of("tserver-flag", "456");
          universeObject.setUniverseDetails(universeDetails);
        };
    Universe.saveDetails(universe.getUniverseUUID(), updater);

    String url =
        "/api/customers/"
            + customer.getUuid()
            + "/universes/"
            + universe.getUniverseUUID()
            + "/upgrade/gflags";
    JsonNode masterGFlags = Json.parse("{ \"master-flag\": \"123\"}");
    JsonNode tserverGFlags = Json.parse("{ \"tserver-flag2\": \"456\"}");
    ObjectNode bodyJson = Json.newObject().put("upgradeOption", "Non-Restart");
    bodyJson.set("masterGFlags", masterGFlags);
    bodyJson.set("tserverGFlags", tserverGFlags);
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "Cannot delete gFlags through non-restart upgrade option.");

    ArgumentCaptor<GFlagsUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(GFlagsUpgradeParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.GFlagsUpgrade), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testGFlagsUpgradeWithMalformedFlags() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/gflags";
    ObjectNode bodyJson = Json.newObject().put("masterGFlags", "abcd").put("tserverGFlags", "abcd");
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "JsonProcessingException parsing request body");

    ArgumentCaptor<GFlagsUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(GFlagsUpgradeParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.GFlagsUpgrade), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testGFlagsUpgradeWithValidParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/gflags";
    JsonNode masterGFlags = Json.parse("{ \"master-flag\": \"123\"}");
    JsonNode tserverGFlags = Json.parse("{ \"tserver-flag\": \"456\"}");
    ObjectNode bodyJson = Json.newObject();
    bodyJson.set("masterGFlags", masterGFlags);
    bodyJson.set("tserverGFlags", tserverGFlags);
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<GFlagsUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(GFlagsUpgradeParams.class);
    verify(mockCommissioner, times(1)).submit(eq(TaskType.GFlagsUpgrade), argCaptor.capture());

    GFlagsUpgradeParams taskParams = argCaptor.getValue();
    assertEquals("123", taskParams.masterGFlags.get("master-flag"));
    assertEquals("456", taskParams.tserverGFlags.get("tserver-flag"));
    assertEquals(UpgradeOption.ROLLING_UPGRADE, taskParams.upgradeOption);

    // Checking params are merged with universe info.
    Universe universe = Universe.getOrBadRequest(universeUUID);
    assertEquals(universe.getUniverseDetails().rootCA, taskParams.rootCA);
    assertEquals(universe.getUniverseDetails().getClientRootCA(), taskParams.getClientRootCA());
    assertEquals(universe.getUniverseDetails().clusters.size(), taskParams.clusters.size());

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.GFlagsUpgrade)));
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testGFlagsUpgradeWithTrimParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/gflags";
    JsonNode masterGFlags = Json.parse("{ \"master-flag\": \" 123 \"}");
    JsonNode tserverGFlags = Json.parse("{ \"tserver-flag\": \" 456 \"}");
    ObjectNode bodyJson = Json.newObject();
    bodyJson.set("masterGFlags", masterGFlags);
    bodyJson.set("tserverGFlags", tserverGFlags);
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<GFlagsUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(GFlagsUpgradeParams.class);
    verify(mockCommissioner, times(1)).submit(eq(TaskType.GFlagsUpgrade), argCaptor.capture());

    GFlagsUpgradeParams taskParams = argCaptor.getValue();
    assertEquals("123", taskParams.masterGFlags.get("master-flag"));
    assertEquals("456", taskParams.tserverGFlags.get("tserver-flag"));
    assertEquals(UpgradeOption.ROLLING_UPGRADE, taskParams.upgradeOption);

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.GFlagsUpgrade)));
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testGFlagsUpgradeNonRolling() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/gflags";
    JsonNode masterGFlags = Json.parse("{ \"master-flag\": \"123\"}");
    JsonNode tserverGFlags = Json.parse("{ \"tserver-flag\": \"456\"}");
    ObjectNode bodyJson = Json.newObject().put("upgradeOption", "Non-Rolling");
    bodyJson.set("masterGFlags", masterGFlags);
    bodyJson.set("tserverGFlags", tserverGFlags);
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<GFlagsUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(GFlagsUpgradeParams.class);
    verify(mockCommissioner, times(1)).submit(eq(TaskType.GFlagsUpgrade), argCaptor.capture());

    GFlagsUpgradeParams taskParams = argCaptor.getValue();
    assertEquals("123", taskParams.masterGFlags.get("master-flag"));
    assertEquals("456", taskParams.tserverGFlags.get("tserver-flag"));
    assertEquals(UpgradeOption.NON_ROLLING_UPGRADE, taskParams.upgradeOption);

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.GFlagsUpgrade)));
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testGFlagsUpgradeWithKubernetesUniverse() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    Universe universe = createUniverse("Test Universe", customer.getId(), CloudType.kubernetes);
    Map<String, String> universeConfig = new HashMap<>();
    universeConfig.put(Universe.HELM2_LEGACY, "helm");
    universe.setConfig(universeConfig);
    universe.save();

    String url =
        "/api/customers/"
            + customer.getUuid()
            + "/universes/"
            + universe.getUniverseUUID()
            + "/upgrade/gflags";
    JsonNode masterGFlags = Json.parse("{ \"master-flag\": \"123\"}");
    JsonNode tserverGFlags = Json.parse("{ \"tserver-flag\": \"456\"}");
    ObjectNode bodyJson = Json.newObject();
    bodyJson.set("masterGFlags", masterGFlags);
    bodyJson.set("tserverGFlags", tserverGFlags);
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<GFlagsUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(GFlagsUpgradeParams.class);
    verify(mockCommissioner, times(1))
        .submit(eq(TaskType.GFlagsKubernetesUpgrade), argCaptor.capture());

    GFlagsUpgradeParams taskParams = argCaptor.getValue();
    assertEquals("123", taskParams.masterGFlags.get("master-flag"));
    assertEquals("456", taskParams.tserverGFlags.get("tserver-flag"));
    assertEquals(UpgradeOption.ROLLING_UPGRADE, taskParams.upgradeOption);

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.GFlagsUpgrade)));
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testCertsRotateWithNoChange() throws IOException, NoSuchAlgorithmException {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = prepareUniverseForCertsRotate(false);

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/certs";
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, Json.newObject()));
    assertBadRequest(result, "No changes in rootCA or clientRootCA.");

    ArgumentCaptor<CertsRotateParams> argCaptor = ArgumentCaptor.forClass(CertsRotateParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.CertsRotate), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testCertsRotate() throws IOException, NoSuchAlgorithmException {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = prepareUniverseForCertsRotate(false);

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/certs";
    ObjectNode bodyJson = prepareRequestBodyForCertsRotate(false);
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<CertsRotateParams> argCaptor = ArgumentCaptor.forClass(CertsRotateParams.class);
    verify(mockCommissioner, times(1)).submit(eq(TaskType.CertsRotate), argCaptor.capture());

    CertsRotateParams taskParams = argCaptor.getValue();
    assertEquals(bodyJson.get("rootCA").asText(), taskParams.rootCA.toString());
    assertEquals(bodyJson.get("clientRootCA").asText(), taskParams.getClientRootCA().toString());
    assertEquals(UpgradeOption.ROLLING_UPGRADE, taskParams.upgradeOption);

    // Checking params are merged with universe info.
    Universe universe = Universe.getOrBadRequest(universeUUID);
    assertEquals(universe.getUniverseDetails().allowInsecure, taskParams.allowInsecure);
    assertEquals(
        universe.getUniverseDetails().setTxnTableWaitCountFlag,
        taskParams.setTxnTableWaitCountFlag);
    assertEquals(universe.getUniverseDetails().clusters.size(), taskParams.clusters.size());

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.CertsRotate)));
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testCertsRotateByTlsConfigUpdate() throws IOException, NoSuchAlgorithmException {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = prepareUniverseForCertsRotate(false);
    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/update_tls";
    ObjectNode bodyJson = prepareRequestBodyForCertsRotate(false);
    bodyJson.put("enableNodeToNodeEncrypt", "true");
    bodyJson.put("enableClientToNodeEncrypt", "true");
    bodyJson.put("rootAndClientRootCASame", "false");
    bodyJson.put("sleepAfterMasterRestartMillis", 1200);
    bodyJson.put("sleepAfterTServerRestartMillis", 1300);
    bodyJson.put("upgradeOption", "Non-Rolling");
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<CertsRotateParams> argCaptor = ArgumentCaptor.forClass(CertsRotateParams.class);
    verify(mockCommissioner, times(1)).submit(eq(TaskType.CertsRotate), argCaptor.capture());

    CertsRotateParams taskParams = argCaptor.getValue();
    assertEquals(bodyJson.get("rootCA").asText(), taskParams.rootCA.toString());
    assertEquals(bodyJson.get("clientRootCA").asText(), taskParams.getClientRootCA().toString());
    assertEquals(1200, (int) taskParams.sleepAfterMasterRestartMillis);
    assertEquals(1300, (int) taskParams.sleepAfterTServerRestartMillis);
    assertEquals(UpgradeOption.NON_ROLLING_UPGRADE, taskParams.upgradeOption);

    // Checking params are merged with universe info.
    Universe universe = Universe.getOrBadRequest(universeUUID);
    assertEquals(universe.getUniverseDetails().allowInsecure, taskParams.allowInsecure);
    assertEquals(
        universe.getUniverseDetails().setTxnTableWaitCountFlag,
        taskParams.setTxnTableWaitCountFlag);
    assertEquals(universe.getUniverseDetails().clusters.size(), taskParams.clusters.size());

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.CertsRotate)));
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testCertsRotateWithOnPremUniverse() throws IOException, NoSuchAlgorithmException {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = prepareUniverseForCertsRotate(true);

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/certs";
    ObjectNode bodyJson = prepareRequestBodyForCertsRotate(true);
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<CertsRotateParams> argCaptor = ArgumentCaptor.forClass(CertsRotateParams.class);
    verify(mockCommissioner, times(1)).submit(eq(TaskType.CertsRotate), argCaptor.capture());

    CertsRotateParams taskParams = argCaptor.getValue();
    assertEquals(bodyJson.get("rootCA").asText(), taskParams.rootCA.toString());
    assertEquals(UpgradeOption.ROLLING_UPGRADE, taskParams.upgradeOption);

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.CertsRotate)));
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testTlsToggleWithEmptyParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = prepareUniverseForTlsToggle(false, false, null);

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/tls";
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, Json.newObject()));
    assertBadRequest(result, "Missing required creator property");

    ArgumentCaptor<TlsToggleParams> argCaptor = ArgumentCaptor.forClass(TlsToggleParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.TlsToggle), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testTlsToggleWithInvalidUpgradeOption() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = prepareUniverseForTlsToggle(false, false, null);

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/tls";
    ObjectNode bodyJson = prepareRequestBodyForTlsToggle(true, true, null);
    bodyJson.put("upgradeOption", "ROLLING");
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "JsonProcessingException parsing request body");

    ArgumentCaptor<TlsToggleParams> argCaptor = ArgumentCaptor.forClass(TlsToggleParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.TlsToggle), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testTlsToggleWithNoChangeInParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = prepareUniverseForTlsToggle(false, false, null);

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/tls";
    ObjectNode bodyJson = prepareRequestBodyForTlsToggle(false, false, null);
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "No changes in Tls parameters");

    ArgumentCaptor<TlsToggleParams> argCaptor = ArgumentCaptor.forClass(TlsToggleParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.TlsToggle), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testTlsToggleWithInvalidRootCa() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = prepareUniverseForTlsToggle(false, false, null);

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/tls";
    ObjectNode bodyJson = prepareRequestBodyForTlsToggle(true, false, UUID.randomUUID());
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "No valid root certificate");

    ArgumentCaptor<TlsToggleParams> argCaptor = ArgumentCaptor.forClass(TlsToggleParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.TlsToggle), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testTlsToggleWithRootCaUpdate() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    when(mockConfig.getString("yb.storage.path")).thenReturn(TMP_CERTS_PATH);
    UUID certUUID1 = CertificateHelper.createRootCA(mockConfig, "test cert 1", customer.getUuid());
    UUID certUUID2 = CertificateHelper.createRootCA(mockConfig, "test cert 2", customer.getUuid());
    UUID universeUUID = prepareUniverseForTlsToggle(true, true, certUUID1);

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/tls";
    ObjectNode bodyJson = prepareRequestBodyForTlsToggle(false, true, certUUID2);
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "Cannot update root certificate");

    ArgumentCaptor<TlsToggleParams> argCaptor = ArgumentCaptor.forClass(TlsToggleParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.TlsToggle), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testTlsToggleWithNodesInTransit() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = prepareUniverseForTlsToggle(false, false, null);
    setInTransitNode(universeUUID);

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/tls";
    ObjectNode bodyJson = prepareRequestBodyForTlsToggle(true, true, null);
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "as it has nodes in one of");

    ArgumentCaptor<TlsToggleParams> argCaptor = ArgumentCaptor.forClass(TlsToggleParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.TlsToggle), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testTlsToggleWithValidParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = prepareUniverseForTlsToggle(false, false, null);

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/tls";
    ObjectNode bodyJson = prepareRequestBodyForTlsToggle(true, true, null);
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<TlsToggleParams> argCaptor = ArgumentCaptor.forClass(TlsToggleParams.class);
    verify(mockCommissioner, times(1)).submit(eq(TaskType.TlsToggle), argCaptor.capture());

    TlsToggleParams taskParams = argCaptor.getValue();
    assertEquals(UpgradeOption.ROLLING_UPGRADE, taskParams.upgradeOption);
    assertTrue(taskParams.enableNodeToNodeEncrypt);
    assertTrue(taskParams.enableClientToNodeEncrypt);
    assertNotNull(taskParams.rootCA);

    assertNotNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testVMImageUpgradeWithUnsupportedProvider() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    Provider provider = ModelFactory.onpremProvider(customer);
    UUID universeUUID = prepareUniverseForVMImageUpgrade(provider, "type.small").getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/vm";
    ObjectNode bodyJson = Json.newObject();
    ObjectNode images = Json.newObject();
    UUID uuid = UUID.randomUUID();
    images.put(uuid.toString(), "image-" + uuid);
    bodyJson.set("machineImages", images);
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "VM image upgrade is only supported for AWS / GCP");

    ArgumentCaptor<VMImageUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(VMImageUpgradeParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.VMImageUpgrade), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testVMImageUpgradeWithEphemeralStorage() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    Provider provider = ModelFactory.awsProvider(customer);
    UUID universeUUID = prepareUniverseForVMImageUpgrade(provider, "i3.xlarge").getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/vm";
    ObjectNode bodyJson = Json.newObject();
    ObjectNode images = Json.newObject();
    UUID uuid = UUID.randomUUID();
    images.put(uuid.toString(), "image-" + uuid);
    bodyJson.set("machineImages", images);
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson));
    assertBadRequest(result, "Cannot upgrade a universe with ephemeral storage");

    ArgumentCaptor<VMImageUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(VMImageUpgradeParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.VMImageUpgrade), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testVMImageUpgradeWithNoImage() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    Provider provider = ModelFactory.awsProvider(customer);
    UUID universeUUID = prepareUniverseForVMImageUpgrade(provider, "c5.xlarge").getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/vm";
    Result result =
        assertPlatformException(
            () -> doRequestWithAuthTokenAndBody("POST", url, authToken, Json.newObject()));
    assertBadRequest(result, "machineImages/imageBundle param is required.");

    ArgumentCaptor<VMImageUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(VMImageUpgradeParams.class);
    verify(mockCommissioner, times(0)).submit(eq(TaskType.VMImageUpgrade), argCaptor.capture());

    assertNull(CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne());
    assertAuditEntry(0, customer.getUuid());
  }

  @Test
  public void testVMImageUpgradeValidParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    Provider provider = ModelFactory.awsProvider(customer);
    Universe universe = prepareUniverseForVMImageUpgrade(provider, "c5.xlarge");
    UUID regionUuid =
        universe.getUniverseDetails().getPrimaryCluster().userIntent.regionList.get(0);

    String url =
        "/api/customers/"
            + customer.getUuid()
            + "/universes/"
            + universe.getUniverseUUID()
            + "/upgrade/vm";
    ObjectNode bodyJson = Json.newObject();
    ObjectNode images = Json.newObject();
    images.put(regionUuid.toString(), "image-" + regionUuid);
    bodyJson.set("machineImages", images);
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<VMImageUpgradeParams> argCaptor =
        ArgumentCaptor.forClass(VMImageUpgradeParams.class);
    verify(mockCommissioner, times(1)).submit(eq(TaskType.VMImageUpgrade), argCaptor.capture());

    VMImageUpgradeParams taskParams = argCaptor.getValue();
    assertEquals(UpgradeOption.ROLLING_UPGRADE, taskParams.upgradeOption);
    assertEquals("image-" + regionUuid, taskParams.machineImages.get(regionUuid));

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(
        task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.VMImageUpgrade)));
    assertAuditEntry(1, customer.getUuid());
  }

  @Test
  public void testRebootUniverse() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(), any())).thenReturn(fakeTaskUUID);
    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();

    String url =
        "/api/customers/" + customer.getUuid() + "/universes/" + universeUUID + "/upgrade/reboot";
    ObjectNode bodyJson = Json.newObject().put("upgradeOption", "Rolling");
    Result result = doRequestWithAuthTokenAndBody("POST", url, authToken, bodyJson);

    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "taskUUID", fakeTaskUUID.toString());

    ArgumentCaptor<UpgradeTaskParams> argCaptor = ArgumentCaptor.forClass(UpgradeTaskParams.class);
    verify(mockCommissioner, times(1)).submit(eq(TaskType.RebootUniverse), argCaptor.capture());

    UpgradeTaskParams taskParams = argCaptor.getValue();
    assertEquals(UpgradeOption.ROLLING_UPGRADE, taskParams.upgradeOption);

    CustomerTask task = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.getUuid())));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("Test Universe")));
    assertThat(
        task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.RebootUniverse)));
    assertAuditEntry(1, customer.getUuid());
  }

  private UUID prepareUniverseForCertsRotate(boolean onprem)
      throws IOException, NoSuchAlgorithmException {
    UUID rootCA = UUID.randomUUID();
    UUID clientRootCA = UUID.randomUUID();
    createTempFile("upgrade_universe_controller_test_ca.crt", cert1Contents);
    if (onprem) {
      Date date = new Date();

      CertificateParams.CustomCertInfo customCertInfo = new CertificateParams.CustomCertInfo();
      customCertInfo.rootCertPath = "rootCertPath";
      customCertInfo.nodeCertPath = "nodeCertPath";
      customCertInfo.nodeKeyPath = "nodeKeyPath";
      CertificateInfo.create(
          rootCA,
          customer.getUuid(),
          "test1",
          date,
          date,
          TestHelper.TMP_PATH + "/upgrade_universe_controller_test_ca.crt",
          customCertInfo);
    } else {
      createTempFile("upgrade_universe_controller_test_ca2.crt", cert2Contents);
      CertificateInfo.create(
          rootCA,
          customer.getUuid(),
          "test1",
          new Date(),
          new Date(),
          "privateKey",
          TestHelper.TMP_PATH + "/upgrade_universe_controller_test_ca.crt",
          CertConfigType.SelfSigned);
      CertificateInfo.create(
          clientRootCA,
          customer.getUuid(),
          "test2",
          new Date(),
          new Date(),
          "privateKey",
          TestHelper.TMP_PATH + "/upgrade_universe_controller_test_ca2.crt",
          CertConfigType.SelfSigned);
    }

    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();
    Universe.saveDetails(universeUUID, ApiUtils.mockUniverseUpdater());
    return Universe.saveDetails(
            universeUUID,
            universe -> {
              UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
              PlacementInfo placementInfo = universeDetails.getPrimaryCluster().placementInfo;
              UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;
              userIntent.enableNodeToNodeEncrypt = true;
              userIntent.enableClientToNodeEncrypt = true;
              if (onprem) {
                universeDetails.rootCA = rootCA;
                universeDetails.rootAndClientRootCASame = true;
                userIntent.providerType = CloudType.onprem;
              } else {
                universeDetails.rootCA = rootCA;
                universeDetails.setClientRootCA(clientRootCA);
                universeDetails.rootAndClientRootCASame = false;
                userIntent.providerType = CloudType.aws;
              }
              universeDetails.upsertPrimaryCluster(userIntent, placementInfo);
              // Modifying default values to make sure these params are merged into taskParams.
              universeDetails.setTxnTableWaitCountFlag = !universeDetails.setTxnTableWaitCountFlag;
              universeDetails.allowInsecure = !universeDetails.allowInsecure;
              universe.setUniverseDetails(universeDetails);
            })
        .getUniverseUUID();
  }

  private ObjectNode prepareRequestBodyForCertsRotate(boolean onprem)
      throws IOException, NoSuchAlgorithmException {
    UUID rootCA = UUID.randomUUID();
    UUID clientRootCA = UUID.randomUUID();
    createTempFile("upgrade_universe_controller_test_ca2.crt", cert2Contents);
    if (onprem) {
      Date date = new Date();

      CertificateParams.CustomCertInfo customCertInfo = new CertificateParams.CustomCertInfo();
      customCertInfo.rootCertPath = "rootCertPath1";
      customCertInfo.nodeCertPath = "nodeCertPath1";
      customCertInfo.nodeKeyPath = "nodeKeyPath1";
      CertificateInfo.create(
          rootCA,
          customer.getUuid(),
          "test2",
          date,
          date,
          TestHelper.TMP_PATH + "/upgrade_universe_controller_test_ca2.crt",
          customCertInfo);
      return Json.newObject()
          .put("rootCA", rootCA.toString())
          .put("clientRootCA", rootCA.toString());
    } else {
      createTempFile("upgrade_universe_controller_test_ca.crt", cert1Contents);
      CertificateInfo.create(
          rootCA,
          customer.getUuid(),
          "test3",
          new Date(),
          new Date(),
          "privateKey",
          TestHelper.TMP_PATH + "/upgrade_universe_controller_test_ca2.crt",
          CertConfigType.SelfSigned);
      CertificateInfo.create(
          clientRootCA,
          customer.getUuid(),
          "test4",
          new Date(),
          new Date(),
          "privateKey",
          TestHelper.TMP_PATH + "/upgrade_universe_controller_test_ca.crt",
          CertConfigType.SelfSigned);
      return Json.newObject()
          .put("rootCA", rootCA.toString())
          .put("clientRootCA", clientRootCA.toString());
    }
  }

  private UUID prepareUniverseForTlsToggle(
      boolean enableNodeToNodeEncrypt, boolean enableClientToNodeEncrypt, UUID rootCA) {
    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();
    Universe.saveDetails(universeUUID, ApiUtils.mockUniverseUpdater());
    // Update current TLS params
    Universe.saveDetails(
        universeUUID,
        universe -> {
          UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
          PlacementInfo placementInfo = universeDetails.getPrimaryCluster().placementInfo;
          UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;
          userIntent.providerType = CloudType.aws;
          userIntent.enableNodeToNodeEncrypt = enableNodeToNodeEncrypt;
          userIntent.enableClientToNodeEncrypt = enableClientToNodeEncrypt;
          universeDetails.rootAndClientRootCASame = true;
          universeDetails.rootCA = rootCA;
          universeDetails.setClientRootCA(rootCA);
          universeDetails.upsertPrimaryCluster(userIntent, placementInfo);
          universe.setUniverseDetails(universeDetails);
        });
    return universeUUID;
  }

  private ObjectNode prepareRequestBodyForTlsToggle(
      boolean enableNodeToNodeEncrypt, boolean enableClientToNodeEncrypt, UUID rootCA) {
    return Json.newObject()
        .put("enableNodeToNodeEncrypt", enableNodeToNodeEncrypt)
        .put("enableClientToNodeEncrypt", enableClientToNodeEncrypt)
        .put("rootCA", rootCA != null ? rootCA.toString() : "");
  }

  private Universe prepareUniverseForVMImageUpgrade(Provider provider, String instanceTypeString) {
    when(mockConfig.getBoolean("yb.cloud.enabled")).thenReturn(true);

    Region region = Region.create(provider, "region", "Region", "yb-image-1");
    AvailabilityZone availabilityZone =
        AvailabilityZone.createOrThrow(region, "az", "AZ", "subnet");
    UUID universeUUID = createUniverse(customer.getId()).getUniverseUUID();
    Universe.saveDetails(universeUUID, ApiUtils.mockUniverseUpdater());

    return Universe.saveDetails(
        universeUUID,
        universe -> {
          UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
          PlacementInfo placementInfo = new PlacementInfo();
          PlacementInfoUtil.addPlacementZone(
              availabilityZone.getUuid(), placementInfo, 1, 2, false);
          universeDetails.getPrimaryCluster().placementInfo = placementInfo;

          InstanceType instanceType =
              InstanceType.upsert(
                  provider.getUuid(),
                  instanceTypeString,
                  10,
                  5.5,
                  new InstanceType.InstanceTypeDetails());

          UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;
          userIntent.numNodes = 3;
          userIntent.instanceType = instanceType.getInstanceTypeCode();
          userIntent.replicationFactor = 3;
          userIntent.providerType = Common.CloudType.valueOf(provider.getCode());
          userIntent.provider = provider.getUuid().toString();
          userIntent.regionList = ImmutableList.of(region.getUuid());
          universeDetails.upsertPrimaryCluster(userIntent, placementInfo);

          universeDetails.nodeDetailsSet = new HashSet<>();
          for (int idx = 0; idx <= userIntent.numNodes; idx++) {
            NodeDetails node = new NodeDetails();
            node.nodeIdx = idx;
            node.placementUuid = universeDetails.getPrimaryCluster().uuid;
            node.nodeName = "host-n" + idx;
            node.isMaster = true;
            node.isTserver = true;
            node.cloudInfo = new CloudSpecificInfo();
            node.cloudInfo.private_ip = "1.2.3." + idx;
            node.cloudInfo.az = availabilityZone.getCode();
            node.azUuid = availabilityZone.getUuid();
            universeDetails.nodeDetailsSet.add(node);
          }

          for (NodeDetails node : universeDetails.nodeDetailsSet) {
            node.nodeUuid = UUID.randomUUID();
          }

          universe.setUniverseDetails(universeDetails);
        });
  }

  // Change the node state to removed, for one of the nodes in the given universe uuid.
  private void setInTransitNode(UUID universeUUID) {
    Universe.UniverseUpdater updater =
        universe -> {
          UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
          NodeDetails node = universeDetails.nodeDetailsSet.iterator().next();
          node.state = NodeState.Removed;
          universe.setUniverseDetails(universeDetails);
        };
    Universe.saveDetails(universeUUID, updater);
  }
}
