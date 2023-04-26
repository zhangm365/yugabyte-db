package com.yugabyte.yw.controllers.handlers;

import static play.mvc.Http.Status.BAD_REQUEST;

import com.google.inject.Inject;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.tasks.subtasks.cloud.CloudImageBundleSetup;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.ProviderEditRestrictionManager;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.ImageBundle;
import com.yugabyte.yw.models.ImageBundleDetails;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.helpers.TaskType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImageBundleHandler {

  @Inject ProviderEditRestrictionManager providerEditRestrictionManager;
  @Inject Commissioner commissioner;

  public UUID create(Customer customer, Provider provider, ImageBundle bundle) {
    log.info("Creating image bundle {} for provider {}.", bundle.getName(), provider.getUuid());
    return providerEditRestrictionManager.tryEditProvider(
        provider.getUuid(), () -> doCreate(customer, provider, bundle));
  }

  public UUID doCreate(Customer customer, Provider provider, ImageBundle bundle) {
    CloudImageBundleSetup.Params taskParams = getCloudImageBundleParams(provider, bundle);
    UUID taskUUID = commissioner.submit(TaskType.CloudImageBundleSetup, taskParams);

    CustomerTask.create(
        customer,
        provider.getUuid(),
        taskUUID,
        CustomerTask.TargetType.Provider,
        CustomerTask.TaskType.CreateImageBundle,
        provider.getName());
    return taskUUID;
  }

  public void delete(UUID providerUUID, UUID iBUUID) {
    log.info("Deleting image bundle {} for provider {}.", iBUUID, providerUUID);
    providerEditRestrictionManager.tryEditProvider(
        providerUUID,
        () -> {
          int imageBundleCount = ImageBundle.getImageBundleCount(providerUUID);
          if (imageBundleCount == 1) {
            throw new PlatformServiceException(
                BAD_REQUEST,
                "Minimum one image bundle must be associated with provider. Cannot delete");
          }
          ImageBundle bundle = ImageBundle.getOrBadRequest(providerUUID, iBUUID);
          if (bundle.getUseAsDefault()) {
            log.error(
                "Image Bundle {} is currently marked as default. Cannot delete", bundle.getUuid());
            throw new PlatformServiceException(
                BAD_REQUEST,
                String.format(
                    "Image Bundle %s is currently marked as default. Cannot delete",
                    bundle.getUuid()));
          }
          bundle.delete();
        });
  }

  public ImageBundle edit(Provider provider, UUID iBUUID, ImageBundle bundle) {
    log.info("Editing image bundle {} for provider {}.", bundle.getName(), provider.getUuid());
    return providerEditRestrictionManager.tryEditProvider(
        provider.getUuid(), () -> doEdit(provider, iBUUID, bundle));
  }

  public ImageBundle doEdit(Provider provider, UUID iBUUID, ImageBundle bundle) {
    ImageBundleDetails details = bundle.getDetails();
    CloudImageBundleSetup.verifyImageBundleDetails(details, provider);
    ImageBundle oBundle = ImageBundle.getOrBadRequest(iBUUID);
    if (oBundle.getUseAsDefault() && !bundle.getUseAsDefault()) {
      throw new PlatformServiceException(
          BAD_REQUEST,
          String.format(
              "One of the image bundle should be default for the provider %s", provider.getUuid()));
    } else if (!oBundle.getUseAsDefault() && bundle.getUseAsDefault()) {
      // Change the default image bundle for the provider.
      ImageBundle prevDefaultImageBundle = ImageBundle.getDefaultForProvider(provider.getUuid());
      prevDefaultImageBundle.setUseAsDefault(false);
      prevDefaultImageBundle.save();
      oBundle.setUseAsDefault(bundle.getUseAsDefault());
    }
    oBundle.setDetails(bundle.getDetails());
    oBundle.save();
    return oBundle;
  }

  public CloudImageBundleSetup.Params getCloudImageBundleParams(
      Provider provider, ImageBundle bundle) {
    CloudImageBundleSetup.Params params = new CloudImageBundleSetup.Params();
    List<ImageBundle> imageBundles = new ArrayList<>();
    imageBundles.add(bundle);
    params.providerUUID = provider.getUuid();
    params.imageBundles = imageBundles;

    return params;
  }
}
