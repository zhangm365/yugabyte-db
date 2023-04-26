// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import static com.yugabyte.yw.common.TestHelper.testDatabase;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static play.inject.Bindings.bind;

import com.yugabyte.yw.cloud.CloudAPI;
import com.yugabyte.yw.cloud.aws.AWSCloudImpl;
import com.yugabyte.yw.commissioner.CallHome;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.SetUniverseKey;
import com.yugabyte.yw.commissioner.YbcUpgrade;
import com.yugabyte.yw.common.alerts.AlertConfigurationService;
import com.yugabyte.yw.common.alerts.AlertDefinitionService;
import com.yugabyte.yw.common.alerts.AlertService;
import com.yugabyte.yw.common.gflags.GFlagsValidation;
import com.yugabyte.yw.common.kms.EncryptionAtRestManager;
import com.yugabyte.yw.common.metrics.MetricService;
import com.yugabyte.yw.common.services.YBClientService;
import com.yugabyte.yw.common.services.YbcClientService;
import com.yugabyte.yw.common.ybc.YbcManager;
import com.yugabyte.yw.metrics.MetricQueryHelper;
import com.yugabyte.yw.models.helpers.JsonFieldsValidator;
import com.yugabyte.yw.scheduler.Scheduler;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;
import kamon.instrumentation.play.GuiceModule;
import org.junit.Before;
import org.pac4j.play.CallbackController;
import org.pac4j.play.store.PlayCacheSessionStore;
import org.pac4j.play.store.PlaySessionStore;
import org.yb.client.YBClient;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;

public class FakeDBApplication extends PlatformGuiceApplicationBaseTest {
  public Commissioner mockCommissioner = mock(Commissioner.class);
  public CallHome mockCallHome = mock(CallHome.class);
  public ApiHelper mockApiHelper = mock(ApiHelper.class);
  public ShellKubernetesManager mockKubernetesManager = mock(ShellKubernetesManager.class);
  public EncryptionAtRestManager mockEARManager = mock(EncryptionAtRestManager.class);
  public SetUniverseKey mockSetUniverseKey = mock(SetUniverseKey.class);
  public CallbackController mockCallbackController = mock(CallbackController.class);
  public PlayCacheSessionStore mockSessionStore = mock(PlayCacheSessionStore.class);
  public AccessManager mockAccessManager = mock(AccessManager.class);
  public CustomerLicenseManager mockCustomerLicenseManager = mock(CustomerLicenseManager.class);
  public TemplateManager mockTemplateManager = mock(TemplateManager.class);
  public MetricQueryHelper mockMetricQueryHelper = spy(MetricQueryHelper.class);
  public CloudQueryHelper mockCloudQueryHelper = mock(CloudQueryHelper.class);
  public CloudAPI.Factory mockCloudAPIFactory = mock(CloudAPI.Factory.class);
  public ReleaseManager mockReleaseManager = mock(ReleaseManager.class);
  public YBClientService mockService = mock(YBClientService.class);
  public DnsManager mockDnsManager = mock(DnsManager.class);
  public NetworkManager mockNetworkManager = mock(NetworkManager.class);
  public YamlWrapper mockYamlWrapper = mock(YamlWrapper.class);
  public Executors mockExecutors = mock(Executors.class);
  public ShellProcessHandler mockShellProcessHandler = mock(ShellProcessHandler.class);
  public TableManager mockTableManager = mock(TableManager.class);
  public TableManagerYb mockTableManagerYb = mock(TableManagerYb.class);
  public TaskInfoManager mockTaskManager = mock(TaskInfoManager.class);
  public GFlagsValidation mockGFlagsValidation = mock(GFlagsValidation.class);
  public NodeManager mockNodeManager = mock(NodeManager.class);
  public BackupUtil mockBackupUtil = mock(BackupUtil.class);
  public AWSUtil mockAWSUtil = mock(AWSUtil.class);
  public GCPUtil mockGCPUtil = mock(GCPUtil.class);
  public AZUtil mockAZUtil = mock(AZUtil.class);
  public JsonFieldsValidator mockJsonFieldValidator = mock(JsonFieldsValidator.class);
  public NFSUtil mockNfsUtil = mock(NFSUtil.class);
  public YbcClientService mockYbcClientService = mock(YbcClientService.class);
  public YbcUpgrade mockYbcUpgrade = mock(YbcUpgrade.class);
  public YbcManager mockYbcManager = mock(YbcManager.class);
  public AWSCloudImpl mockAWSCloudImpl = mock(AWSCloudImpl.class);
  public YBClient mockYBClient = mock(YBClient.class);
  public SwamperHelper mockSwamperHelper = mock(SwamperHelper.class);

