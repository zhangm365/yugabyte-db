// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.models.configs.validators;

import static play.mvc.Http.Status.BAD_REQUEST;

import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.util.CollectionUtils;
import com.google.inject.Singleton;
import com.yugabyte.yw.cloud.aws.AWSCloudImpl;
import com.yugabyte.yw.common.BeanValidator;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.config.RuntimeConfGetter;
import com.yugabyte.yw.models.AccessKey;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.helpers.provider.AWSCloudInfo;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import org.apache.commons.lang3.StringUtils;

@Singleton
public class AWSProviderValidator extends ProviderFieldsValidator {

  private final AWSCloudImpl awsCloudImpl;
  private final RuntimeConfGetter runtimeConfigGetter;

  @Inject
  public AWSProviderValidator(
      BeanValidator beanValidator,
      AWSCloudImpl awsCloudImpl,
      RuntimeConfGetter runtimeConfigGetter) {
    super(beanValidator, runtimeConfigGetter);
    this.awsCloudImpl = awsCloudImpl;
    this.runtimeConfigGetter = runtimeConfigGetter;
  }

  @Override
  public void validate(Provider provider) {
    // To guarantee that we can safely fall back to the default provider,
    // the user should either submit both keys and their secret or leave them as null.
    checkMissingKeys(provider);

    // validate access
    if (provider.getRegions() != null && !provider.getRegions().isEmpty()) {
      for (Region region : provider.getRegions()) {
        try {
          awsCloudImpl.getStsClientOrBadRequest(provider, region);
        } catch (PlatformServiceException e) {
          if (e.getHttpStatus() == BAD_REQUEST) {
            if (awsCloudImpl.checkKeysExists(provider)) {
              throwBeanProviderValidatorError("KEYS", e.getMessage());
            } else {
              throwBeanProviderValidatorError("IAM", e.getMessage());
            }
          }
          throw e;
        }
      }
    }

    // validate SSH private key content
    try {
      if (provider.getAllAccessKeys() != null && provider.getAllAccessKeys().size() > 0) {
        for (AccessKey accessKey : provider.getAllAccessKeys()) {
          String privateKeyContent = accessKey.getKeyInfo().sshPrivateKeyContent;
          if (!awsCloudImpl.getPrivateKeyAlgoOrBadRequest(privateKeyContent).equals("RSA")) {
            throw new PlatformServiceException(BAD_REQUEST, "Please provide a valid RSA key");
          }
        }
      }
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        throwBeanProviderValidatorError("SSH_PRIVATE_KEY_CONTENT", e.getMessage());
      }
      throw e;
    }

    // validate NTP Servers
    if (provider.getDetails() != null && provider.getDetails().ntpServers != null) {
      validateNTPServers(provider.getDetails().ntpServers);
    }

    if (provider.getDetails().sshPort == null) {
      throwBeanProviderValidatorError("SSH_PORT", "Please provide a valid ssh port value");
    }

    // validate hosted zone id
    if (provider.getRegions() != null && !provider.getRegions().isEmpty()) {
      for (Region region : provider.getRegions()) {
        try {
          String hostedZoneId = provider.getDetails().cloudInfo.aws.awsHostedZoneId;
          if (!StringUtils.isEmpty(hostedZoneId)) {
            awsCloudImpl.getHostedZoneOrBadRequest(provider, region, hostedZoneId);
          }
        } catch (PlatformServiceException e) {
          if (e.getHttpStatus() == BAD_REQUEST) {
            throwBeanProviderValidatorError("HOSTED_ZONE", e.getMessage());
          }
          throw e;
        }
      }
    }

