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
import com.yugabyte.yw.commissioner.tasks.UniverseTaskBase.VersionCheckMode;
import com.yugabyte.yw.common.NodeManager.SkipCertValidationType;
import com.yugabyte.yw.common.config.ConfKeyInfo.ConfKeyTags;
import com.yugabyte.yw.forms.RuntimeConfigFormData.ScopedConfig.ScopeType;
import java.time.Duration;
import java.util.List;

public class UniverseConfKeys extends RuntimeConfigKeysModule {

  public static final ConfKeyInfo<Duration> alertMaxClockSkew =
      new ConfKeyInfo<>(
          "yb.alert.max_clock_skew_ms",
          ScopeType.UNIVERSE,
          "Clock Skew",
          "Default threshold for Clock Skew alert",
          ConfDataType.DurationType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> cloudEnabled =
      new ConfKeyInfo<>(
          "yb.cloud.enabled",
          ScopeType.UNIVERSE,
          "Cloud Enabled",
          "Enables YBM specific features",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.INTERNAL));
  public static final ConfKeyInfo<Boolean> healthLogOutput =
      new ConfKeyInfo<>(
          "yb.health.logOutput",
          ScopeType.UNIVERSE,
          "Health Log Output",
          "It determines whether to log the output "
              + "of the node health check script to the console",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> nodeCheckTimeoutSec =
      new ConfKeyInfo<>(
          "yb.health.nodeCheckTimeoutSec",
          ScopeType.UNIVERSE,
          "Node Checkout Time",
          "The timeout (in seconds) for node check operation as part of universe health check",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> ybUpgradeBlacklistLeaders =
      new ConfKeyInfo<>(
          "yb.upgrade.blacklist_leaders",
          ScopeType.UNIVERSE,
          "YB Upgrade Blacklist Leaders",
          "Determines (boolean) whether we enable/disable "
              + "leader blacklisting when performing universe/node tasks",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> ybUpgradeBlacklistLeaderWaitTimeMs =
      new ConfKeyInfo<>(
          "yb.upgrade.blacklist_leader_wait_time_ms",
          ScopeType.UNIVERSE,
          "YB Upgrade Blacklist Leader Wait Time in Ms",
          "The timeout (in milliseconds) that we wait of leader blacklisting on a node to complete",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> ybUpgradeMaxFollowerLagThresholdMs =
      new ConfKeyInfo<>(
          "yb.upgrade.max_follower_lag_threshold_ms",
          ScopeType.UNIVERSE,
          "YB Upgrade Max Follower Lag Threshold ",
          "The maximum time (in milliseconds) that we allow a tserver to be behind its peers",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  // TODO(naorem): Add correct metadata
  public static final ConfKeyInfo<Boolean> ybUpgradeVmImage =
      new ConfKeyInfo<>(
          "yb.upgrade.vmImage",
          ScopeType.UNIVERSE,
          "Upgrade VM Image",
          "TODO - Leave this for feature owners to fill in",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.BETA));
  // TODO(): Add correct metadata
  public static final ConfKeyInfo<Boolean> allowDowngrades =
      new ConfKeyInfo<>(
          "yb.upgrade.allow_downgrades",
          ScopeType.UNIVERSE,
          "YB Upgrade Allow Downgrades",
          "TODO - Leave this for feature owners to fill in",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static final ConfKeyInfo<Boolean> singleConnectionYsqlUpgrade =
      new ConfKeyInfo<>(
          "yb.upgrade.single_connection_ysql_upgrade",
          ScopeType.UNIVERSE,
          "YB Upgrade Use Single Connection Param",
          "The flag, which controls, "
              + "if YSQL catalog upgrade will be performed in single or multi connection mode."
              + "Single connection mode makes it work even on tiny DB nodes.",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> ybEditWaitForLeadersOnPreferred =
      new ConfKeyInfo<>(
          "yb.edit.wait_for_leaders_on_preferred",
          ScopeType.UNIVERSE,
          "YB Edit Wait For Leaders On Preferred Only",
          "Controls whether we perform the createWaitForLeadersOnPreferredOnly subtask"
              + "in editUniverse",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.INTERNAL));
  public static final ConfKeyInfo<Integer> ybNumReleasesToKeepDefault =
      new ConfKeyInfo<>(
          "yb.releases.num_releases_to_keep_default",
          ScopeType.UNIVERSE,
          "Default Releases Count",
          "Number of Releases to Keep",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> ybNumReleasesToKeepCloud =
      new ConfKeyInfo<>(
          "yb.releases.num_releases_to_keep_cloud",
          ScopeType.UNIVERSE,
          "Cloud Releases Count",
          "Number Of Cloud Releases To Keep",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> dbMemPostgresMaxMemMb =
      new ConfKeyInfo<>(
          "yb.dbmem.postgres.max_mem_mb",
          ScopeType.UNIVERSE,
          "DB Postgres Max Mem",
          "Amount of memory to limit the postgres process to via the ysql cgroup",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static final ConfKeyInfo<Integer> dbMemPostgresReadReplicaMaxMemMb =
      new ConfKeyInfo<>(
          "yb.dbmem.postgres.rr_max_mem_mb",
          ScopeType.UNIVERSE,
          "DB Postgres Max Mem for read replicas",
          "The amount of memory in MB to limit the postgres process in read replicas to via the "
              + "ysql cgroup. "
              + "If the value is -1, it will default to the 'yb.dbmem.postgres.max_mem_mb' value. "
              + "0 will not set any cgroup limits. "
              + ">0 set max memory of postgres to this value for read replicas",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static final ConfKeyInfo<Long> dbMemAvailableLimit =
      new ConfKeyInfo<>(
          "yb.dbmem.checks.mem_available_limit_kb",
          ScopeType.UNIVERSE,
          "DB Available Mem Limit",
          "Minimum available memory required on DB nodes for software upgrade.",
          ConfDataType.LongType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> pgBasedBackup =
      new ConfKeyInfo<>(
          "yb.backup.pg_based",
          ScopeType.UNIVERSE,
          "PG Based Backup",
          "Enable PG-based backup",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> dbReadWriteTest =
      new ConfKeyInfo<>(
          "yb.metrics.db_read_write_test",
          ScopeType.UNIVERSE,
          "DB Read Write Test",
          "The flag defines, if we perform DB write-read check on DB nodes or not.",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<String> metricsCollectionLevel =
      new ConfKeyInfo<>(
          "yb.metrics.collection_level",
          ScopeType.UNIVERSE,
          "Metrics Collection Level",
          "DB node metrics collection level."
              + "ALL - collect all metrics, "
              + "NORMAL - default value, which only limits some per-table metrics, "
              + "MINIMAL - limits both node level and further limits table level "
              + "metrics we collect and "
              + "OFF to completely disable metric collection.",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<VersionCheckMode> universeVersionCheckMode =
      new ConfKeyInfo<>(
          "yb.universe_version_check_mode",
          ScopeType.UNIVERSE,
          "Universe Version Check Mode",
          "Possible values: NEVER, HA_ONLY, ALWAYS",
          ConfDataType.VersionCheckModeEnum,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> taskOverrideForceUniverseLock =
      new ConfKeyInfo<>(
          "yb.task.override_force_universe_lock",
          ScopeType.UNIVERSE,
          "Override Force Universe Lock",
          "Whether overriding universe lock is allowed when force option is selected."
              + "If it is disabled, force option will wait for the lock to be released.",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  // TODO(): Add correct metadata
  public static final ConfKeyInfo<Boolean> enableSshKeyExpiration =
      new ConfKeyInfo<>(
          "yb.security.ssh_keys.enable_ssh_key_expiration",
          ScopeType.UNIVERSE,
          "Enable SSH Key Expiration",
          "TODO",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  // TODO(): Add correct metadata
  public static final ConfKeyInfo<Integer> sshKeyExpirationThresholdDays =
      new ConfKeyInfo<>(
          "yb.security.ssh_keys.ssh_key_expiration_threshold_days",
          ScopeType.UNIVERSE,
          "SSh Key Expiration Threshold",
          "TODO",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<String> nfsDirs =
      new ConfKeyInfo<>(
          "yb.ybc_flags.nfs_dirs",
          ScopeType.UNIVERSE,
          "NFS Directry Path",
          "Authorised NFS directories for backups",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> ybcEnableVervbose =
      new ConfKeyInfo<>(
          "yb.ybc_flags.enable_verbose",
          ScopeType.UNIVERSE,
          "Enable Verbose Logging",
          "Enable verbose ybc logging",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> maxThreads =
      new ConfKeyInfo<>(
          "yb.perf_advisor.max_threads",
          ScopeType.UNIVERSE,
          "Max Thread Count",
          "Max number of threads to support parallel querying of nodes",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> ybcAllowScheduledUpgrade =
      new ConfKeyInfo<>(
          "ybc.upgrade.allow_scheduled_upgrade",
          ScopeType.UNIVERSE,
          "Allow Scheduled YBC Upgrades",
          "Enable Scheduled upgrade of ybc on the universe",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> gflagsAllowUserOverride =
      new ConfKeyInfo<>(
          "yb.gflags.allow_user_override",
          ScopeType.UNIVERSE,
          "Allow User Gflags Override",
          "Allow users to override default Gflags values",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> enableTriggerAPI =
      new ConfKeyInfo<>(
          "yb.health.trigger_api.enabled",
          ScopeType.UNIVERSE,
          "Enable Trigger API",
          "Allow trigger_health_check API to be called",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> backupLogVerbose =
      new ConfKeyInfo<>(
          "yb.backup.log.verbose",
          ScopeType.UNIVERSE,
          "Verbose Backup Log",
          "Enable verbose backup logging",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> waitForLbForAddedNodes =
      new ConfKeyInfo<>(
          "yb.wait_for_lb_for_added_nodes",
          ScopeType.UNIVERSE,
          "Wait for LB for Added Nodes",
          "Wait for Load Balancer for added nodes",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Duration> waitForMasterLeaderTimeout =
      new ConfKeyInfo<>(
          "yb.wait_for_master_leader_timeout",
          ScopeType.UNIVERSE,
          "Wait For master Leader timeout",
          "Time in seconds to wait for master leader before timeout for List tables API",
          ConfDataType.DurationType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  // TODO(Shashank): Add correct metadata
  public static final ConfKeyInfo<Integer> slowQueryLimit =
      new ConfKeyInfo<>(
          "yb.query_stats.slow_queries.limit",
          ScopeType.UNIVERSE,
          "Slow Queries Limit",
          "TODO - Leave this for feature owners to fill in",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.BETA));
  // TODO(Shashank): Add correct metadata
  public static final ConfKeyInfo<String> slowQueryOrderByKey =
      new ConfKeyInfo<>(
          "yb.query_stats.slow_queries.order_by",
          ScopeType.UNIVERSE,
          "Slow Queries Order By Key",
          "TODO - Leave this for feature owners to fill in",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.BETA));
  // TODO(Shashank): Add correct metadata
  public static final ConfKeyInfo<Boolean> setEnableNestloopOff =
      new ConfKeyInfo<>(
          "yb.query_stats.slow_queries.set_enable_nestloop_off",
          ScopeType.UNIVERSE,
          "Turn off batch nest loop for running slow sql queries",
          "This config turns off and on batch nestloop during running the join statement "
              + "for slow queries. If true, it will be turned off and we expect better "
              + "performance.",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.BETA));
  // TODO(Shashank)
  public static final ConfKeyInfo<List> excludedQueries =
      new ConfKeyInfo<>(
          "yb.query_stats.excluded_queries",
          ScopeType.UNIVERSE,
          "Excluded Queries",
          "TODO - Leave this for feature owners to fill in",
          ConfDataType.StringListType,
          ImmutableList.of(ConfKeyTags.BETA));
  public static final ConfKeyInfo<String> ansibleStrategy =
      new ConfKeyInfo<>(
          "yb.ansible.strategy",
          ScopeType.UNIVERSE,
          "Ansible Strategy",
          "strategy can be linear, mitogen_linear or debug",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> ansibleConnectionTimeoutSecs =
      new ConfKeyInfo<>(
          "yb.ansible.conn_timeout_secs",
          ScopeType.UNIVERSE,
          "Ansible Connection Timeout Duration",
          "This is the default timeout for connection plugins to use.",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> ansibleVerbosity =
      new ConfKeyInfo<>(
          "yb.ansible.verbosity",
          ScopeType.UNIVERSE,
          "Ansible Verbosity Level",
          "verbosity of ansible logs, 0 to 4 (more verbose)",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> ansibleDebug =
      new ConfKeyInfo<>(
          "yb.ansible.debug",
          ScopeType.UNIVERSE,
          "Ansible Debug Output",
          "Debug output (can include secrets in output)",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> ansibleDiffAlways =
      new ConfKeyInfo<>(
          "yb.ansible.diff_always",
          ScopeType.UNIVERSE,
          "Ansible Diff Always",
          "Configuration toggle to tell modules to show differences "
              + "when in 'changed' status, equivalent to --diff.",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<String> ansibleLocalTemp =
      new ConfKeyInfo<>(
          "yb.ansible.local_temp",
          ScopeType.UNIVERSE,
          "Ansible Local Temp Directory",
          "Temporary directory for Ansible to use on the controller.",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> uiEnableTopK =
      new ConfKeyInfo<>(
          "yb.metrics.ui.topk.enable",
          ScopeType.UNIVERSE,
          "Universe Metrics view",
          "Option to switch between old and new universe metrics UI",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> perfAdvisorEnabled =
      new ConfKeyInfo<>(
          "yb.perf_advisor.enabled",
          ScopeType.UNIVERSE,
          "Enable Performance Advisor",
          "Defines if performance advisor is enabled for the universe or not",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> perfAdvisorUniverseFrequencyMins =
      new ConfKeyInfo<>(
          "yb.perf_advisor.universe_frequency_mins",
          ScopeType.UNIVERSE,
          "Performance Advisor Run Frequency",
          "Defines performance advisor run frequency for universe",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));

  public static final ConfKeyInfo<Double> perfAdvisorConnectionSkewThreshold =
      new ConfKeyInfo<>(
          "yb.perf_advisor.connection_skew_threshold_pct",
          ScopeType.UNIVERSE,
          "Performance Advisor connection skew threshold",
          "Defines max difference between avg connections count usage and"
              + " node connection count before connection skew recommendation is raised",
          ConfDataType.DoubleType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> perfAdvisorConnectionSkewMinConnections =
      new ConfKeyInfo<>(
          "yb.perf_advisor.connection_skew_min_connections",
          ScopeType.UNIVERSE,
          "Performance Advisor connection skew min connections",
          "Defines minimal number of connections for connection "
              + "skew recommendation to be raised",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> perfAdvisorConnectionSkewIntervalMins =
      new ConfKeyInfo<>(
          "yb.perf_advisor.connection_skew_interval_mins",
          ScopeType.UNIVERSE,
          "Performance Advisor connection skew interval mins",
          "Defines time interval for connection skew recommendation check, in minutes",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));

  public static final ConfKeyInfo<Double> perfAdvisorCpuSkewThreshold =
      new ConfKeyInfo<>(
          "yb.perf_advisor.cpu_skew_threshold_pct",
          ScopeType.UNIVERSE,
          "Performance Advisor cpu skew threshold",
          "Defines max difference between avg cpu usage and"
              + " node cpu usage before cpu skew recommendation is raised",
          ConfDataType.DoubleType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Double> perfAdvisorCpuSkewMinUsage =
      new ConfKeyInfo<>(
          "yb.perf_advisor.cpu_skew_min_usage_pct",
          ScopeType.UNIVERSE,
          "Performance Advisor cpu skew min usage",
          "Defines minimal cpu usage for cpu skew recommendation to be raised",
          ConfDataType.DoubleType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> perfAdvisorCpuSkewIntervalMins =
      new ConfKeyInfo<>(
          "yb.perf_advisor.cpu_skew_interval_mins",
          ScopeType.UNIVERSE,
          "Performance Advisor cpu skew interval mins",
          "Defines time interval for cpu skew recommendation check, in minutes",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));

  public static final ConfKeyInfo<Double> perfAdvisorCpuUsageThreshold =
      new ConfKeyInfo<>(
          "yb.perf_advisor.cpu_usage_threshold",
          ScopeType.UNIVERSE,
          "Performance Advisor CPU usage threshold",
          "Defines max allowed average CPU usage per 10 minutes before "
              + "CPU usage recommendation is raised",
          ConfDataType.DoubleType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> perfAdvisorCpuUsageIntervalMins =
      new ConfKeyInfo<>(
          "yb.perf_advisor.cpu_usage_interval_mins",
          ScopeType.UNIVERSE,
          "Performance Advisor cpu usage interval mins",
          "Defines time interval for cpu usage recommendation check, in minutes",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));

  public static final ConfKeyInfo<Double> perfAdvisorQuerySkewThreshold =
      new ConfKeyInfo<>(
          "yb.perf_advisor.query_skew_threshold_pct",
          ScopeType.UNIVERSE,
          "Performance Advisor query skew threshold",
          "Defines max difference between avg queries count and"
              + " node queries count before query skew recommendation is raised",
          ConfDataType.DoubleType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> perfAdvisorQuerySkewMinQueries =
      new ConfKeyInfo<>(
          "yb.perf_advisor.query_skew_min_queries",
          ScopeType.UNIVERSE,
          "Performance Advisor query skew min queries",
          "Defines minimal queries count for query skew recommendation to be raised",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> perfAdvisorQuerySkewIntervalMins =
      new ConfKeyInfo<>(
          "yb.perf_advisor.query_skew_interval_mins",
          ScopeType.UNIVERSE,
          "Performance Advisor query skew interval mins",
          "Defines time interval for query skew recommendation check, in minutes",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));

  public static final ConfKeyInfo<Integer> perfAdvisorRejectedConnThreshold =
      new ConfKeyInfo<>(
          "yb.perf_advisor.rejected_conn_threshold",
          ScopeType.UNIVERSE,
          "Performance Advisor rejected connections threshold",
          "Defines number of rejected connections during configured interval"
              + " for rejected connections recommendation to be raised ",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> perfAdvisorRejectedConnIntervalMins =
      new ConfKeyInfo<>(
          "yb.perf_advisor.rejected_conn_interval_mins",
          ScopeType.UNIVERSE,
          "Performance Advisor rejected connections interval mins",
          "Defines time interval for rejected connections recommendation check, in minutes",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Double> perfAdvisorHotShardWriteSkewThresholdPct =
      new ConfKeyInfo<>(
          "yb.perf_advisor.hot_shard_write_skew_threshold_pct",
          ScopeType.UNIVERSE,
          "Performance Advisor hot shard write skew threshold",
          "Defines max difference between average node writes and hot shard node writes before "
              + "hot shard recommendation is raised",
          ConfDataType.DoubleType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Double> perfAdvisorHotShardReadSkewThresholdPct =
      new ConfKeyInfo<>(
          "yb.perf_advisor.hot_shard_read_skew_threshold_pct",
          ScopeType.UNIVERSE,
          "Performance Advisor hot shard read skew threshold",
          "Defines max difference between average node reads and hot shard node reads before "
              + "hot shard recommendation is raised",
          ConfDataType.DoubleType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> perfAdvisorHotShardIntervalMins =
      new ConfKeyInfo<>(
          "yb.perf_advisor.hot_shard_interval_mins",
          ScopeType.UNIVERSE,
          "Performance Advisor hot shard interval mins",
          "Defines time interval for hot hard recommendation check, in minutes",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> perfAdvisorHotShardMinimalWrites =
      new ConfKeyInfo<>(
          "yb.perf_advisor.hot_shard_min_node_writes",
          ScopeType.UNIVERSE,
          "Performance Advisor hot shard minimal writes",
          "Defines min writes for hot shard recommendation to be raised",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Integer> perfAdvisorHotShardMinimalReads =
      new ConfKeyInfo<>(
          "yb.perf_advisor.hot_shard_min_node_reads",
          ScopeType.UNIVERSE,
          "Performance Advisor hot shard minimal reads",
          "Defines min reads for hot shard recommendation to be raised",
          ConfDataType.IntegerType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<SkipCertValidationType> tlsSkipCertValidation =
      new ConfKeyInfo<>(
          "yb.tls.skip_cert_validation",
          ScopeType.UNIVERSE,
          "Skip TLS Cert Validation",
          "Used to skip certificates validation for the configure phase."
              + "Possible values - ALL, HOSTNAME, NONE",
          ConfDataType.SkipCertValdationEnum,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> deleteOrphanSnapshotOnStartup =
      new ConfKeyInfo<>(
          "yb.snapshot_cleanup.delete_orphan_on_startup",
          ScopeType.UNIVERSE,
          "Clean Orphan snapshots",
          "Clean orphan(non-scheduled) snapshots on Yugaware startup/restart",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> nodeUIHttpsEnabled =
      new ConfKeyInfo<>(
          "yb.node_ui.https.enabled",
          ScopeType.UNIVERSE,
          "Enable https on Master/TServer UI",
          "Allow https on Master/TServer UI for a universe",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Long> helmTimeoutSecs =
      new ConfKeyInfo<>(
          "yb.helm.timeout_secs",
          ScopeType.UNIVERSE,
          "Helm Timeout in Seconds",
          "Timeout used for internal universe-level helm operations like install/upgrade in secs",
          ConfDataType.LongType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> enablePerfAdvisor =
      new ConfKeyInfo<>(
          "yb.ui.feature_flags.perf_advisor",
          ScopeType.UNIVERSE,
          "Enable Perf Advisor to view recommendations",
          "Builds recommendations to help tune our applications accordingly",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> promoteAutoFlag =
      new ConfKeyInfo<>(
          "yb.upgrade.promote_auto_flag",
          ScopeType.UNIVERSE,
          "Promote AutoFlags",
          "Promotes Auto flags while upgrading YB-DB",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> allowUpgradeOnTransitUniverse =
      new ConfKeyInfo<>(
          "yb.upgrade.allow_upgrade_on_transit_universe",
          ScopeType.UNIVERSE,
          "Allow upgrade on transit universe",
          "Allow universe upgrade when nodes are in transit mode",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Boolean> promoteAutoFlagsForceFully =
      new ConfKeyInfo<>(
          "yb.upgrade.promote_flags_forcefully",
          ScopeType.UNIVERSE,
          "Promote AutoFlags Forcefully",
          "Promote AutoFlags Forcefully during software upgrade",
          ConfDataType.BooleanType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<Long> minIncrementalScheduleFrequencyInSecs =
      new ConfKeyInfo<>(
          "yb.backup.minIncrementalScheduleFrequencyInSecs",
          ScopeType.UNIVERSE,
          "Minimum Incremental backup schedule frequency",
          "Minimum Incremental backup schedule frequency in seconds",
          ConfDataType.LongType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<String> universeLogsRegexPattern =
      new ConfKeyInfo<>(
          "yb.support_bundle.universe_logs_regex_pattern",
          ScopeType.UNIVERSE,
          "Universe logs regex pattern",
          "Universe logs regex pattern in support bundle",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
  public static final ConfKeyInfo<String> postgresLogsRegexPattern =
      new ConfKeyInfo<>(
          "yb.support_bundle.postgres_logs_regex_pattern",
          ScopeType.UNIVERSE,
          "Postgres logs regex pattern",
          "Postgres logs regex pattern in support bundle",
          ConfDataType.StringType,
          ImmutableList.of(ConfKeyTags.PUBLIC));
}
