package com.yugabyte.yw.models.helpers;

import static com.yugabyte.yw.models.helpers.CommonUtils.maskConfigNew;
import static play.mvc.Http.Status.BAD_REQUEST;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.AvailabilityZoneDetails;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.ProviderDetails;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.RegionDetails;
import com.yugabyte.yw.models.helpers.provider.AWSCloudInfo;
import com.yugabyte.yw.models.helpers.provider.AzureCloudInfo;
import com.yugabyte.yw.models.helpers.provider.DefaultCloudInfo;
import com.yugabyte.yw.models.helpers.provider.GCPCloudInfo;
import com.yugabyte.yw.models.helpers.provider.KubernetesInfo;
import com.yugabyte.yw.models.helpers.provider.OnPremCloudInfo;
import com.yugabyte.yw.models.helpers.provider.region.AWSRegionCloudInfo;
import com.yugabyte.yw.models.helpers.provider.region.AzureRegionCloudInfo;
import com.yugabyte.yw.models.helpers.provider.region.DefaultRegionCloudInfo;
import com.yugabyte.yw.models.helpers.provider.region.GCPRegionCloudInfo;
import com.yugabyte.yw.models.helpers.provider.region.KubernetesRegionInfo;
import com.yugabyte.yw.models.helpers.provider.region.azs.DefaultAZCloudInfo;
import java.util.Map;
import java.util.Objects;
import play.libs.Json;

public interface CloudInfoInterface {

  public final ObjectMapper mapper = Json.mapper();

  public Map<String, String> getEnvVars();

  public Map<String, String> getConfigMapForUIOnlyAPIs(Map<String, String> config);

  public void mergeMaskedFields(CloudInfoInterface providerCloudInfo);

  public void withSensitiveDataMasked();

  public static enum VPCType {
    EXISTING,
    NEW
  }

  public static <T extends CloudInfoInterface> T get(Provider provider) {
    return get(provider, false);
  }

  public static <T extends CloudInfoInterface> T get(Region region) {
    return get(region, false);
  }

  public static <T extends CloudInfoInterface> T get(AvailabilityZone zone) {
    return get(zone, false);
  }

  public static <T extends CloudInfoInterface> T get(Provider provider, Boolean maskSensitiveData) {
    ProviderDetails providerDetails = provider.getDetails();
    if (providerDetails == null) {
      providerDetails = new ProviderDetails();
    }
    CloudType cloudType = provider.getCloudCode();
    return get(providerDetails, maskSensitiveData, cloudType);
  }

  public static <T extends CloudInfoInterface> T get(
      ProviderDetails providerDetails, Boolean maskSensitiveData, CloudType cloudType) {
    ProviderDetails.CloudInfo cloudInfo = providerDetails.getCloudInfo();
    if (cloudInfo == null) {
      cloudInfo = new ProviderDetails.CloudInfo();
      providerDetails.cloudInfo = cloudInfo;
    }
    return getCloudInfo(cloudInfo, cloudType, maskSensitiveData);
  }

  public static <T extends CloudInfoInterface> T get(Region region, Boolean maskSensitiveData) {
    RegionDetails regionDetails = region.getDetails();
    if (regionDetails == null) {
      regionDetails = new RegionDetails();
    }
    CloudType cloudType = region.getProviderCloudCode();
    return get(regionDetails, maskSensitiveData, cloudType);
  }

  public static <T extends CloudInfoInterface> T get(
      RegionDetails regionDetails, Boolean maskSensitiveData, CloudType cloudType) {
    RegionDetails.RegionCloudInfo cloudInfo = regionDetails.getCloudInfo();
    if (cloudInfo == null) {
      cloudInfo = new RegionDetails.RegionCloudInfo();
      regionDetails.cloudInfo = cloudInfo;
    }
    return getCloudInfo(cloudInfo, cloudType, maskSensitiveData);
  }

  public static <T extends CloudInfoInterface> T get(
      AvailabilityZone zone, Boolean maskSensitiveData) {
    AvailabilityZoneDetails azDetails = zone.getAvailabilityZoneDetails();
    if (azDetails == null) {
      azDetails = new AvailabilityZoneDetails();
    }
    CloudType cloudType = zone.getProviderCloudCode();
    return get(azDetails, maskSensitiveData, cloudType);
  }

