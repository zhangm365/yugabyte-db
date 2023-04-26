/*
 * Copyright 2022 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.common.config;

import com.google.common.collect.ImmutableList;
import com.yugabyte.yw.common.config.ConfKeyInfo.ConfKeyTags;
import com.yugabyte.yw.forms.RuntimeConfigFormData.ScopedConfig.ScopeType;
import java.time.Duration;
import java.util.List;

public class GlobalConfKeys extends RuntimeConfigKeysModule {

  public static final ConfKeyInfo<Integer> maxParallelNodeChecks =
      new ConfKeyInfo<>(
          "yb.health.max_num_parallel_node_checks",
          ScopeType.GLOBAL,
          "Max Number of Parallel Node Checks",
          "Number of parallel node checks, spawned as part of universes health check process",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> logScriptOutput =
      new ConfKeyInfo<>(
          "yb.ha.logScriptOutput",
          ScopeType.GLOBAL,
          "Log Script Output For YBA HA Feature",
          "To log backup restore script output for debugging issues",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> useKubectl =
      new ConfKeyInfo<>(
          "yb.use_kubectl",
          ScopeType.GLOBAL,
          "Use Kubectl",
          "Use java library instead of spinning up kubectl process.",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  // TODO(): Add correct metadata
  public static final ConfKeyInfo<Boolean> useNewHelmNaming =
      new ConfKeyInfo<>(
          "yb.use_new_helm_naming",
          ScopeType.GLOBAL,
          "Use New Helm Naming",
          "TODO - Leave this for feature owners to fill in",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static final ConfKeyInfo<Boolean> useOauth =
      new ConfKeyInfo<>(
          "yb.security.use_oauth",
          ScopeType.GLOBAL,
          "Use OAUTH",
          "Hidden because this key has dedicated UI",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static final ConfKeyInfo<String> ybSecurityType =
      new ConfKeyInfo<>(
          "yb.security.type",
          ScopeType.GLOBAL,
          "YB Security Type",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static final ConfKeyInfo<String> ybClientID =
      new ConfKeyInfo<>(
          "yb.security.clientID",
          ScopeType.GLOBAL,
          "YB Client ID",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static final ConfKeyInfo<String> ybSecuritySecret =
      new ConfKeyInfo<>(
          "yb.security.secret",
          ScopeType.GLOBAL,
          "YB Security Secret",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static final ConfKeyInfo<String> discoveryURI =
      new ConfKeyInfo<>(
          "yb.security.discoveryURI",
          ScopeType.GLOBAL,
          "Discovery URI",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static final ConfKeyInfo<String> oidcScope =
      new ConfKeyInfo<>(
          "yb.security.oidcScope",
          ScopeType.GLOBAL,
          "OIDC Scope",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static final ConfKeyInfo<String> oidcEmailAttribute =
      new ConfKeyInfo<>(
          "yb.security.oidcEmailAttribute",
          ScopeType.GLOBAL,
          "OIDC Email Attribute",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static final ConfKeyInfo<Boolean> ssh2Enabled =
      new ConfKeyInfo<>(
          "yb.security.ssh2_enabled",
          ScopeType.GLOBAL,
          "Enable SSH2",
          "Flag for enabling ssh2 on YBA",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> enableCustomHooks =
      new ConfKeyInfo<>(
          "yb.security.custom_hooks.enable_custom_hooks",
          ScopeType.GLOBAL,
          "Enable Custom Hooks",
          "Flag for enabling custom hooks on YBA",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> enableSudo =
      new ConfKeyInfo<>(
          "yb.security.custom_hooks.enable_sudo",
          ScopeType.GLOBAL,
          "Enable SUDO",
          "Flag for enabling sudo access while running custom hooks",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> enabledApiTriggerHooks =
      new ConfKeyInfo<>(
          "yb.security.custom_hooks.enable_api_triggered_hooks",
          ScopeType.GLOBAL,
          "Enable API Triggered Hooks",
          "Flag for enabling API Triggered Hooks on YBA",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> disableXxHashChecksum =
      new ConfKeyInfo<>(
          "yb.backup.disable_xxhash_checksum",
          ScopeType.GLOBAL,
          "Disable XX Hash Checksum",
          "Flag for disabling xxhsum based checksums for computing the backup",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  // TODO(): Add correct metadata
  public static final ConfKeyInfo<Boolean> auditVerifyLogging =
      new ConfKeyInfo<>(
          "yb.audit.log.verifyLogging",
          ScopeType.GLOBAL,
          "Audit Verify Logging",
          "TODO - Leave this for feature owners to fill in",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.BETA));
  // TODO(): Add correct metadata
  public static final ConfKeyInfo<Boolean> auditOutputToStdout =
      new ConfKeyInfo<>(
          "yb.audit.log.outputToStdout",
          ScopeType.GLOBAL,
          "Audit Log Output to Stdout",
          "TODO - Leave this for feature owners to fill in",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.BETA));
  // TODO(): Add correct metadata
  public static final ConfKeyInfo<Boolean> auditOutputToFile =
      new ConfKeyInfo<>(
          "yb.audit.log.outputToFile",
          ScopeType.GLOBAL,
          "Audit Log Output to File",
          "TODO - Leave this for feature owners to fill in",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.BETA));
  // TODO(): Add correct metadata
  public static final ConfKeyInfo<String> auditRolloverPattern =
      new ConfKeyInfo<>(
          "yb.audit.log.rolloverPattern",
          ScopeType.GLOBAL,
          "Audit Log Rollover Pattern",
          "TODO - Leave this for feature owners to fill in",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.BETA));
  // TODO(): Add correct metadata
  public static final ConfKeyInfo<String> auditMaxHistory =
      new ConfKeyInfo<>(
          "yb.audit.log.maxHistory",
          ScopeType.GLOBAL,
          "Audit Log Max History",
          "TODO - Leave this for feature owners to fill in",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static final ConfKeyInfo<Boolean> supportBundleK8sEnabled =
      new ConfKeyInfo<>(
          "yb.support_bundle.k8s_enabled",
          ScopeType.GLOBAL,
          "Enable K8s Support Bundle",
          "This config lets you enable support bundle creation on k8s universes.",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> supportBundleOnPremEnabled =
      new ConfKeyInfo<>(
          "yb.support_bundle.onprem_enabled",
          ScopeType.GLOBAL,
          "Enable On Prem Support Bundle",
          "This config lets you enable support bundle creation for onprem universes.",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> snapshotCreationMaxAttempts =
      new ConfKeyInfo<>(
          "yb.snapshot_creation.max_attempts",
          ScopeType.GLOBAL,
          "Snapshot creation max attempts",
          "Max attempts while waiting for AWS Snapshot Creation",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> snapshotCreationDelay =
      new ConfKeyInfo<>(
          "yb.snapshot_creation.delay",
          ScopeType.GLOBAL,
          "Snapshot creation delay",
          "Delay per attempt while waiting for AWS Snapshot Creation",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> runtimeConfigUiEnableForAll =
      new ConfKeyInfo<>(
          "yb.runtime_conf_ui.enable_for_all",
          ScopeType.GLOBAL,
          "Runtime Config UI",
          "Allows users to view the runtime configuration properties via UI",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> isPlatformDowngradeAllowed =
      new ConfKeyInfo<>(
          "yb.is_platform_downgrade_allowed",
          ScopeType.GLOBAL,
          "Allow Platform Downgrade",
          "Allow Downgrading the Platform Version",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Duration> ybcUpgradeInterval =
      new ConfKeyInfo<>(
          "ybc.upgrade.scheduler_interval",
          ScopeType.GLOBAL,
          "YBC Upgrade Interval",
          "YBC Upgrade interval",
          ConfDataType.DurationType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> ybcUniverseBatchSize =
      new ConfKeyInfo<>(
          "ybc.upgrade.universe_batch_size",
          ScopeType.GLOBAL,
          "YBC Universe Upgrade Batch Size",
          "The number of maximum universes on which ybc will be upgraded simultaneously",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> ybcNodeBatchSize =
      new ConfKeyInfo<>(
          "ybc.upgrade.node_batch_size",
          ScopeType.GLOBAL,
          "YBC Node Upgrade Batch Size",
          "The number of maximum nodes on which ybc will be upgraded simultaneously",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<String> ybcStableVersion =
      new ConfKeyInfo<>(
          "ybc.releases.stable_version",
          ScopeType.GLOBAL,
          "YBC Stable Release",
          "Stable version for Yb-Controller",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> ybcAdminOperationTimeoutMs =
      new ConfKeyInfo<>(
          "ybc.timeout.admin_operation_timeout_ms",
          ScopeType.GLOBAL,
          "YBC admin operation timeout",
          "YBC client timeout in milliseconds for admin operations",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> ybcSocketReadTimeoutMs =
      new ConfKeyInfo<>(
          "ybc.timeout.socket_read_timeout_ms",
          ScopeType.GLOBAL,
          "YBC socket read timeout",
          "YBC client socket read timeout in milliseconds",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> ybcOperationTimeoutMs =
      new ConfKeyInfo<>(
          "ybc.timeout.operation_timeout_ms",
          ScopeType.GLOBAL,
          "YBC operation timeout",
          "YBC client timeout in milliseconds for operations",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> enableCertReload =
      new ConfKeyInfo<>(
          "yb.features.cert_reload.enabled",
          ScopeType.GLOBAL,
          "Enable Cert Reload",
          "Enable hot reload of TLS certificates without restart of the DB nodes",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> cmdOutputDelete =
      new ConfKeyInfo<>(
          "yb.logs.cmdOutputDelete",
          ScopeType.GLOBAL,
          "Delete Output File",
          "Flag to delete temp output file created by the shell command",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> shellOutputRetentationHours =
      new ConfKeyInfo<>(
          "yb.logs.shell.output_retention_hours",
          ScopeType.GLOBAL,
          "Shell Output Retention Duration",
          "Output logs for shell commands are written to tmp folder."
              + "This setting defines how long will we wait before garbage collecting them.",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Long> shellMaxOutputDirSize =
      new ConfKeyInfo<>(
          "yb.logs.shell.output_dir_max_size",
          ScopeType.GLOBAL,
          "Shell Output Max Directory Size",
          "Output logs for shell commands are written to tmp folder."
              + "This setting defines rotation policy based on directory size.",
          ConfDataType.BytesType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Long> logsMaxMsgSize =
      new ConfKeyInfo<>(
          "yb.logs.max_msg_size",
          ScopeType.GLOBAL,
          "Max Size of each log message",
          "We limit the length of each log line as sometimes we dump entire output"
              + " of script. If you want to debug something specific and the script output is"
              + "getting truncated in application log then increase this limit",
          ConfDataType.BytesType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Duration> kmsRefreshInterval =
      new ConfKeyInfo<>(
          "yb.kms.refresh_interval",
          ScopeType.GLOBAL,
          "KMS Refresh Interval",
          "Default refresh interval for the KMS providers.",
          ConfDataType.DurationType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  // TODO() Add metadata
  public static final ConfKeyInfo<Boolean> startMasterOnStopNode =
      new ConfKeyInfo<>(
          "yb.start_master_on_stop_node",
          ScopeType.GLOBAL,
          "Start Master On Stop Node",
          "TODO",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static ConfKeyInfo<Boolean> useLdap =
      new ConfKeyInfo<>(
          "yb.security.ldap.use_ldap",
          ScopeType.GLOBAL,
          "Use LDAP",
          "Hidden because this key has dedicated UI",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static ConfKeyInfo<String> ldapUrl =
      new ConfKeyInfo<>(
          "yb.security.ldap.ldap_url",
          ScopeType.GLOBAL,
          "LDAP URL",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static ConfKeyInfo<String> ldapPort =
      new ConfKeyInfo<>(
          "yb.security.ldap.ldap_port",
          ScopeType.GLOBAL,
          "LDAP Port",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static ConfKeyInfo<String> ldapBaseDn =
      new ConfKeyInfo<>(
          "yb.security.ldap.ldap_basedn",
          ScopeType.GLOBAL,
          "LDAP Base DN",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static ConfKeyInfo<String> ldapDnPrefix =
      new ConfKeyInfo<>(
          "yb.security.ldap.ldap_dn_prefix",
          ScopeType.GLOBAL,
          "LDAP DN Prefix",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static ConfKeyInfo<String> ldapCustomerUUID =
      new ConfKeyInfo<>(
          "yb.security.ldap.ldap_customeruuid",
          ScopeType.GLOBAL,
          "LDAP Customer UUID",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static ConfKeyInfo<String> ldapServiceAccountUsername =
      new ConfKeyInfo<>(
          "yb.security.ldap.ldap_service_account_username",
          ScopeType.GLOBAL,
          "LDAP Service Account Username",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static ConfKeyInfo<String> ldapServiceAccountPassword =
      new ConfKeyInfo<>(
          "yb.security.ldap.ldap_service_account_password",
          ScopeType.GLOBAL,
          "LDAP Service Account Password",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static ConfKeyInfo<Boolean> enableLdap =
      new ConfKeyInfo<>(
          "yb.security.ldap.enable_ldaps",
          ScopeType.GLOBAL,
          "Enable LDAPS",
          "TODO - Leave this for feature owners to fill in",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static ConfKeyInfo<Boolean> enableLdapStartTls =
      new ConfKeyInfo<>(
          "yb.security.ldap.enable_ldap_start_tls",
          ScopeType.GLOBAL,
          "Enable LDAPS start TLS",
          "Hidden because this key has dedicated UI",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static ConfKeyInfo<Boolean> ldapUseSearchAndBind =
      new ConfKeyInfo<>(
          "yb.security.ldap.use_search_and_bind",
          ScopeType.GLOBAL,
          "Use Search and Bind",
          "Hidden because this key has dedicated UI",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static ConfKeyInfo<String> ldapSearchAttribute =
      new ConfKeyInfo<>(
          "yb.security.ldap.ldap_search_attribute",
          ScopeType.GLOBAL,
          "LDAP Search Attribute",
          "Hidden because this key has dedicated UI",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.UIDriven));
  public static ConfKeyInfo<Boolean> enableDetailedLogs =
      new ConfKeyInfo<>(
          "yb.security.enable_detailed_logs",
          ScopeType.GLOBAL,
          "Enable Detailed Logs",
          "Enable detailed security logs",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static ConfKeyInfo<Boolean> fsStatelessSuppressError =
      new ConfKeyInfo<>(
          "yb.fs_stateless.suppress_error",
          ScopeType.GLOBAL,
          "Supress Error",
          "If set, suppresses exceptions to be thrown as part of FS <-> DB sync on YBA startup",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static ConfKeyInfo<Long> fsStatelessMaxFileSizeBytes =
      new ConfKeyInfo<>(
          "yb.fs_stateless.max_file_size_bytes",
          ScopeType.GLOBAL,
          "Max File Size",
          "Maximum size of file that can be persisted in DB",
          ConfDataType.LongType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static ConfKeyInfo<Integer> fsStatelessMaxFilesCountPersist =
      new ConfKeyInfo<>(
          "yb.fs_stateless.max_files_count_persist",
          ScopeType.GLOBAL,
          "Max Files Persist",
          "Maximum number of files that can be persisted in DB",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static ConfKeyInfo<Boolean> disableSyncDbToFsStartup =
      new ConfKeyInfo<>(
          "yb.fs_stateless.disable_sync_db_to_fs_startup",
          ScopeType.GLOBAL,
          "Sync DB State to FS",
          "If disables does not syncs the files in DB to FS on every YBA startup",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static ConfKeyInfo<Duration> taskGcCheckInterval =
      new ConfKeyInfo<>(
          "yb.taskGC.gc_check_interval",
          ScopeType.GLOBAL,
          "Task Garbage Collector Check Interval",
          "How frequently do we check for completed tasks in database",
          ConfDataType.DurationType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  // TODO yury
  public static ConfKeyInfo<Boolean> editProviderNewEnabled =
      new ConfKeyInfo<>(
          "yb.edit_provider.new.enabled",
          ScopeType.GLOBAL,
          "Enable New Edit Provider",
          "TODO",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static final ConfKeyInfo<List> tagList =
      new ConfKeyInfo<>(
          "yb.runtime_conf_ui.tag_filter",
          ScopeType.GLOBAL,
          "UI Tag Filters",
          "List of tags to filter which keys are displayed",
          ConfDataType.TagListType,
          ImmutableList.of(ConfKeyTags.INTERNAL));
  public static final ConfKeyInfo<Boolean> dataValidationEnabled =
      new ConfKeyInfo<>(
          "runtime_config.data_validation.enabled",
          ScopeType.GLOBAL,
          "Enable Data Validation",
          "Enable data validation while setting runtime keys",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.INTERNAL));
  public static final ConfKeyInfo<Boolean> scopeStrictnessEnabled =
      new ConfKeyInfo<>(
          "runtime_config.scope_strictness.enabled",
          ScopeType.GLOBAL,
          "Enable Scope Strictness",
          "Enable scope strictness while setting runtime keys",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.INTERNAL));
  public static final ConfKeyInfo<String> ansibleOffloadSupportedVersion =
      new ConfKeyInfo<>(
          "yb.node_agent.ansible_offloading.min_supported_version",
          ScopeType.GLOBAL,
          "Ansible Offloading Supported Version",
          "Minimum supported version for ansible offloading",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.INTERNAL));
  public static final ConfKeyInfo<Duration> nodeAgentPollerInterval =
      new ConfKeyInfo<>(
          "yb.node_agent.poller_interval",
          ScopeType.GLOBAL,
          "Node Agent Poller Interval",
          "Node agent poller interval",
          ConfDataType.DurationType,
          ImmutableList.of(ConfKeyTags.INTERNAL));
  public static final ConfKeyInfo<Duration> deadNodeAgentRetention =
      new ConfKeyInfo<>(
          "yb.node_agent.retention_duration",
          ScopeType.GLOBAL,
          "Dead Node Agent Retention Duration",
          "Retention duration for a dead node agent before deletion",
          ConfDataType.DurationType,
          ImmutableList.of(ConfKeyTags.INTERNAL));
  public static final ConfKeyInfo<Integer> maxParallelNodeAgentUpgrades =
      new ConfKeyInfo<>(
          "yb.node_agent.max_parallel_upgrades",
          ScopeType.GLOBAL,
          "Max Parallel Node Agent Upgrades",
          "Maximum number of parallel node agent upgrades",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static final ConfKeyInfo<Boolean> backwardCompatibleDate =
      new ConfKeyInfo<>(
          "yb.api.backward_compatible_date",
          ScopeType.GLOBAL,
          "API support for backward compatible date fields",
          "Enable when a client to the YBAnywhere API wants to continue using the older date "
              + " fields in non-ISO format. Default behaviour is to not populate such deprecated "
              + "API fields and only return newer date fields.",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> attachDetachEnabled =
      new ConfKeyInfo<>(
          "yb.attach_detach.enabled",
          ScopeType.GLOBAL,
          "Allow universes to be detached/attached",
          "Allow universes to be detached from a source platform and attached to dest platform",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> transactionalXClusterEnabled =
      new ConfKeyInfo<>(
          "yb.xcluster.transactional.enabled",
          ScopeType.GLOBAL,
          "Whether YBA supports transactional xCluster configs",
          "It indicates whether YBA should support transactional xCluster configs",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.INTERNAL));
  public static final ConfKeyInfo<Boolean> allowDbVersionMoreThanYbaVersion =
      new ConfKeyInfo<>(
          "yb.allow_db_version_more_than_yba_version",
          ScopeType.GLOBAL,
          "Whether installation of YugabyteDB version higher than YBA version is allowed",
          "It indicates whether the installation of YugabyteDB with a version higher than "
              + "YBA version is allowed on universe nodes",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<String> pgDumpPath =
      new ConfKeyInfo<>(
          "db.default.pg_dump_path",
          ScopeType.GLOBAL,
          "Path to pg_dump on the YBA node",
          "Set during yba-installer for both custom postgres and version specific postgres "
              + "installation",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<String> pgRestorePath =
      new ConfKeyInfo<>(
          "db.default.pg_restore_path",
          ScopeType.GLOBAL,
          "Path to pg_restore on the YBA node",
          "Set during yba-installer for both custom postgres and version specific postgres "
              + "installation",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> allowUsedProviderEdit =
      new ConfKeyInfo<>(
          "yb.provider.allow_used_provider_edit",
          ScopeType.GLOBAL,
          "Allow editing of used providers",
          "Provider associated with universes can be edited",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static final ConfKeyInfo<String> ybdbReleasePathRegex =
      new ConfKeyInfo<>(
          "yb.regex.release_pattern.ybdb",
          ScopeType.GLOBAL,
          "Regex for match Yugabyte DB release .tar.gz files",
          "Regex pattern used to find Yugabyte DB release .tar.gz files",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<String> ybdbHelmReleasePathRegex =
      new ConfKeyInfo<>(
          "yb.regex.release_pattern.helm",
          ScopeType.GLOBAL,
          "Regex for match Yugabyte DB release helm .tar.gz files",
          "Regex pattern used to find Yugabyte DB helm .tar.gz files",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> fixDatabaseFullPaths =
      new ConfKeyInfo<>(
          "yb.fixPaths",
          ScopeType.GLOBAL,
          "Whether YBA should fix paths on startup",
          "When enabled YBA will try to replace all filepaths in the database with updated values "
              + "for the configurable part of the path (like storage or releases path)",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.INTERNAL));
}
