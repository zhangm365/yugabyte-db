// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.tasks.UniverseTaskBase.ServerType;
import com.yugabyte.yw.common.KubernetesManagerFactory;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.common.certmgmt.CertConfigType;
import com.yugabyte.yw.common.certmgmt.CertificateHelper;
import com.yugabyte.yw.common.config.ProviderConfKeys;
import com.yugabyte.yw.common.config.RuntimeConfGetter;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.common.gflags.GFlagDetails;
import com.yugabyte.yw.common.gflags.GFlagDiffEntry;
import com.yugabyte.yw.common.gflags.GFlagsAuditPayload;
import com.yugabyte.yw.common.gflags.GFlagsUtil;
import com.yugabyte.yw.common.ybc.YbcManager;
import com.yugabyte.yw.forms.CertsRotateParams;
import com.yugabyte.yw.forms.GFlagsUpgradeParams;
import com.yugabyte.yw.forms.KubernetesOverridesUpgradeParams;
import com.yugabyte.yw.forms.ResizeNodeParams;
import com.yugabyte.yw.forms.RestartTaskParams;
import com.yugabyte.yw.forms.SoftwareUpgradeParams;
import com.yugabyte.yw.forms.SystemdUpgradeParams;
import com.yugabyte.yw.forms.ThirdpartySoftwareUpgradeParams;
import com.yugabyte.yw.forms.TlsToggleParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import com.yugabyte.yw.forms.UpgradeTaskParams;
import com.yugabyte.yw.forms.VMImageUpgradeParams;
import com.yugabyte.yw.models.CertificateInfo;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.TaskType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import play.mvc.Http.Status;

@Slf4j
@Singleton
public class UpgradeUniverseHandler {

  private final Commissioner commissioner;
  private final KubernetesManagerFactory kubernetesManagerFactory;
  private final RuntimeConfigFactory runtimeConfigFactory;
  private final GFlagsValidationHandler gFlagsValidationHandler;
  private final YbcManager ybcManager;
  private final RuntimeConfGetter confGetter;

  @Inject
  public UpgradeUniverseHandler(
      Commissioner commissioner,
      KubernetesManagerFactory kubernetesManagerFactory,
      RuntimeConfigFactory runtimeConfigFactory,
      GFlagsValidationHandler gFlagsValidationHandler,
      YbcManager ybcManager,
      RuntimeConfGetter confGetter) {
    this.commissioner = commissioner;
    this.kubernetesManagerFactory = kubernetesManagerFactory;
    this.runtimeConfigFactory = runtimeConfigFactory;
    this.gFlagsValidationHandler = gFlagsValidationHandler;
    this.ybcManager = ybcManager;
    this.confGetter = confGetter;
  }

  public UUID restartUniverse(
      RestartTaskParams requestParams, Customer customer, Universe universe) {
    // Verify request params
    requestParams.verifyParams(universe);

    UserIntent userIntent = universe.getUniverseDetails().getPrimaryCluster().userIntent;
    return submitUpgradeTask(
        userIntent.providerType.equals(CloudType.kubernetes)
            ? TaskType.RestartUniverseKubernetesUpgrade
            : TaskType.RestartUniverse,
        CustomerTask.TaskType.RestartUniverse,
        requestParams,
        customer,
        universe);
  }