  public static <T extends CloudInfoInterface> T get(
      AvailabilityZoneDetails azDetails, Boolean maskSensitiveData, CloudType cloudType) {
    AvailabilityZoneDetails.AZCloudInfo cloudInfo = azDetails.getCloudInfo();
    if (cloudInfo == null) {
      cloudInfo = new AvailabilityZoneDetails.AZCloudInfo();
      azDetails.cloudInfo = cloudInfo;
    }
    return getCloudInfo(cloudInfo, cloudType, maskSensitiveData);
  }

  public static <T extends CloudInfoInterface> T getCloudInfo(
      ProviderDetails.CloudInfo cloudInfo, CloudType cloudType, Boolean maskSensitiveData) {
    switch (cloudType) {
      case aws:
        AWSCloudInfo awsCloudInfo = cloudInfo.getAws();
        if (awsCloudInfo == null) {
          awsCloudInfo = new AWSCloudInfo();
          cloudInfo.setAws(awsCloudInfo);
        }
        if (awsCloudInfo != null && maskSensitiveData) {
          awsCloudInfo.withSensitiveDataMasked();
        }
        return (T) awsCloudInfo;
      case gcp:
        GCPCloudInfo gcpCloudInfo = cloudInfo.getGcp();
        if (gcpCloudInfo == null) {
          gcpCloudInfo = new GCPCloudInfo();
          cloudInfo.setGcp(gcpCloudInfo);
        }
        if (gcpCloudInfo != null && maskSensitiveData) {
          gcpCloudInfo.withSensitiveDataMasked();
        }
        return (T) gcpCloudInfo;
      case azu:
        AzureCloudInfo azuCloudInfo = cloudInfo.getAzu();
        if (azuCloudInfo == null) {
          azuCloudInfo = new AzureCloudInfo();
          cloudInfo.setAzu(azuCloudInfo);
        }
        if (azuCloudInfo != null && maskSensitiveData) {
          azuCloudInfo.withSensitiveDataMasked();
        }
        return (T) azuCloudInfo;
      case kubernetes:
        KubernetesInfo kubernetesInfo = cloudInfo.getKubernetes();
        if (kubernetesInfo == null) {
          kubernetesInfo = new KubernetesInfo();
          cloudInfo.setKubernetes(kubernetesInfo);
        }
        if (kubernetesInfo != null && maskSensitiveData) {
          kubernetesInfo.withSensitiveDataMasked();
        }
        return (T) kubernetesInfo;
      case onprem:
        OnPremCloudInfo onpremCloudInfo = cloudInfo.getOnprem();
        if (onpremCloudInfo == null) {
          onpremCloudInfo = new OnPremCloudInfo();
          cloudInfo.setOnprem(onpremCloudInfo);
        }
        if (onpremCloudInfo != null && maskSensitiveData) {
          onpremCloudInfo.withSensitiveDataMasked();
        }
        return (T) onpremCloudInfo;
      default:
        // Placeholder. Don't want consumers to receive null.
        return (T) new DefaultCloudInfo();
    }
  }

