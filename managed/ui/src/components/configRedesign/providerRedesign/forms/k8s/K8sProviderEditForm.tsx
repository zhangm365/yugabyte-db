/*
 * Copyright 2023 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
import React, { useState } from 'react';
import JsYaml from 'js-yaml';
import { Box, CircularProgress, FormHelperText, Typography } from '@material-ui/core';
import { FormProvider, SubmitHandler, useForm } from 'react-hook-form';
import { array, mixed, object, string } from 'yup';
import { toast } from 'react-toastify';
import { yupResolver } from '@hookform/resolvers/yup';
import { useQuery } from 'react-query';
import { useSelector } from 'react-redux';

import { ACCEPTABLE_CHARS } from '../../../../config/constants';
import { KubernetesProviderLabel, KubernetesProviderType, ProviderCode } from '../../constants';
import { DeleteRegionModal } from '../../components/DeleteRegionModal';
import { FieldGroup } from '../components/FieldGroup';
import { FieldLabel } from '../components/FieldLabel';
import { FormContainer } from '../components/FormContainer';
import { FormField } from '../components/FormField';
import { K8sCertIssuerType, RegionOperation } from '../configureRegion/constants';
import {
  K8sRegionField,
  ConfigureK8sRegionModal
} from '../configureRegion/ConfigureK8sRegionModal';
import {
  KUBERNETES_PROVIDER_OPTIONS,
  QUAY_IMAGE_REGISTRY,
  REDHAT_IMAGE_REGISTRY
} from './constants';
import { RegionList } from '../../components/RegionList';
import { YBButton } from '../../../../common/forms/fields';
import { YBDropZoneField } from '../../components/YBDropZone/YBDropZoneField';
import { YBInput, YBInputField, YBToggleField } from '../../../../../redesign/components';
import { YBReactSelectField } from '../../components/YBReactSelect/YBReactSelectField';
import {
  addItem,
  deleteItem,
  editItem,
  generateLowerCaseAlphanumericId,
  readFileAsText
} from '../utils';
import { EditProvider } from '../ProviderEditView';
import { getRegionlabel } from '../configureRegion/utils';
import {
  findExistingRegion,
  findExistingZone,
  getCertIssuerType,
  getDeletedRegions,
  getDeletedZones,
  getKubernetesProviderType
} from '../../utils';
import { VersionWarningBanner } from '../components/VersionWarningBanner';
import { api, suggestedKubernetesConfigQueryKey } from '../../../../../redesign/helpers/api';
import { YBLoading } from '../../../../common/indicators';
import { adaptSuggestedKubernetesConfig } from './utils';
import { KUBERNETES_REGIONS } from '../../providerRegionsData';

import {
  K8sAvailabilityZone,
  K8sAvailabilityZoneMutation,
  K8sProvider,
  K8sPullSecretFile,
  K8sRegion,
  K8sRegionMutation,
  YBProviderMutation
} from '../../types';

interface K8sProviderEditFormProps {
  editProvider: EditProvider;
  isProviderInUse: boolean;
  providerConfig: K8sProvider;
}

export interface K8sProviderEditFormFieldValues {
  dbNodePublicInternetAccess: boolean;
  editKubeConfigContent: boolean;
  editPullSecretContent: boolean;
  kubeConfigName: string;
  kubernetesImageRegistry: string;
  kubernetesProvider: { value: string; label: string };
  providerName: string;
  regions: K8sRegionField[];
  version: number;

  kubeConfigContent?: File;
  kubernetesPullSecretContent?: File;
  kubernetesPullSecretName?: string;
}

const VALIDATION_SCHEMA = object().shape({
  providerName: string()
    .required('Provider Name is required.')
    .matches(
      ACCEPTABLE_CHARS,
      'Provider name cannot contain special characters other than "-", and "_".'
    ),
  kubernetesProvider: object().required('Kubernetes provider is required.'),
  kubeConfigContent: mixed().when('editKubeConfigContent', {
    is: true,
    then: mixed().required('Please provide a Kube config file.')
  }),
  kubernetesPullSecretContent: mixed().when('editPullSecretContent', {
    is: true,
    then: mixed().required('Please provide a Kuberentes pull secret file.')
  }),
  kubernetesImageRegistry: string().required('Image registry is required.'),
  regions: array().min(1, 'Provider configurations must contain at least one region.')
});

const FORM_NAME = 'K8sProviderEditForm';

export const K8sProviderEditForm = ({
  editProvider,
  isProviderInUse,
  providerConfig
}: K8sProviderEditFormProps) => {
  const [isRegionFormModalOpen, setIsRegionFormModalOpen] = useState<boolean>(false);
  const [isDeleteRegionModalOpen, setIsDeleteRegionModalOpen] = useState<boolean>(false);
  const [regionSelection, setRegionSelection] = useState<K8sRegionField>();
  const [regionOperation, setRegionOperation] = useState<RegionOperation>(RegionOperation.ADD);
  const featureFlags = useSelector((state: any) => state.featureFlags);

  const defaultValues = constructDefaultFormValues(providerConfig);
  const formMethods = useForm<K8sProviderEditFormFieldValues>({
    defaultValues: defaultValues,
    resolver: yupResolver(VALIDATION_SCHEMA)
  });
  const kubernetesProviderType = getKubernetesProviderType(
    providerConfig.details.cloudInfo.kubernetes.kubernetesProvider
  );
  const enableSuggestedConfigFeature =
    kubernetesProviderType === KubernetesProviderType.MANAGED_SERVICE &&
    !!(featureFlags.test.enablePrefillKubeConfig || featureFlags.released.enablePrefillKubeConfig);
  const suggestedKubernetesConfigQuery = useQuery(
    suggestedKubernetesConfigQueryKey.ALL,
    () => api.fetchSuggestedKubernetesConfig(),
    {
      enabled: enableSuggestedConfigFeature
    }
  );

  if (suggestedKubernetesConfigQuery.isLoading || suggestedKubernetesConfigQuery.isIdle) {
    return <YBLoading />;
  }

  const onFormSubmit: SubmitHandler<K8sProviderEditFormFieldValues> = async (formValues) => {
    try {
      const providerPayload = await constructProviderPayload(formValues, providerConfig);
      try {
        await editProvider(providerPayload);
      } catch (_) {
        // Handled with `mutateOptions.onError`
      }
    } catch (error: any) {
      toast.error(error.message ?? error);
    }
  };

  const suggestedKubernetesConfig = suggestedKubernetesConfigQuery.data;
  const applySuggestedConfig = () => {
    if (!suggestedKubernetesConfig) {
      return;
    }
    const {
      kubernetesImageRegistry,
      kubernetesProvider,
      kubernetesPullSecretContent,
      providerName,
      regions
    } = adaptSuggestedKubernetesConfig(suggestedKubernetesConfig);

    formMethods.setValue('editPullSecretContent', true);
    formMethods.setValue('kubernetesPullSecretContent', kubernetesPullSecretContent);
    formMethods.setValue('kubernetesImageRegistry', kubernetesImageRegistry);
    formMethods.setValue('kubernetesProvider', kubernetesProvider);
    formMethods.setValue('providerName', providerName);
    formMethods.setValue('regions', regions, { shouldValidate: true });
  };
  const onFormReset = () => {
    formMethods.reset(defaultValues);
  };
  const showAddRegionFormModal = () => {
    setRegionSelection(undefined);
    setRegionOperation(RegionOperation.ADD);
    setIsRegionFormModalOpen(true);
  };
  const showEditRegionFormModal = (options?: { isExistingRegion: boolean }) => {
    setRegionOperation(
      options?.isExistingRegion ? RegionOperation.EDIT_EXISTING : RegionOperation.EDIT_NEW
    );
    setIsRegionFormModalOpen(true);
  };
  const showDeleteRegionModal = () => {
    setIsDeleteRegionModalOpen(true);
  };
  const hideDeleteRegionModal = () => {
    setIsDeleteRegionModalOpen(false);
  };
  const hideRegionFormModal = () => {
    setIsRegionFormModalOpen(false);
  };

  const regions = formMethods.watch('regions', defaultValues.regions);
  const setRegions = (regions: K8sRegionField[]) =>
    formMethods.setValue('regions', regions, { shouldValidate: true });
  const onRegionFormSubmit = (currentRegion: K8sRegionField) => {
    regionOperation === RegionOperation.ADD
      ? addItem(currentRegion, regions, setRegions)
      : editItem(currentRegion, regions, setRegions);
  };
  const onDeleteRegionSubmit = (currentRegion: K8sRegionField) =>
    deleteItem(currentRegion, regions, setRegions);

  const currentProviderVersion = formMethods.watch('version', defaultValues.version);
  const editKubeConfigContent = formMethods.watch(
    'editKubeConfigContent',
    defaultValues.editKubeConfigContent
  );
  const editPullSecretContent = formMethods.watch(
    'editPullSecretContent',
    defaultValues.editPullSecretContent
  );
  const kubernetesProviderTypeOptions = [
    ...(KUBERNETES_PROVIDER_OPTIONS[kubernetesProviderType] ?? []),
    ...KUBERNETES_PROVIDER_OPTIONS.k8sDeprecated
  ] as const;
  const existingRegions = providerConfig.regions.map((region) => region.code);
  const isFormDisabled =
    isProviderInUse || formMethods.formState.isValidating || formMethods.formState.isSubmitting;
  return (
    <Box display="flex" justifyContent="center">
      <FormProvider {...formMethods}>
        <FormContainer name="K8sProviderForm" onSubmit={formMethods.handleSubmit(onFormSubmit)}>
          {currentProviderVersion < providerConfig.version && (
            <VersionWarningBanner onReset={onFormReset} />
          )}
          <Box display="flex">
            <Typography variant="h3">Manage Kubernetes Provider Configuration</Typography>
            <Box marginLeft="auto">
              {enableSuggestedConfigFeature && (
                <YBButton
                  btnText="Autofill local cluster config"
                  btnClass="btn btn-default"
                  btnType="button"
                  onClick={() => applySuggestedConfig()}
                  disabled={isFormDisabled || !suggestedKubernetesConfig}
                  data-testid={`${FORM_NAME}-UseSuggestedConfigButton`}
                />
              )}
            </Box>
          </Box>
          <FormField providerNameField={true}>
            <FieldLabel>Provider Name</FieldLabel>
            <YBInputField
              control={formMethods.control}
              name="providerName"
              fullWidth
              disabled={isFormDisabled}
            />
          </FormField>
          <Box width="100%" display="flex" flexDirection="column" gridGap="32px">
            <FieldGroup heading="Cloud Info">
              <FormField>
                <FieldLabel>Kubernetes Provider Type</FieldLabel>
                <YBReactSelectField
                  control={formMethods.control}
                  name="kubernetesProvider"
                  options={kubernetesProviderTypeOptions}
                  defaultValue={defaultValues.kubernetesProvider}
                  isDisabled={isFormDisabled}
                />
              </FormField>
              <FormField>
                <FieldLabel>Image Registry</FieldLabel>
                <YBInputField
                  control={formMethods.control}
                  name="kubernetesImageRegistry"
                  placeholder={
                    kubernetesProviderType === KubernetesProviderType.OPEN_SHIFT
                      ? REDHAT_IMAGE_REGISTRY
                      : QUAY_IMAGE_REGISTRY
                  }
                  fullWidth
                  disabled={isFormDisabled}
                />
              </FormField>
              <FormField>
                <FieldLabel>Current Pull Secret Filepath</FieldLabel>
                <YBInput
                  value={providerConfig.details.cloudInfo.kubernetes.kubernetesPullSecret}
                  disabled={true}
                  fullWidth
                />
              </FormField>
              <FormField>
                <FieldLabel>Change Pull Secret File</FieldLabel>
                <YBToggleField
                  name="editPullSecretContent"
                  control={formMethods.control}
                  disabled={isFormDisabled}
                />
              </FormField>
              {editPullSecretContent && (
                <FormField>
                  <FieldLabel
                    infoTitle="Pull Secret"
                    infoContent="A pull secret file is required when pulling an image from a private container image registry or repository."
                  >
                    Pull Secret
                  </FieldLabel>
                  <YBDropZoneField
                    name="kubernetesPullSecretContent"
                    control={formMethods.control}
                    actionButtonText="Upload Pull Secret File"
                    multipleFiles={false}
                    showHelpText={false}
                    disabled={isFormDisabled}
                  />
                </FormField>
              )}
              <FormField>
                <FieldLabel>Current Kube Config Filepath</FieldLabel>
                <YBInput
                  value={providerConfig.details.cloudInfo.kubernetes.kubeConfig}
                  disabled={true}
                  fullWidth
                />
              </FormField>
              <FormField>
                <FieldLabel>Change Kube Config File</FieldLabel>
                <YBToggleField
                  name="editKubeConfigContent"
                  control={formMethods.control}
                  disabled={isFormDisabled}
                />
              </FormField>
              {editKubeConfigContent && (
                <FormField>
                  <FieldLabel>Kube Config</FieldLabel>
                  <YBDropZoneField
                    name="kubeConfigContent"
                    control={formMethods.control}
                    actionButtonText="Upload Kube Config File"
                    multipleFiles={false}
                    showHelpText={false}
                    disabled={isFormDisabled}
                  />
                </FormField>
              )}
            </FieldGroup>
            <FieldGroup
              heading="Regions"
              headerAccessories={
                regions.length > 0 ? (
                  <YBButton
                    btnIcon="fa fa-plus"
                    btnText="Add Region"
                    btnClass="btn btn-default"
                    btnType="button"
                    onClick={showAddRegionFormModal}
                    disabled={isFormDisabled}
                    data-testid={`${FORM_NAME}-AddRegionButton`}
                  />
                ) : null
              }
            >
              <RegionList
                providerCode={ProviderCode.KUBERNETES}
                regions={regions}
                existingRegions={existingRegions}
                setRegionSelection={setRegionSelection}
                showAddRegionFormModal={showAddRegionFormModal}
                showEditRegionFormModal={showEditRegionFormModal}
                showDeleteRegionModal={showDeleteRegionModal}
                disabled={isFormDisabled}
                isError={!!formMethods.formState.errors.regions}
              />
              {formMethods.formState.errors.regions?.message && (
                <FormHelperText error={true}>
                  {formMethods.formState.errors.regions?.message}
                </FormHelperText>
              )}
            </FieldGroup>
            {(formMethods.formState.isValidating || formMethods.formState.isSubmitting) && (
              <Box display="flex" gridGap="5px" marginLeft="auto">
                <CircularProgress size={16} color="primary" thickness={5} />
              </Box>
            )}
          </Box>
          <Box marginTop="16px">
            <YBButton
              btnText="Apply Changes"
              btnClass="btn btn-default save-btn"
              btnType="submit"
              disabled={isFormDisabled}
              data-testid={`${FORM_NAME}-SubmitButton`}
            />
            <YBButton
              btnText="Clear Changes"
              btnClass="btn btn-default"
              onClick={onFormReset}
              disabled={isFormDisabled}
              data-testid={`${FORM_NAME}-ClearButton`}
            />
          </Box>
        </FormContainer>
      </FormProvider>
      {isRegionFormModalOpen && (
        <ConfigureK8sRegionModal
          configuredRegions={regions}
          onClose={hideRegionFormModal}
          onRegionSubmit={onRegionFormSubmit}
          open={isRegionFormModalOpen}
          regionOperation={regionOperation}
          regionSelection={regionSelection}
        />
      )}
      <DeleteRegionModal
        region={regionSelection}
        onClose={hideDeleteRegionModal}
        open={isDeleteRegionModalOpen}
        deleteRegion={onDeleteRegionSubmit}
      />
    </Box>
  );
};

const constructDefaultFormValues = (
  providerConfig: K8sProvider
): Partial<K8sProviderEditFormFieldValues> => {
  return {
    dbNodePublicInternetAccess: !providerConfig.details.airGapInstall,
    editKubeConfigContent: false,
    editPullSecretContent: false,
    kubeConfigName: providerConfig.details.cloudInfo.kubernetes.kubeConfigName,
    kubernetesImageRegistry: providerConfig.details.cloudInfo.kubernetes.kubernetesImageRegistry,
    kubernetesProvider: {
      value: providerConfig.details.cloudInfo.kubernetes.kubernetesProvider,
      label: KubernetesProviderLabel[providerConfig.details.cloudInfo.kubernetes.kubernetesProvider]
    },
    providerName: providerConfig.name,
    regions: providerConfig.regions.map((region) => ({
      fieldId: generateLowerCaseAlphanumericId(),
      code: region.code,
      regionData: {
        value: { code: region.code, zoneOptions: [] },
        label: getRegionlabel(ProviderCode.KUBERNETES, region.code)
      },
      zones: region.zones.map((zone) => ({
        code: zone.code,
        certIssuerType: zone.details?.cloudInfo.kubernetes
          ? getCertIssuerType(zone.details?.cloudInfo.kubernetes)
          : K8sCertIssuerType.NONE,
        ...(zone.details?.cloudInfo.kubernetes && {
          certIssuerName:
            zone.details?.cloudInfo.kubernetes.certManagerClusterIssuer ??
            zone.details?.cloudInfo.kubernetes.certManagerIssuer,
          kubeDomain: zone.details.cloudInfo.kubernetes.kubeDomain,
          kubeNamespace: zone.details.cloudInfo.kubernetes.kubeNamespace,
          kubePodAddressTemplate: zone.details.cloudInfo.kubernetes.kubePodAddressTemplate,
          kubernetesStorageClass: zone.details.cloudInfo.kubernetes.kubernetesStorageClass,
          overrides: zone.details.cloudInfo.kubernetes.overrides
        })
      }))
    })),
    version: providerConfig.version
  };
};

const constructProviderPayload = async (
  formValues: K8sProviderEditFormFieldValues,
  providerConfig: K8sProvider
): Promise<YBProviderMutation> => {
  let kubernetesPullSecretContent = '';
  try {
    kubernetesPullSecretContent = formValues.kubernetesPullSecretContent
      ? (await readFileAsText(formValues.kubernetesPullSecretContent)) ?? ''
      : '';
  } catch (error) {
    throw new Error(`An error occurred while processing the pull secret file: ${error}`);
  }

  let kubernetesImagePullSecretName = '';
  try {
    // Type cast is required since JsYaml.load doesn't know the type of the input file
    const kubernetesPullSecretYAML = JsYaml.load(kubernetesPullSecretContent) as K8sPullSecretFile;
    kubernetesImagePullSecretName = kubernetesPullSecretYAML?.metadata?.name ?? '';
  } catch (error) {
    throw new Error(`An error occurred while reading the pull secret file as YAML: ${error}`);
  }

  let kubeConfigContent = '';
  try {
    kubeConfigContent = formValues.kubeConfigContent
      ? (await readFileAsText(formValues.kubeConfigContent)) ?? ''
      : '';
  } catch (error) {
    throw new Error(`An error occurred while processing the kube config file: ${error}`);
  }

  let regions = [];
  try {
    regions = await Promise.all(
      formValues.regions.map<Promise<K8sRegionMutation>>(async (regionFormValues) => {
        const existingRegion = findExistingRegion<K8sProvider, K8sRegion>(
          providerConfig,
          regionFormValues.code
        );

        // Preprocess the zones data collected from the form.
        // Store the zones data in a format compatiable with expected API payload.
        const preprocessedZones = await Promise.all(
          regionFormValues.zones.map<Promise<K8sAvailabilityZoneMutation>>(async (azFormValues) => {
            const existingZone = findExistingZone<K8sRegion, K8sAvailabilityZone>(
              existingRegion,
              azFormValues.code
            );
            return {
              ...(existingZone && {
                active: existingZone.active,
                uuid: existingZone.uuid
              }),
              code: azFormValues.code,
              name: azFormValues.code,
              details: {
                cloudInfo: {
                  [ProviderCode.KUBERNETES]: {
                    ...((!existingZone?.kubeconfigPath || azFormValues.editKubeConfigContent) &&
                      azFormValues.kubeConfigContent && {
                        kubeConfigContent:
                          (await readFileAsText(azFormValues.kubeConfigContent)) ?? ''
                      }),
                    kubeDomain: azFormValues.kubeDomain,
                    kubeNamespace: azFormValues.kubeNamespace,
                    kubePodAddressTemplate: azFormValues.kubePodAddressTemplate,
                    kubernetesStorageClass: azFormValues.kubernetesStorageClass,
                    overrides: azFormValues.overrides,
                    ...(azFormValues.certIssuerType === K8sCertIssuerType.CLUSTER_ISSUER && {
                      certManagerClusterIssuer: azFormValues.certIssuerName
                    }),
                    ...(azFormValues.certIssuerType === K8sCertIssuerType.ISSUER && {
                      certManagerIssuer: azFormValues.certIssuerName
                    })
                  }
                }
              }
            };
          })
        );

        const newRegion: K8sRegionMutation = {
          ...(existingRegion && {
            active: existingRegion.active,
            uuid: existingRegion.uuid
          }),
          code: regionFormValues.regionData.value.code,
          name: regionFormValues.regionData.label,
          longitude: KUBERNETES_REGIONS[regionFormValues.regionData.value.code]?.longitude,
          latitude: KUBERNETES_REGIONS[regionFormValues.regionData.value.code]?.latitude,
          zones: [
            ...preprocessedZones,
            ...getDeletedZones(existingRegion?.zones, regionFormValues.zones)
          ] as K8sAvailabilityZoneMutation[]
        };
        return newRegion;
      })
    );
  } catch (error) {
    throw new Error(
      `An error occurred while processing the zone level kube config files: ${error}`
    );
  }

  return {
    code: ProviderCode.KUBERNETES,
    name: formValues.providerName,
    details: {
      airGapInstall: !formValues.dbNodePublicInternetAccess,
      cloudInfo: {
        [ProviderCode.KUBERNETES]: {
          ...(formValues.editKubeConfigContent &&
            formValues.kubeConfigContent && {
              kubeConfigContent: kubeConfigContent,
              kubeConfigName: formValues.kubeConfigContent?.name ?? ''
            }),
          kubernetesImageRegistry: formValues.kubernetesImageRegistry,
          kubernetesProvider: formValues.kubernetesProvider.value,
          ...(formValues.editPullSecretContent &&
            formValues.kubernetesPullSecretContent && {
              kubernetesPullSecretContent: kubernetesPullSecretContent,
              kubernetesPullSecretName: formValues.kubernetesPullSecretContent.name ?? '',
              kubernetesImagePullSecretName: kubernetesImagePullSecretName
            })
        }
      }
    },
    regions: [
      ...regions,
      ...getDeletedRegions(providerConfig.regions, formValues.regions)
    ] as K8sRegionMutation[],
    version: formValues.version
  };
};