  public UUID upgradeSoftware(
      SoftwareUpgradeParams requestParams, Customer customer, Universe universe) {
    // Temporary fix for PLAT-4791 until PLAT-4653 fixed.
    if (universe.getUniverseDetails().getReadOnlyClusters().size() > 0
        && requestParams.getReadOnlyClusters().size() == 0) {
      requestParams.clusters.add(universe.getUniverseDetails().getReadOnlyClusters().get(0));
    }
    // Verify request params
    requestParams.verifyParams(universe);

    UserIntent userIntent = universe.getUniverseDetails().getPrimaryCluster().userIntent;

    if (userIntent.providerType.equals(CloudType.kubernetes)) {
      checkHelmChartExists(requestParams.ybSoftwareVersion);
    }

    if (userIntent.providerType.equals(CloudType.kubernetes)) {
      Provider p = Provider.getOrBadRequest(UUID.fromString(userIntent.provider));
      if (confGetter.getConfForScope(p, ProviderConfKeys.enableYbcOnK8s)
          && Util.compareYbVersions(
                  requestParams.ybSoftwareVersion, Util.K8S_YBC_COMPATIBLE_DB_VERSION, true)
              >= 0
          && !universe.isYbcEnabled()
          && requestParams.isEnableYbc()) {
        requestParams.setYbcSoftwareVersion(ybcManager.getStableYbcVersion());
        requestParams.installYbc = true;
      } else if (universe.isYbcEnabled()) {
        requestParams.setEnableYbc(true);
        requestParams.installYbc = true;
        requestParams.setYbcSoftwareVersion(ybcManager.getStableYbcVersion());
      } else {
        requestParams.setEnableYbc(false);
        requestParams.installYbc = false;
      }
    } else if (Util.compareYbVersions(
                requestParams.ybSoftwareVersion, Util.YBC_COMPATIBLE_DB_VERSION, true)
            > 0
        && !universe.isYbcEnabled()
        && requestParams.isEnableYbc()) {
      requestParams.setYbcSoftwareVersion(ybcManager.getStableYbcVersion());
      requestParams.installYbc = true;
    } else {
      requestParams.setYbcSoftwareVersion(universe.getUniverseDetails().getYbcSoftwareVersion());
      requestParams.installYbc = false;
      requestParams.setEnableYbc(false);
    }
    requestParams.setYbcInstalled(universe.isYbcEnabled());

    return submitUpgradeTask(
        userIntent.providerType.equals(CloudType.kubernetes)
            ? TaskType.SoftwareKubernetesUpgrade
            : TaskType.SoftwareUpgrade,
        CustomerTask.TaskType.SoftwareUpgrade,
        requestParams,
        customer,
        universe);
  }

  public UUID upgradeGFlags(
      GFlagsUpgradeParams requestParams, Customer customer, Universe universe) {
    UserIntent userIntent;
    if (requestParams.masterGFlags.isEmpty()
        && requestParams.tserverGFlags.isEmpty()
        && requestParams.getPrimaryCluster() != null) {
      // If user hasn't provided gflags in the top level params, get from primary cluster
      userIntent = requestParams.getPrimaryCluster().userIntent;
      userIntent.masterGFlags = GFlagsUtil.trimFlags(userIntent.masterGFlags);
      userIntent.tserverGFlags = GFlagsUtil.trimFlags(userIntent.tserverGFlags);
      requestParams.masterGFlags = userIntent.masterGFlags;
      requestParams.tserverGFlags = userIntent.tserverGFlags;
    } else {
      userIntent = universe.getUniverseDetails().getPrimaryCluster().userIntent;
      requestParams.masterGFlags = GFlagsUtil.trimFlags(requestParams.masterGFlags);
      requestParams.tserverGFlags = GFlagsUtil.trimFlags((requestParams.tserverGFlags));
    }

    // Temporary fix for PLAT-4791 until PLAT-4653 fixed.
    if (universe.getUniverseDetails().getReadOnlyClusters().size() > 0
        && requestParams.getReadOnlyClusters().size() == 0) {
      requestParams.clusters.add(universe.getUniverseDetails().getReadOnlyClusters().get(0));
    }
    // Verify request params
    requestParams.verifyParams(universe);

    if (userIntent.providerType.equals(CloudType.kubernetes)) {
      // Gflags upgrade does not change universe version. Check for current version of helm chart.
      checkHelmChartExists(
          universe.getUniverseDetails().getPrimaryCluster().userIntent.ybSoftwareVersion);
    }
    return submitUpgradeTask(
        userIntent.providerType.equals(CloudType.kubernetes)
            ? TaskType.GFlagsKubernetesUpgrade
            : TaskType.GFlagsUpgrade,
        CustomerTask.TaskType.GFlagsUpgrade,
        requestParams,
        customer,
        universe);
  }

  public UUID upgradeKubernetesOverrides(
      KubernetesOverridesUpgradeParams requestParams, Customer customer, Universe universe) {
    // Temporary fix for PLAT-4791 until PLAT-4653 fixed.
    if (universe.getUniverseDetails().getReadOnlyClusters().size() > 0
        && requestParams.getReadOnlyClusters().size() == 0) {
      requestParams.clusters.add(universe.getUniverseDetails().getReadOnlyClusters().get(0));
    }
    requestParams.verifyParams(universe);
    return submitUpgradeTask(
        TaskType.KubernetesOverridesUpgrade,
        CustomerTask.TaskType.KubernetesOverridesUpgrade,
        requestParams,
        customer,
        universe);
  }