  public static <T extends CloudInfoInterface> T getCloudInfo(
      RegionDetails.RegionCloudInfo cloudInfo, CloudType cloudType, Boolean maskSensitiveData) {
    switch (cloudType) {
      case aws:
        AWSRegionCloudInfo awsRegionCloudInfo = cloudInfo.getAws();
        if (awsRegionCloudInfo == null) {
          awsRegionCloudInfo = new AWSRegionCloudInfo();
          cloudInfo.setAws(awsRegionCloudInfo);
        }
        if (awsRegionCloudInfo != null && maskSensitiveData) {
          awsRegionCloudInfo.withSensitiveDataMasked();
        }
        return (T) awsRegionCloudInfo;
      case gcp:
        GCPRegionCloudInfo gcpRegionCloudInfo = cloudInfo.getGcp();
        if (gcpRegionCloudInfo == null) {
          gcpRegionCloudInfo = new GCPRegionCloudInfo();
          cloudInfo.setGcp(gcpRegionCloudInfo);
        }
        if (gcpRegionCloudInfo != null && maskSensitiveData) {
          gcpRegionCloudInfo.withSensitiveDataMasked();
        }
        return (T) gcpRegionCloudInfo;
      case azu:
        AzureRegionCloudInfo azuRegionCloudInfo = cloudInfo.getAzu();
        if (azuRegionCloudInfo == null) {
          azuRegionCloudInfo = new AzureRegionCloudInfo();
          cloudInfo.setAzu(azuRegionCloudInfo);
        }
        if (azuRegionCloudInfo != null && maskSensitiveData) {
          azuRegionCloudInfo.withSensitiveDataMasked();
        }
        return (T) azuRegionCloudInfo;
      case kubernetes:
        KubernetesRegionInfo kubernetesInfo = cloudInfo.getKubernetes();
        if (kubernetesInfo == null) {
          kubernetesInfo = new KubernetesRegionInfo();
          cloudInfo.setKubernetes(kubernetesInfo);
        }
        if (kubernetesInfo != null && maskSensitiveData) {
          kubernetesInfo.withSensitiveDataMasked();
        }
        return (T) kubernetesInfo;
      default:
        // Placeholder. Don't want consumers to receive null.
        return (T) new DefaultRegionCloudInfo();
    }
  }

  public static <T extends CloudInfoInterface> T getCloudInfo(
      AvailabilityZoneDetails.AZCloudInfo cloudInfo,
      CloudType cloudType,
      Boolean maskSensitiveData) {
    switch (cloudType) {
      case kubernetes:
        KubernetesRegionInfo kubernetesAZInfo = cloudInfo.getKubernetes();
        if (kubernetesAZInfo == null) {
          kubernetesAZInfo = new KubernetesRegionInfo();
          cloudInfo.setKubernetes(kubernetesAZInfo);
        }
        if (kubernetesAZInfo != null && maskSensitiveData) {
          kubernetesAZInfo.withSensitiveDataMasked();
        }
        return (T) kubernetesAZInfo;
      default:
        return (T) new DefaultAZCloudInfo();
    }
  }

  public static ProviderDetails maskProviderDetails(Provider provider) {
    if (Objects.isNull(provider.getDetails())) {
      return null;
    }
    JsonNode detailsJson = Json.toJson(provider.getDetails());
    ProviderDetails details = Json.fromJson(detailsJson, ProviderDetails.class);
    get(details, true, provider.getCloudCode());
    return details;
  }

  public static RegionDetails maskRegionDetails(Region region) {
    if (Objects.isNull(region.getDetails())) {
      return null;
    }
    JsonNode detailsJson = Json.toJson(region.getDetails());
    if (detailsJson.size() == 0) {
      return null;
    }
    RegionDetails details = Json.fromJson(detailsJson, RegionDetails.class);
    get(details, true, region.getProvider().getCloudCode());
    return details;
  }

  public static AvailabilityZoneDetails maskAvailabilityZoneDetails(AvailabilityZone zone) {
    if (Objects.isNull(zone.getDetails())) {
      return null;
    }
    JsonNode detailsJson = Json.toJson(zone.getDetails());
    if (detailsJson.size() == 0) {
      return null;
    }
    AvailabilityZoneDetails details = Json.fromJson(detailsJson, AvailabilityZoneDetails.class);
    get(details, true, zone.getRegion().getProvider().getCloudCode());
    return details;
  }

  public static void setCloudProviderInfoFromConfig(Provider provider, Map<String, String> config) {
    ProviderDetails providerDetails = provider.getDetails();
    ProviderDetails.CloudInfo cloudInfo = providerDetails.getCloudInfo();
    if (cloudInfo == null) {
      cloudInfo = new ProviderDetails.CloudInfo();
      providerDetails.setCloudInfo(cloudInfo);
    }
    CloudType cloudType = provider.getCloudCode();
    setFromConfig(cloudInfo, config, cloudType);
  }