  public MetricService metricService;
  public AlertService alertService;
  public AlertDefinitionService alertDefinitionService;
  public AlertConfigurationService alertConfigurationService;

  @Override
  protected Application provideApplication() {
    Map<String, Object> additionalConfiguration = new HashMap<>();
    return provideApplication(additionalConfiguration);
  }

  public Application provideApplication(Map<String, Object> additionalConfiguration) {
    return provideApplication(app -> app.configure(additionalConfiguration));
  }

  public Application provideApplication(
      Function<GuiceApplicationBuilder, GuiceApplicationBuilder> overrides) {
    GuiceApplicationBuilder guiceApplicationBuilder =
        new GuiceApplicationBuilder().disable(GuiceModule.class);
    guiceApplicationBuilder = overrides.apply(guiceApplicationBuilder);
    return configureApplication(
            guiceApplicationBuilder
                .configure(testDatabase())
                .overrides(bind(ApiHelper.class).toInstance(mockApiHelper))
                .overrides(bind(Commissioner.class).toInstance(mockCommissioner))
                .overrides(bind(CallHome.class).toInstance(mockCallHome))
                .overrides(bind(Executors.class).toInstance(mockExecutors))
                .overrides(bind(BackupUtil.class).toInstance(mockBackupUtil))
                .overrides(bind(EncryptionAtRestManager.class).toInstance(mockEARManager))
                .overrides(bind(SetUniverseKey.class).toInstance(mockSetUniverseKey))
                .overrides(bind(ShellKubernetesManager.class).toInstance(mockKubernetesManager))
                .overrides(bind(CallbackController.class).toInstance(mockCallbackController))
                .overrides(bind(PlaySessionStore.class).toInstance(mockSessionStore))
                .overrides(bind(AccessManager.class).toInstance(mockAccessManager))
                .overrides(
                    bind(CustomerLicenseManager.class).toInstance(mockCustomerLicenseManager))
                .overrides(bind(TemplateManager.class).toInstance(mockTemplateManager))
                .overrides(bind(MetricQueryHelper.class).toInstance(mockMetricQueryHelper))
                .overrides(bind(CloudQueryHelper.class).toInstance(mockCloudQueryHelper))
                .overrides(bind(ReleaseManager.class).toInstance(mockReleaseManager))
                .overrides(bind(YBClientService.class).toInstance(mockService))
                .overrides(bind(YBClient.class).toInstance(mockYBClient))
                .overrides(bind(NetworkManager.class).toInstance(mockNetworkManager))
                .overrides(bind(DnsManager.class).toInstance(mockDnsManager))
                .overrides(bind(YamlWrapper.class).toInstance(mockYamlWrapper))
                .overrides(bind(CloudAPI.Factory.class).toInstance(mockCloudAPIFactory))
                .overrides(bind(Scheduler.class).toInstance(mock(Scheduler.class)))
                .overrides(bind(ShellProcessHandler.class).toInstance(mockShellProcessHandler))
                .overrides(bind(TableManager.class).toInstance(mockTableManager))
                .overrides(bind(TableManagerYb.class).toInstance(mockTableManagerYb))
                .overrides(bind(TaskInfoManager.class).toInstance(mockTaskManager))
                .overrides(bind(GFlagsValidation.class).toInstance(mockGFlagsValidation))
                .overrides(bind(AWSUtil.class).toInstance(mockAWSUtil))
                .overrides(bind(GCPUtil.class).toInstance(mockGCPUtil))
                .overrides(bind(AZUtil.class).toInstance(mockAZUtil))
                .overrides(bind(NFSUtil.class).toInstance(mockNfsUtil))
                .overrides(bind(NodeManager.class).toInstance(mockNodeManager))
                .overrides(bind(JsonFieldsValidator.class).toInstance(mockJsonFieldValidator))
                .overrides(bind(YbcClientService.class).toInstance(mockYbcClientService))
                .overrides(bind(YbcManager.class).toInstance(mockYbcManager))
                .overrides(bind(YbcUpgrade.class).toInstance(mockYbcUpgrade))
                .overrides(bind(AWSCloudImpl.class).toInstance(mockAWSCloudImpl))
                .overrides(bind(SwamperHelper.class).toInstance(mockSwamperHelper)))
        .build();
  }

  protected boolean isSwaggerEnabled() {
    return false;
  }

  public Application getApp() {
    return app;
  }

  @Before
  public void baseSetUp() {
    metricService = app.injector().instanceOf(MetricService.class);
    alertService = app.injector().instanceOf(AlertService.class);
    alertDefinitionService = app.injector().instanceOf(AlertDefinitionService.class);
    alertConfigurationService = app.injector().instanceOf(AlertConfigurationService.class);
  }
}