  public JsonNode constructGFlagAuditPayload(
      GFlagsUpgradeParams requestParams, UserIntent oldUserIntent) {
    if (requestParams.getPrimaryCluster() == null) {
      return null;
    }
    // TODO: support specific gflags
    UserIntent newUserIntent = requestParams.getPrimaryCluster().userIntent;
    Map<String, String> newMasterGFlags = newUserIntent.masterGFlags;
    Map<String, String> newTserverGFlags = newUserIntent.tserverGFlags;
    Map<String, String> oldMasterGFlags = oldUserIntent.masterGFlags;
    Map<String, String> oldTserverGFlags = oldUserIntent.tserverGFlags;
    GFlagsAuditPayload payload = new GFlagsAuditPayload();
    String softwareVersion = newUserIntent.ybSoftwareVersion;
    payload.master =
        generateGFlagEntries(
            oldMasterGFlags, newMasterGFlags, ServerType.MASTER.toString(), softwareVersion);
    payload.tserver =
        generateGFlagEntries(
            oldTserverGFlags, newTserverGFlags, ServerType.TSERVER.toString(), softwareVersion);

    ObjectMapper mapper = new ObjectMapper();
    Map<String, GFlagsAuditPayload> auditPayload = new HashMap<>();
    auditPayload.put("gflags", payload);

    return mapper.valueToTree(auditPayload);
  }

  public List<GFlagDiffEntry> generateGFlagEntries(
      Map<String, String> oldGFlags,
      Map<String, String> newGFlags,
      String serverType,
      String softwareVersion) {
    List<GFlagDiffEntry> gFlagChanges = new ArrayList<GFlagDiffEntry>();

    GFlagDiffEntry tEntry;
    Collection<String> modifiedGFlags = Sets.union(oldGFlags.keySet(), newGFlags.keySet());

    for (String gFlagName : modifiedGFlags) {
      String oldGFlagValue = oldGFlags.getOrDefault(gFlagName, null);
      String newGFlagValue = newGFlags.getOrDefault(gFlagName, null);
      if (oldGFlagValue == null || !oldGFlagValue.equals(newGFlagValue)) {
        String defaultGFlagValue = getGFlagDefaultValue(softwareVersion, serverType, gFlagName);
        tEntry = new GFlagDiffEntry(gFlagName, oldGFlagValue, newGFlagValue, defaultGFlagValue);
        gFlagChanges.add(tEntry);
      }
    }

    return gFlagChanges;
  }

  public String getGFlagDefaultValue(String softwareVersion, String serverType, String gFlagName) {
    GFlagDetails defaultGFlag;
    String defaultGFlagValue;
    try {
      defaultGFlag =
          gFlagsValidationHandler.getGFlagsMetadata(softwareVersion, serverType, gFlagName);
    } catch (IOException | PlatformServiceException e) {
      defaultGFlag = null;
    }
    defaultGFlagValue = (defaultGFlag == null) ? null : defaultGFlag.defaultValue;
    return defaultGFlagValue;
  }

  public UUID rotateCerts(CertsRotateParams requestParams, Customer customer, Universe universe) {
    log.debug(
        "rotateCerts called with rootCA: {}",
        (requestParams.rootCA != null) ? requestParams.rootCA.toString() : "NULL");
    // Temporary fix for PLAT-4791 until PLAT-4653 fixed.
    if (universe.getUniverseDetails().getReadOnlyClusters().size() > 0
        && requestParams.getReadOnlyClusters().size() == 0) {
      requestParams.clusters.add(universe.getUniverseDetails().getReadOnlyClusters().get(0));
    }
    // Verify request params
    requestParams.verifyParams(universe);
    UserIntent userIntent = universe.getUniverseDetails().getPrimaryCluster().userIntent;
    // Generate client certs if rootAndClientRootCASame is true and rootCA is self-signed.
    // This is there only for legacy support, no need if rootCA and clientRootCA are different.
    if (userIntent.enableClientToNodeEncrypt && requestParams.rootAndClientRootCASame) {
      CertificateInfo rootCert = CertificateInfo.get(requestParams.rootCA);
      if (rootCert.getCertType() == CertConfigType.SelfSigned
          || rootCert.getCertType() == CertConfigType.HashicorpVault) {
        CertificateHelper.createClientCertificate(
            runtimeConfigFactory.staticApplicationConf(), customer.getUuid(), requestParams.rootCA);
      }
    }

    if (userIntent.providerType.equals(CloudType.kubernetes)) {
      // Certs rotate does not change universe version. Check for current version of helm chart.
      checkHelmChartExists(
          universe.getUniverseDetails().getPrimaryCluster().userIntent.ybSoftwareVersion);
    }

    return submitUpgradeTask(
        userIntent.providerType.equals(CloudType.kubernetes)
            ? TaskType.CertsRotateKubernetesUpgrade
            : TaskType.CertsRotate,
        CustomerTask.TaskType.CertsRotate,
        requestParams,
        customer,
        universe);
  }