    // validate Region and its details
    if (provider.getRegions() != null && !provider.getRegions().isEmpty()) {
      for (Region region : provider.getRegions()) {
        validateAMI(provider, region);
        validateVpc(provider, region);
        validateSgAndPort(provider, region);
        validateSubnets(provider, region);
        dryRun(provider, region);
      }
    }
  }

  @Override
  public void validate(AvailabilityZone zone) {
    // pass
  }

  private void dryRun(Provider provider, Region region) {
    String fieldDetails = "REGION." + region.getCode() + ".DRY_RUN";
    try {
      awsCloudImpl.dryRunDescribeInstanceOrBadRequest(provider, region.getCode());
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        throwBeanProviderValidatorError(fieldDetails, e.getMessage());
      }
      throw e;
    }
  }

  private void validateAMI(Provider provider, Region region) {
    String imageId = region.getYbImage();
    String fieldDetails = "REGION." + region.getCode() + "." + "IMAGE";
    try {
      if (!StringUtils.isEmpty(imageId)) {
        Image image = awsCloudImpl.describeImageOrBadRequest(provider, region, imageId);
        String arch = image.getArchitecture().toLowerCase();
        List<String> supportedArch =
            runtimeConfigGetter.getStaticConf().getStringList("yb.aws.supported_arch_types");
        if (!supportedArch.contains(arch)) {
          throw new PlatformServiceException(
              BAD_REQUEST, arch + " arch on image " + imageId + " is not supported");
        }
        List<String> supportedRootDeviceType =
            runtimeConfigGetter.getStaticConf().getStringList("yb.aws.supported_root_device_type");
        String rootDeviceType = image.getRootDeviceType().toLowerCase();
        if (!supportedRootDeviceType.contains(rootDeviceType)) {
          throw new PlatformServiceException(
              BAD_REQUEST,
              rootDeviceType + " root device type on image " + imageId + " is not supported");
        }
        List<String> supportedPlatform =
            runtimeConfigGetter.getStaticConf().getStringList("yb.aws.supported_platform");
        String platformDetails = image.getPlatformDetails().toLowerCase();
        if (supportedPlatform.stream().noneMatch(platformDetails::contains)) {
          throw new PlatformServiceException(
              BAD_REQUEST, platformDetails + " platform on image " + imageId + " is not supported");
        }
      }
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        throwBeanProviderValidatorError(fieldDetails, e.getMessage());
      }
      throw e;
    }
  }

  private void validateVpc(Provider provider, Region region) {
    String fieldDetails = "REGION." + region.getCode() + ".VPC";
    try {
      if (!StringUtils.isEmpty(region.getVnetName())) {
        awsCloudImpl.describeVpcOrBadRequest(provider, region);
      }
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        throwBeanProviderValidatorError(fieldDetails, e.getMessage());
      }
      throw e;
    }
  }

  private void validateSgAndPort(Provider provider, Region region) {
    String fieldDetails = "REGION." + region.getCode() + ".SECURITY_GROUP";

    try {
      if (!StringUtils.isEmpty(region.getSecurityGroupId())) {
        SecurityGroup securityGroup =
            awsCloudImpl.describeSecurityGroupsOrBadRequest(provider, region);
        if (StringUtils.isEmpty(securityGroup.getVpcId())) {
          throw new PlatformServiceException(
              BAD_REQUEST, "No vpc is attached to SG: " + region.getSecurityGroupId());
        }
        if (!securityGroup.getVpcId().equals(region.getVnetName())) {
          throw new PlatformServiceException(
              BAD_REQUEST,
              region.getSecurityGroupId() + " is not attached to vpc: " + region.getVnetName());
        }
        Integer sshPort = provider.getDetails().getSshPort();
        boolean portOpen = false;
        if (!CollectionUtils.isNullOrEmpty(securityGroup.getIpPermissions())) {
          for (IpPermission ipPermission : securityGroup.getIpPermissions()) {
            Integer fromPort = ipPermission.getFromPort();
            Integer toPort = ipPermission.getToPort();
            if (fromPort == null || toPort == null) {
              continue;
            }
            if (fromPort <= sshPort && toPort >= sshPort) {
              portOpen = true;
              break;
            }
          }
        }
        if (!portOpen) {
          throw new PlatformServiceException(
              BAD_REQUEST,
              sshPort + " is not open on security group " + region.getSecurityGroupId());
        }
      }
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        throwBeanProviderValidatorError(fieldDetails, e.getMessage());
      }
      throw e;
    }
  }

  private void validateSubnets(Provider provider, Region region) {
    String fieldDetails = "REGION." + region.getCode() + ".SUBNETS";
    String regionVnetName = region.getVnetName();
    try {
      if (!StringUtils.isEmpty(region.getSecurityGroupId())) {
        List<Subnet> subnets = awsCloudImpl.describeSubnetsOrBadRequest(provider, region);
        Set<String> cidrBlocks = new HashSet<>();
        for (Subnet subnet : subnets) {
          AvailabilityZone az = getAzBySubnetFromRegion(region, subnet.getSubnetId());
          if (!az.getCode().equals(subnet.getAvailabilityZone())) {
            throw new PlatformServiceException(
                BAD_REQUEST, "Invalid AZ code for subnet: " + subnet.getSubnetId());
          }
          if (!subnet.getVpcId().equals(regionVnetName)) {
            throw new PlatformServiceException(
                BAD_REQUEST, subnet.getSubnetId() + " is not associated with " + regionVnetName);
          }
          if (cidrBlocks.contains(subnet.getCidrBlock())) {
            throw new PlatformServiceException(
                BAD_REQUEST, "Please provide non-overlapping CIDR blocks subnets");
          }
          cidrBlocks.add(subnet.getCidrBlock());
        }
      }
    } catch (PlatformServiceException e) {
      if (e.getHttpStatus() == BAD_REQUEST) {
        throwBeanProviderValidatorError(fieldDetails, e.getMessage());
      }
      throw e;
    }
  }

  private void checkMissingKeys(Provider provider) {
    AWSCloudInfo cloudInfo = provider.getDetails().getCloudInfo().getAws();
    String accessKey = cloudInfo.awsAccessKeyID;
    String accessKeySecret = cloudInfo.awsAccessKeySecret;
    if ((StringUtils.isEmpty(accessKey) && !StringUtils.isEmpty(accessKeySecret))
        || (!StringUtils.isEmpty(accessKey) && StringUtils.isEmpty(accessKeySecret))) {
      throwBeanProviderValidatorError("KEYS", "Please provide both access key and its secret");
    }
  }

  private AvailabilityZone getAzBySubnetFromRegion(Region region, String subnet) {
    return region.getZones().stream()
        .filter(zone -> zone.getSubnet().equals(subnet))
        .findFirst()
        .orElseThrow(
            () ->
                new PlatformServiceException(
                    BAD_REQUEST, "Could not find AZ for subnet: " + subnet));
  }
}