  public static void setCloudProviderInfoFromConfig(Region region, Map<String, String> config) {
    CloudType cloudType = region.getProviderCloudCode();
    if (cloudType.equals(CloudType.other)) {
      return;
    }
    RegionDetails regionDetails = region.getDetails();
    RegionDetails.RegionCloudInfo cloudInfo = regionDetails.getCloudInfo();
    if (cloudInfo == null) {
      cloudInfo = new RegionDetails.RegionCloudInfo();
      regionDetails.setCloudInfo(cloudInfo);
    }
    setFromConfig(cloudInfo, config, cloudType);
  }

  public static void setCloudProviderInfoFromConfig(
      AvailabilityZone az, Map<String, String> config) {
    CloudType cloudType = az.getProviderCloudCode();
    if (cloudType.equals(CloudType.other)) {
      return;
    }
    AvailabilityZoneDetails azDetails = az.getAvailabilityZoneDetails();
    AvailabilityZoneDetails.AZCloudInfo cloudInfo = azDetails.getCloudInfo();
    if (cloudInfo == null) {
      cloudInfo = new AvailabilityZoneDetails.AZCloudInfo();
      azDetails.setCloudInfo(cloudInfo);
    }
    setFromConfig(cloudInfo, config, cloudType);
  }

  public static void setFromConfig(
      ProviderDetails.CloudInfo cloudInfo, Map<String, String> config, CloudType cloudType) {
    if (config == null) {
      return;
    }

    switch (cloudType) {
      case aws:
        AWSCloudInfo awsCloudInfo = mapper.convertValue(config, AWSCloudInfo.class);
        cloudInfo.setAws(awsCloudInfo);
        break;
      case gcp:
        GCPCloudInfo gcpCloudInfo = mapper.convertValue(config, GCPCloudInfo.class);
        cloudInfo.setGcp(gcpCloudInfo);
        break;
      case azu:
        AzureCloudInfo azuCloudInfo = mapper.convertValue(config, AzureCloudInfo.class);
        cloudInfo.setAzu(azuCloudInfo);
        break;
      case kubernetes:
        KubernetesInfo kubernetesInfo = mapper.convertValue(config, KubernetesInfo.class);
        cloudInfo.setKubernetes(kubernetesInfo);
        break;
      case onprem:
        OnPremCloudInfo onPremCloudInfo = mapper.convertValue(config, OnPremCloudInfo.class);
        cloudInfo.setOnprem(onPremCloudInfo);
        break;
      case local:
        // TODO: check if it used anymore? in case not, remove the local universe case
        // Import Universe case
        break;
      default:
        throw new PlatformServiceException(BAD_REQUEST, "Unsupported cloud type");
    }
  }

  public static void setFromConfig(
      RegionDetails.RegionCloudInfo cloudInfo, Map<String, String> config, CloudType cloudType) {
    if (config == null) {
      return;
    }

    switch (cloudType) {
      case aws:
        AWSRegionCloudInfo awsRegionCloudInfo =
            mapper.convertValue(config, AWSRegionCloudInfo.class);
        cloudInfo.setAws(awsRegionCloudInfo);
        break;
      case gcp:
        GCPRegionCloudInfo gcpRegionCloudInfo =
            mapper.convertValue(config, GCPRegionCloudInfo.class);
        cloudInfo.setGcp(gcpRegionCloudInfo);
        break;
      case azu:
        AzureRegionCloudInfo azuRegionCloudInfo =
            mapper.convertValue(config, AzureRegionCloudInfo.class);
        cloudInfo.setAzu(azuRegionCloudInfo);
        break;
      case kubernetes:
        KubernetesRegionInfo kubernetesRegionInfo =
            mapper.convertValue(config, KubernetesRegionInfo.class);
        cloudInfo.setKubernetes(kubernetesRegionInfo);
        break;
      default:
        break;
    }
  }

  public static void setFromConfig(
      AvailabilityZoneDetails.AZCloudInfo cloudInfo,
      Map<String, String> config,
      CloudType cloudType) {
    if (config == null) {
      return;
    }

    switch (cloudType) {
      case kubernetes:
        KubernetesRegionInfo kubernetesAZInfo =
            mapper.convertValue(config, KubernetesRegionInfo.class);
        cloudInfo.setKubernetes(kubernetesAZInfo);
        break;
      default:
        break;
    }
  }