  public UUID resizeNode(ResizeNodeParams requestParams, Customer customer, Universe universe) {
    // Verify request params
    requestParams.verifyParams(universe);

    UserIntent userIntent = universe.getUniverseDetails().getPrimaryCluster().userIntent;
    return submitUpgradeTask(
        userIntent.providerType.equals(CloudType.kubernetes)
            ? TaskType.UpdateKubernetesDiskSize
            : TaskType.ResizeNode,
        CustomerTask.TaskType.ResizeNode,
        requestParams,
        customer,
        universe);
  }

  public UUID thirdpartySoftwareUpgrade(
      ThirdpartySoftwareUpgradeParams requestParams, Customer customer, Universe universe) {
    // Verify request params
    requestParams.verifyParams(universe);
    return submitUpgradeTask(
        TaskType.ThirdpartySoftwareUpgrade,
        CustomerTask.TaskType.ThirdpartySoftwareUpgrade,
        requestParams,
        customer,
        universe);
  }

  // Enable/Disable TLS on Cluster
  public UUID toggleTls(TlsToggleParams requestParams, Customer customer, Universe universe) {
    UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
    UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;

    // Verify request params
    requestParams.verifyParams(universe);
    requestParams.allowInsecure =
        !(requestParams.enableNodeToNodeEncrypt || requestParams.enableClientToNodeEncrypt);

    if (requestParams.enableNodeToNodeEncrypt) {
      // Setting the rootCA to the already existing rootCA as we do not
      // support root certificate rotation through TLS upgrade.
      // There is a check for different new and existing root cert already.
      if (universeDetails.rootCA == null) {
        // Create self-signed rootCA in case it is not provided by the user
        if (requestParams.rootCA == null) {
          requestParams.rootCA =
              CertificateHelper.createRootCA(
                  runtimeConfigFactory.staticApplicationConf(),
                  universeDetails.nodePrefix,
                  customer.getUuid());
        }
      } else {
        // If certificate already present then use the same as upgrade cannot rotate certs
        requestParams.rootCA = universeDetails.rootCA;
      }
    }

    if (requestParams.enableClientToNodeEncrypt) {
      // Setting the ClientRootCA to the already existing clientRootCA as we do not
      // support root certificate rotation through TLS upgrade.
      // There is a check for different new and existing root cert already.
      if (universeDetails.getClientRootCA() == null) {
        if (requestParams.getClientRootCA() == null) {
          if (requestParams.rootCA != null && requestParams.rootAndClientRootCASame) {
            // Setting ClientRootCA to RootCA in case rootAndClientRootCA is true
            requestParams.setClientRootCA(requestParams.rootCA);
          } else {
            // Create self-signed clientRootCA in case it is not provided by the user
            // and rootCA and clientRootCA needs to be different
            requestParams.setClientRootCA(
                CertificateHelper.createClientRootCA(
                    runtimeConfigFactory.staticApplicationConf(),
                    universeDetails.nodePrefix,
                    customer.getUuid()));
          }
        }
      } else {
        requestParams.setClientRootCA(universeDetails.getClientRootCA());
      }

      // Setting rootCA to ClientRootCA in case node to node encryption is disabled.
      // This is necessary to set to ensure backward compatibility as existing parts of
      // codebase uses rootCA for Client to Node Encryption
      if (requestParams.rootCA == null && requestParams.rootAndClientRootCASame) {
        requestParams.rootCA = requestParams.getClientRootCA();
      }

      // Generate client certs if rootAndClientRootCASame is true and rootCA is self-signed.
      // This is there only for legacy support, no need if rootCA and clientRootCA are different.
      if (requestParams.rootAndClientRootCASame) {
        CertificateInfo cert = CertificateInfo.get(requestParams.rootCA);
        if (cert.getCertType() == CertConfigType.SelfSigned
            || cert.getCertType() == CertConfigType.HashicorpVault) {
          CertificateHelper.createClientCertificate(
              runtimeConfigFactory.staticApplicationConf(),
              customer.getUuid(),
              requestParams.rootCA);
        }
      }
    }

    String typeName = generateTypeName(userIntent, requestParams);

    return submitUpgradeTask(
        TaskType.TlsToggle,
        CustomerTask.TaskType.TlsToggle,
        requestParams,
        customer,
        universe,
        typeName);
  }

