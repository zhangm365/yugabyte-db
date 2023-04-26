// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.controllers;

import static com.yugabyte.yw.models.Users.Role;
import static com.yugabyte.yw.models.Users.UserType;

import com.google.common.collect.ImmutableList;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.common.password.PasswordPolicyService;
import com.yugabyte.yw.common.user.UserService;
import com.yugabyte.yw.forms.PlatformResults;
import com.yugabyte.yw.forms.PlatformResults.YBPSuccess;
import com.yugabyte.yw.forms.UserProfileFormData;
import com.yugabyte.yw.forms.UserRegisterFormData;
import com.yugabyte.yw.models.Audit;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Users;
import com.yugabyte.yw.models.extended.UserWithFeatures;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.logstash.logback.encoder.org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.Form;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;

@Api(
    value = "User management",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
public class UsersController extends AuthenticatedController {

  public static final Logger LOG = LoggerFactory.getLogger(UsersController.class);
  private static final List<String> specialCharacters =
      ImmutableList.of("!", "@", "#", "$", "%", "^", "&", "*");

  @Inject private RuntimeConfigFactory runtimeConfigFactory;

  private final PasswordPolicyService passwordPolicyService;
  private final UserService userService;
  private final TokenAuthenticator tokenAuthenticator;

  @Inject
  public UsersController(
      PasswordPolicyService passwordPolicyService,
      UserService userService,
      TokenAuthenticator tokenAuthenticator) {
    this.passwordPolicyService = passwordPolicyService;
    this.userService = userService;
    this.tokenAuthenticator = tokenAuthenticator;
  }

  /**
   * GET endpoint for listing the provider User.
   *
   * @return JSON response with user.
   */
  @ApiOperation(
      value = "Get a user's details",
      nickname = "getUserDetails",
      response = UserWithFeatures.class)
  public Result index(UUID customerUUID, UUID userUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Users user = Users.getOrBadRequest(userUUID);
    return PlatformResults.withData(userService.getUserWithFeatures(customer, user));
  }

  /**
   * GET endpoint for listing all available Users for a customer
   *
   * @return JSON response with users belonging to the customer.
   */
  @ApiOperation(
      value = "List all users",
      nickname = "listUsers",
      response = UserWithFeatures.class,
      responseContainer = "List")
  public Result list(UUID customerUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    List<Users> users = Users.getAll(customerUUID);
    List<UserWithFeatures> userWithFeaturesList =
        users.stream()
            .map(user -> userService.getUserWithFeatures(customer, user))
            .collect(Collectors.toList());
    return PlatformResults.withData(userWithFeaturesList);
  }

  /**
   * POST endpoint for creating new Users.
   *
   * @return JSON response of newly created user.
   */
  @ApiOperation(value = "Create a user", nickname = "createUser", response = UserWithFeatures.class)
  @ApiImplicitParams({
    @ApiImplicitParam(
        name = "User",
        value = "Details of the new user",
        required = true,
        dataType = "com.yugabyte.yw.forms.UserRegisterFormData",
        paramType = "body")
  })
  public Result create(UUID customerUUID, Http.Request request) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Form<UserRegisterFormData> form =
        formFactory.getFormDataOrBadRequest(request, UserRegisterFormData.class);

    UserRegisterFormData formData = form.get();

    if (runtimeConfigFactory.globalRuntimeConf().getBoolean("yb.security.use_oauth")) {
      byte[] passwordOidc = new byte[16];
      new Random().nextBytes(passwordOidc);
      String generatedPassword = new String(passwordOidc, Charset.forName("UTF-8"));
      // To be consistent with password policy
      Integer randomInt = new Random().nextInt(26);
      String lowercaseLetter = String.valueOf((char) (randomInt + 'a'));
      String uppercaseLetter = lowercaseLetter.toUpperCase();
      generatedPassword +=
          (specialCharacters.get(new Random().nextInt(specialCharacters.size()))
              + lowercaseLetter
              + uppercaseLetter
              + String.valueOf(randomInt));
      formData.setPassword(generatedPassword); // Password is not used.
    }

    passwordPolicyService.checkPasswordPolicy(customerUUID, formData.getPassword());
    Users user =
        Users.create(
            formData.getEmail(), formData.getPassword(), formData.getRole(), customerUUID, false);
    auditService()
        .createAuditEntryWithReqBody(
            request,
            Audit.TargetType.User,
            Objects.toString(user.getUuid(), null),
            Audit.ActionType.Create,
            Json.toJson(formData));
    return PlatformResults.withData(userService.getUserWithFeatures(customer, user));
  }

  /**
   * DELETE endpoint for deleting an existing user.
   *
   * @return JSON response on whether or not delete user was successful or not.
   */
  @ApiOperation(
      value = "Delete a user",
      nickname = "deleteUser",
      notes = "Deletes the specified user. Note that you can't delete a customer's primary user.",
      response = YBPSuccess.class)
  public Result delete(UUID customerUUID, UUID userUUID, Http.Request request) {
    Users user = Users.getOrBadRequest(userUUID);
    checkUserOwnership(customerUUID, userUUID, user);
    if (user.isPrimary()) {
      throw new PlatformServiceException(
          BAD_REQUEST,
          String.format(
              "Cannot delete primary user %s for customer %s", userUUID.toString(), customerUUID));
    }
    if (user.delete()) {
      auditService()
          .createAuditEntry(
              request, Audit.TargetType.User, userUUID.toString(), Audit.ActionType.Delete);
      return YBPSuccess.empty();
    } else {
      throw new PlatformServiceException(
          INTERNAL_SERVER_ERROR, "Unable to delete user UUID: " + userUUID);
    }
  }

  private void checkUserOwnership(UUID customerUUID, UUID userUUID, Users user) {
    Customer.getOrBadRequest(customerUUID);
    if (!user.getCustomerUUID().equals(customerUUID)) {
      throw new PlatformServiceException(
          BAD_REQUEST,
          String.format(
              "User UUID %s does not belong to customer %s",
              userUUID.toString(), customerUUID.toString()));
    }
  }

  /**
   * PUT endpoint for changing the role of an existing user.
   *
   * @return JSON response on whether role change was successful or not.
   */
  @ApiOperation(
      value = "Change a user's role",
      nickname = "updateUserRole",
      response = YBPSuccess.class)
  public Result changeRole(UUID customerUUID, UUID userUUID, String role, Http.Request request) {
    Users user = Users.getOrBadRequest(userUUID);
    checkUserOwnership(customerUUID, userUUID, user);
    if (UserType.ldap == user.getUserType() && user.isLdapSpecifiedRole()) {
      throw new PlatformServiceException(BAD_REQUEST, "Cannot change role for LDAP user.");
    }
    if (Role.SuperAdmin == user.getRole()) {
      throw new PlatformServiceException(BAD_REQUEST, "Cannot change super admin role.");
    }
    user.setRole(Role.valueOf(role));
    user.save();
    auditService()
        .createAuditEntryWithReqBody(
            request, Audit.TargetType.User, userUUID.toString(), Audit.ActionType.ChangeUserRole);
    return YBPSuccess.empty();
  }

  /**
   * PUT endpoint for changing the password of an existing user.
   *
   * @return JSON response on whether role change was successful or not.
   */
  @ApiOperation(
      value = "Change a user's password",
      nickname = "updateUserPassword",
      response = YBPSuccess.class)
  @ApiImplicitParams({
    @ApiImplicitParam(
        name = "Users",
        value = "User data containing the new password",
        required = true,
        dataType = "com.yugabyte.yw.forms.UserRegisterFormData",
        paramType = "body")
  })
  public Result changePassword(UUID customerUUID, UUID userUUID, Http.Request request) {
    Users user = Users.getOrBadRequest(userUUID);
    if (UserType.ldap == user.getUserType()) {
      throw new PlatformServiceException(BAD_REQUEST, "Can't change password for LDAP user.");
    }
    checkUserOwnership(customerUUID, userUUID, user);
    Form<UserRegisterFormData> form =
        formFactory.getFormDataOrBadRequest(request, UserRegisterFormData.class);

    UserRegisterFormData formData = form.get();
    passwordPolicyService.checkPasswordPolicy(customerUUID, formData.getPassword());
    if (formData.getEmail().equals(user.getEmail())) {
      if (formData.getPassword().equals(formData.getConfirmPassword())) {
        user.setPassword(formData.getPassword());
        user.save();
        auditService()
            .createAuditEntry(
                request,
                Audit.TargetType.User,
                userUUID.toString(),
                Audit.ActionType.ChangeUserPassword);
        return YBPSuccess.empty();
      }
    }
    throw new PlatformServiceException(BAD_REQUEST, "Invalid user credentials.");
  }

  private Users getLoggedInUser(Http.Request request) {
    Users user = tokenAuthenticator.getCurrentAuthenticatedUser(request);
    return user;
  }

  private boolean checkUpdateProfileAccessForPasswordChange(UUID userUUID, Http.Request request) {
    Users user = getLoggedInUser(request);

    if (user == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Unable To Authenticate User");
    }
    return userUUID.equals(user.getUuid());
  }

  /**
   * PUT endpoint for updating the user profile.
   *
   * @return JSON response of the updated User.
   */
  @ApiOperation(
      value = "Update a user's profile",
      nickname = "UpdateUserProfile",
      response = Users.class)
  @ApiImplicitParams({
    @ApiImplicitParam(
        name = "Users",
        value = "User data in profile to be updated",
        required = true,
        dataType = "com.yugabyte.yw.forms.UserProfileFormData",
        paramType = "body")
  })
  public Result updateProfile(UUID customerUUID, UUID userUUID, Http.Request request) {

    Users user = Users.getOrBadRequest(userUUID);
    checkUserOwnership(customerUUID, userUUID, user);
    Form<UserProfileFormData> form =
        formFactory.getFormDataOrBadRequest(request, UserProfileFormData.class);

    UserProfileFormData formData = form.get();

    if (StringUtils.isNotEmpty(formData.getPassword())) {
      if (UserType.ldap == user.getUserType()) {
        throw new PlatformServiceException(BAD_REQUEST, "Can't change password for LDAP user.");
      }

      if (!checkUpdateProfileAccessForPasswordChange(userUUID, request)) {
        throw new PlatformServiceException(
            BAD_REQUEST, "Only the User can change his/her own password.");
      }

      passwordPolicyService.checkPasswordPolicy(customerUUID, formData.getPassword());
      if (!formData.getPassword().equals(formData.getConfirmPassword())) {
        throw new PlatformServiceException(
            BAD_REQUEST, "Password and confirm password do not match.");
      }
      user.setPassword(formData.getPassword());
    }

    Users loggedInUser = getLoggedInUser(request);
    if ((loggedInUser.getRole() == Role.ReadOnly || loggedInUser.getRole() == Role.BackupAdmin)
        && formData.getRole() != user.getRole()) {
      throw new PlatformServiceException(
          BAD_REQUEST, "ReadOnly/BackupAdmin users can't change their assigned roles");
    }

    if ((loggedInUser.getRole() == Role.ReadOnly || loggedInUser.getRole() == Role.BackupAdmin)
        && !formData.getTimezone().equals(user.getTimezone())) {
      throw new PlatformServiceException(
          BAD_REQUEST, "ReadOnly/BackupAdmin users can't change their timezone");
    }

    if (StringUtils.isNotEmpty(formData.getTimezone())
        && !formData.getTimezone().equals(user.getTimezone())) {
      user.setTimezone(formData.getTimezone());
    }
    if (formData.getRole() != user.getRole()) {
      if (Role.SuperAdmin == user.getRole()) {
        throw new PlatformServiceException(BAD_REQUEST, "Can't change super admin role.");
      }

      if (formData.getRole() == Role.SuperAdmin) {
        throw new PlatformServiceException(
            BAD_REQUEST, "Can't Assign the role of " + "SuperAdmin to another user.");
      }
      user.setRole(formData.getRole());
    }
    auditService()
        .createAuditEntryWithReqBody(
            request,
            Audit.TargetType.User,
            userUUID.toString(),
            Audit.ActionType.Update,
            Json.toJson(formData));
    user.save();
    return ok(Json.toJson(user));
  }
}