  public static Map<String, String> fetchEnvVars(Provider provider) {
    CloudInfoInterface cloudInfo = CloudInfoInterface.get(provider);
    return cloudInfo.getEnvVars();
  }

  public static Map<String, String> fetchEnvVars(Region region) {
    CloudInfoInterface cloudInfo = CloudInfoInterface.get(region);
    return cloudInfo.getEnvVars();
  }

  public static Map<String, String> fetchEnvVars(AvailabilityZone az) {
    CloudInfoInterface cloudInfo = CloudInfoInterface.get(az);
    return cloudInfo.getEnvVars();
  }

  public static void mergeSensitiveFields(Provider provider, Provider editProviderReq) {
    // This helper function helps in merging the masked config values using
    // the entity that is saved in the ebean so as to avoid saving the masked values.
    CloudInfoInterface providerCloudInfo = CloudInfoInterface.get(provider);
    CloudInfoInterface editProviderCloudInfo = CloudInfoInterface.get(editProviderReq);
    editProviderCloudInfo.mergeMaskedFields(providerCloudInfo);
    // ToDo: Add the same for regions/zones. Should we assume the indexing of region/zone
    // won't change? Will revisit once edit region/zone are checked in.
  }

  public static JsonNode mayBeMassageRequest(JsonNode requestBody, Boolean isV2API) {
    // For Backward Compatiblity support.
    JsonNode config = requestBody.get("config");
    ObjectNode reqBody = (ObjectNode) requestBody;
    // Confirm we had a "config" key and it was not null.
    if (config != null && !config.isNull()) {
      ObjectNode details = mapper.createObjectNode();
      if (requestBody.has("details")) {
        details = (ObjectNode) requestBody.get("details");
      }
      ObjectNode cloudInfo = mapper.createObjectNode();
      if (details.has("cloudInfo")) {
        cloudInfo = (ObjectNode) details.get("cloudInfo");
      }
      if (requestBody.get("code").asText().equals(CloudType.gcp.name())) {
        ObjectNode gcpCloudInfo = mapper.createObjectNode();
        if (cloudInfo.has("gcpCloudInfo")) {
          gcpCloudInfo = (ObjectNode) cloudInfo.get("gcpCloudInfo");
        }
        JsonNode configFileContent = config;
        if (!isV2API) {
          // UI_ONLY api passes the gcp creds config on `config_file_contents`.
          // where v2 API version 1 passes on `config` only
          configFileContent = config.get("config_file_contents");
        }

        if (isV2API) {
          if (requestBody.has("destVpcId")) {
            gcpCloudInfo.set("destVpcId", requestBody.get("destVpcId"));
            reqBody.remove("destVpcId");
          }
          if (requestBody.has("hostVpcId")) {
            gcpCloudInfo.set("hostVpcId", requestBody.get("hostVpcId"));
            reqBody.remove("hostVpcId");
          }
        }

        Boolean shouldUseHostCredentials =
            config.has("use_host_credentials") && config.get("use_host_credentials").asBoolean();

        if (config.has("host_project_id")) {
          gcpCloudInfo.set("host_project_id", config.get("host_project_id"));
        } else if (configFileContent != null && !configFileContent.isNull()) {
          gcpCloudInfo.set("host_project_id", ((ObjectNode) configFileContent).get("project_id"));
        }
        if (!shouldUseHostCredentials && configFileContent != null) {
          gcpCloudInfo.set("config_file_contents", configFileContent);
        }
        if (config.has("use_host_vpc")) {
          gcpCloudInfo.set("use_host_vpc", config.get("use_host_vpc"));
        }
        if (shouldUseHostCredentials) {
          gcpCloudInfo.set("useHostCredentials", config.get("use_host_credentials"));
        }
        gcpCloudInfo.set("YB_FIREWALL_TAGS", config.get("YB_FIREWALL_TAGS"));
        cloudInfo.set("gcp", gcpCloudInfo);
        details.set("cloudInfo", cloudInfo);
        details.set("airGapInstall", config.get("airGapInstall"));

        reqBody.set("details", details);
        reqBody.remove("config");
      } else if (requestBody.get("code").asText().equals(CloudType.aws.name())) {
        if (isV2API) {
          // Moving the top level hostVpcId/hostVpcRegion if passed to config
          // so that it can be populated to awsCloudInfo(for v2 APIs version 1).
          if (requestBody.has("hostVpcRegion")) {
            ((ObjectNode) config).set("hostVpcRegion", requestBody.get("hostVpcRegion"));
          }
          if (requestBody.has("hostVpcId")) {
            ((ObjectNode) config).set("hostVpcId", requestBody.get("hostVpcId"));
          }
          reqBody.set("config", config);
        }
      }
    }
    return reqBody;
  }

