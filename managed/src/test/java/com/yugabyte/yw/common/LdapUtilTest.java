package com.yugabyte.yw.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Users;
import org.apache.directory.api.ldap.model.cursor.EntryCursor;
import org.apache.directory.api.ldap.model.entry.DefaultEntry;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.exception.LdapAuthenticationException;
import org.apache.directory.api.ldap.model.exception.LdapNoSuchObjectException;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

public class LdapUtilTest extends FakeDBApplication {

  private LdapUtil ldapUtil;
  private EntryCursor entryCursor;
  private LdapNetworkConnection ldapNetworkConnection;

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
  }

  private void setupTest() throws Exception {
    ldapUtil = spy(LdapUtil.class);
    entryCursor = mock(EntryCursor.class);
    ldapNetworkConnection = mock(LdapNetworkConnection.class);

    doReturn(ldapNetworkConnection).when(ldapUtil).createNewLdapConnection(any());
    doNothing().when(ldapNetworkConnection).bind(anyString(), anyString());
    doReturn(entryCursor)
        .when(ldapNetworkConnection)
        .search(anyString(), anyString(), any(), anyString());
  }

  @Test
  public void testAuthViaLDAPSimpleBindSuccess() throws Exception {
    setupTest();
    Users user =
        ldapUtil.authViaLDAP(
            "test-user",
            "password",
            new LdapUtil.LdapConfiguration(
                "ldapUrl", 389, "base-dn", "", "", false, false, false, "", "", "", false));

    assertNotNull(user);
    assertEquals("test-user", user.getEmail());
  }

  @Test
  public void testAuthViaLDAPSimpleBindFailure() throws Exception {
    setupTest();
    doThrow(LdapAuthenticationException.class)
        .doNothing()
        .when(ldapNetworkConnection)
        .bind(anyString(), anyString());

    assertThrows(
        PlatformServiceException.class,
        () ->
            ldapUtil.authViaLDAP(
                "test-user",
                "password",
                new LdapUtil.LdapConfiguration(
                    "ldapUrl", 389, "base-dn", "", "cn=", false, false, false, "", "", "", false)));
  }

  @Test
  public void testAuthViaLDAPSimpleBindWithUserDeleted() throws Exception {
    setupTest();
    Customer customer = ModelFactory.testCustomer();
    Users user = ModelFactory.testUser(customer);
    user.setUserType(Users.UserType.ldap);
    user.update();
    doThrow(LdapNoSuchObjectException.class)
        .doNothing()
        .when(ldapNetworkConnection)
        .bind(anyString(), anyString());

    assertThrows(
        PlatformServiceException.class,
        () ->
            ldapUtil.authViaLDAP(
                user.getEmail(),
                "password",
                new LdapUtil.LdapConfiguration(
                    "ldapUrl", 389, "base-dn", "", "cn=", false, false, false, "", "", "", false)));
    assertNull(Users.getByEmail(user.getEmail()));
  }

  @Test
  public void testAuthViaLDAPReadRole() throws Exception {
    setupTest();
    Entry entry = new DefaultEntry();
    entry.add("yugabytePlatformRole", "BackupAdmin");
    doNothing().when(ldapNetworkConnection).unBind();
    when(entryCursor.next()).thenReturn(true).thenReturn(false);
    when(entryCursor.get()).thenReturn(entry);

    Users user =
        ldapUtil.authViaLDAP(
            "test-user",
            "password",
            new LdapUtil.LdapConfiguration(
                "ldapUrl",
                389,
                "base-dn",
                "",
                "cn=",
                false,
                false,
                false,
                "service_account",
                "service_password",
                "",
                false));

    assertNotNull(user);
    assertEquals(Users.Role.BackupAdmin, user.getRole());
  }

  @Test
  public void testAuthViaLDAPUpdateRole() throws Exception {
    setupTest();
    Customer customer = ModelFactory.testCustomer();
    Users user = ModelFactory.testUser(customer);
    user.setLdapSpecifiedRole(true);
    user.setUserType(Users.UserType.ldap);
    user.update();
    Entry entry = new DefaultEntry();
    entry.add("yugabytePlatformRole", "BackupAdmin");
    doNothing().when(ldapNetworkConnection).unBind();
    when(entryCursor.next()).thenReturn(true).thenReturn(false);
    when(entryCursor.get()).thenReturn(entry);

    Users updatedUser =
        ldapUtil.authViaLDAP(
            user.getEmail(),
            "password",
            new LdapUtil.LdapConfiguration(
                "ldapUrl",
                389,
                "base-dn",
                "",
                "cn=",
                false,
                false,
                false,
                "service_account",
                "service_password",
                "",
                false));

    assertNotNull(updatedUser);
    assertEquals(Users.Role.BackupAdmin, updatedUser.getRole());
  }

  @Test
  public void testAuthViaLDAPWithOldUser() throws Exception {
    setupTest();
    Customer customer = ModelFactory.testCustomer();
    Users user = ModelFactory.testUser(customer);
    user.setLdapSpecifiedRole(true);
    user.setUserType(Users.UserType.ldap);
    user.update();
    Entry entry = new DefaultEntry();
    entry.add("yugabytePlatformRole", "Admin");
    doNothing().when(ldapNetworkConnection).unBind();
    when(entryCursor.next()).thenReturn(true).thenReturn(false);
    when(entryCursor.get()).thenReturn(entry);

    Users oldUser =
        ldapUtil.authViaLDAP(
            user.getEmail(),
            "password",
            new LdapUtil.LdapConfiguration(
                "ldapUrl",
                389,
                "base-dn",
                "",
                "cn=",
                false,
                false,
                false,
                "service_account",
                "service_password",
                "",
                false));

    assertEquals(user, oldUser);
  }

  @Test
  public void testAuthViaLDAPWithSearchAndBindSuccess() throws Exception {
    setupTest();
    Entry entry = new DefaultEntry();
    entry.add("yugabytePlatformRole", "Admin");
    entry.add("distinguishedName", "CN=test-user");
    doNothing().when(ldapNetworkConnection).unBind();
    when(entryCursor.next()).thenReturn(true).thenReturn(false);
    when(entryCursor.get()).thenReturn(entry);

    Users user =
        ldapUtil.authViaLDAP(
            "test-user",
            "password",
            new LdapUtil.LdapConfiguration(
                "ldapUrl",
                389,
                "base-dn",
                "",
                "cn=",
                false,
                false,
                true,
                "service_account",
                "service_password",
                "search-attribute",
                false));

    assertNotNull(user);
    assertEquals("test-user", user.getEmail());
  }

  @Test
  public void testAuthViaLDAPWithSearchAndBindWithoutServiceAccountAndSearchAttribute()
      throws Exception {
    setupTest();
    assertThrows(
        PlatformServiceException.class,
        () ->
            ldapUtil.authViaLDAP(
                "test-user",
                "password",
                new LdapUtil.LdapConfiguration(
                    "ldapUrl", 389, "base-dn", "", "cn=", false, false, true, "", "", "", false)));
  }
}