  public UUID upgradeVMImage(
      VMImageUpgradeParams requestParams, Customer customer, Universe universe) {
    // Verify request params
    requestParams.verifyParams(universe);
    return submitUpgradeTask(
        TaskType.VMImageUpgrade,
        CustomerTask.TaskType.VMImageUpgrade,
        requestParams,
        customer,
        universe);
  }

  public UUID upgradeSystemd(
      SystemdUpgradeParams requestParams, Customer customer, Universe universe) {
    // Verify request params
    requestParams.verifyParams(universe);

    return submitUpgradeTask(
        TaskType.SystemdUpgrade,
        CustomerTask.TaskType.SystemdUpgrade,
        requestParams,
        customer,
        universe);
  }

  public UUID rebootUniverse(
      UpgradeTaskParams requestParams, Customer customer, Universe universe) {
    // Verify request params
    requestParams.verifyParams(universe);

    return submitUpgradeTask(
        TaskType.RebootUniverse,
        CustomerTask.TaskType.RebootUniverse,
        requestParams,
        customer,
        universe);
  }

  private UUID submitUpgradeTask(
      TaskType taskType,
      CustomerTask.TaskType customerTaskType,
      UpgradeTaskParams upgradeTaskParams,
      Customer customer,
      Universe universe) {
    return submitUpgradeTask(
        taskType, customerTaskType, upgradeTaskParams, customer, universe, null);
  }

  private UUID submitUpgradeTask(
      TaskType taskType,
      CustomerTask.TaskType customerTaskType,
      UpgradeTaskParams upgradeTaskParams,
      Customer customer,
      Universe universe,
      String customTaskName) {
    UUID taskUUID = commissioner.submit(taskType, upgradeTaskParams);
    log.info(
        "Submitted {} for {} : {}, task uuid = {}.",
        taskType,
        universe.getUniverseUUID(),
        universe.getName(),
        taskUUID);

    CustomerTask.create(
        customer,
        universe.getUniverseUUID(),
        taskUUID,
        CustomerTask.TargetType.Universe,
        customerTaskType,
        universe.getName(),
        customTaskName);
    log.info(
        "Saved task uuid {} in customer tasks table for universe {} : {}.",
        taskUUID,
        universe.getUniverseUUID(),
        universe.getName());
    return taskUUID;
  }

  private void checkHelmChartExists(String ybSoftwareVersion) {
    try {
      kubernetesManagerFactory.getManager().getHelmPackagePath(ybSoftwareVersion);
    } catch (RuntimeException e) {
      throw new PlatformServiceException(Status.BAD_REQUEST, e.getMessage());
    }
  }

  @VisibleForTesting
  static String generateTypeName(UserIntent userIntent, TlsToggleParams requestParams) {
    String baseTaskName = "TLS Toggle ";
    Boolean clientToNode =
        (userIntent.enableClientToNodeEncrypt == requestParams.enableClientToNodeEncrypt)
            ? null
            : requestParams.enableClientToNodeEncrypt;
    Boolean nodeToNode =
        (userIntent.enableNodeToNodeEncrypt == requestParams.enableNodeToNodeEncrypt)
            ? null
            : requestParams.enableNodeToNodeEncrypt;
    if (clientToNode != null && nodeToNode != null && !clientToNode.equals(nodeToNode)) {
      // one is off, other is on
      baseTaskName += "Client " + booleanToStr(clientToNode) + " Node " + booleanToStr(nodeToNode);
    } else {
      baseTaskName += booleanToStr(clientToNode == null ? nodeToNode : clientToNode);
    }
    return baseTaskName;
  }

  private static String booleanToStr(boolean toggle) {
    return toggle ? "ON" : "OFF";
  }
}