  public static Map<String, String> populateConfigMap(
      ProviderDetails.CloudInfo cloudInfo, CloudType cloudType, Map<String, String> config) {
    if (cloudInfo == null || config == null) {
      return config;
    }
    CloudInfoInterface cloudInfoInterface = null;
    switch (cloudType) {
      case aws:
        cloudInfoInterface = cloudInfo.getAws();
        break;
      case gcp:
        cloudInfoInterface = cloudInfo.getGcp();
        break;
      case azu:
        cloudInfoInterface = cloudInfo.getAzu();
        break;
      case kubernetes:
        cloudInfoInterface = cloudInfo.getKubernetes();
        break;
      case onprem:
        cloudInfoInterface = cloudInfo.getOnprem();
        break;
      case local:
        // TODO: check if it used anymore? in case not, remove the local universe case
        // Import Universe case
      default:
        break;
    }
    if (cloudInfoInterface == null) {
      return config;
    }
    return maskConfigNew(cloudInfoInterface.getConfigMapForUIOnlyAPIs(config));
  }

  public static Map<String, String> populateConfigMap(
      AvailabilityZoneDetails.AZCloudInfo cloudInfo,
      CloudType cloudType,
      Map<String, String> config) {
    if (cloudInfo == null || config == null) {
      return config;
    }
    CloudInfoInterface cloudInfoInterface = null;
    switch (cloudType) {
      case kubernetes:
        cloudInfoInterface = cloudInfo.getKubernetes();
        break;
      default:
        break;
    }
    if (cloudInfoInterface == null) {
      return config;
    }
    return maskConfigNew(cloudInfoInterface.getConfigMapForUIOnlyAPIs(config));
  }

  public static void mayBeMassageResponse(Provider p) {
    Map<String, String> config = CloudInfoInterface.fetchEnvVars(p);
    ProviderDetails providerDetails = p.getDetails();
    ProviderDetails.CloudInfo cloudInfo = providerDetails.getCloudInfo();
    CloudType cloudType = CloudType.valueOf(p.getCode());
    p.setConfig(populateConfigMap(cloudInfo, cloudType, config));

    if (p.getRegions() == null) {
      return;
    }

    for (Region region : p.getRegions()) {
      mayBeMassageResponse(cloudType, region);
    }
  }

  public static void mayBeMassageResponse(Provider p, Region region) {
    Map<String, String> config = CloudInfoInterface.fetchEnvVars(p);
    ProviderDetails providerDetails = p.getDetails();
    ProviderDetails.CloudInfo cloudInfo = providerDetails.getCloudInfo();
    CloudType cloudType = CloudType.valueOf(p.getCode());
    p.setConfig(populateConfigMap(cloudInfo, cloudType, config));

    mayBeMassageResponse(cloudType, region);
  }

  static void mayBeMassageResponse(CloudType cloudType, Region region) {
    if (region.getZones() == null) {
      return;
    }
    for (AvailabilityZone az : region.getZones()) {
      Map<String, String> config = CloudInfoInterface.fetchEnvVars(az);
      AvailabilityZoneDetails azDetails = az.getAvailabilityZoneDetails();
      AvailabilityZoneDetails.AZCloudInfo azCloudInfo = azDetails.getCloudInfo();
      az.setConfig(populateConfigMap(azCloudInfo, cloudType, config));
    }
  }
}
