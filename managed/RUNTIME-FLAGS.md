# <u>List of supported Runtime Configuration Flags</u>
### These are all the public runtime flags in YBA.
| Name | Key | Scope | Help Text | Data Type |
| :----: | :----: | :----: | :----: | :----: |
| "Task Garbage Collection Retention Duration" | "yb.taskGC.task_retention_duration" | "CUSTOMER" | "We garbage collect stale tasks after this duration" | "Duration" |
| "Enforce Auth" | "yb.universe.auth.is_enforced" | "CUSTOMER" | "Enforces users to enter password for YSQL/YCQL during Universe creation" | "Boolean" |
| "Enable dedicated nodes" | "yb.ui.enable_dedicated_nodes" | "CUSTOMER" | "Gives the option to place master and tserver nodes separately during create/edit universe" | "Boolean" |
| "Max Number of Customer Tasks to fetch" | "yb.customer_task_db_query_limit" | "CUSTOMER" | "Knob that can be used when there are too many customer tasks overwhelming the server" | "Integer" |
| "Show costs in UI" | "yb.ui.show_cost" | "CUSTOMER" | "Option to enable/disable costs in UI" | "Boolean" |
| "Helm chart http download timeout" | "yb.releases.download_helm_chart_http_timeout" | "CUSTOMER" | "The timeout for downloading the Helm chart while importing a release using HTTP" | "Duration" |
| "Use Redesigned Provider UI" | "yb.ui.feature_flags.provider_redesign" | "CUSTOMER" | "The redesigned provider UI adds a provider list view, a provider details view and improves the provider creation form for AWS, AZU, GCP, and K8s" | "Boolean" |
| "Use K8 custom resources" | "yb.ui.feature_flags.k8s_custom_resources" | "CUSTOMER" | "Allows user to select custom K8 memory(GB) and cpu cores" | "Boolean" |
| "Enforce User Tags" | "yb.universe.user_tags.is_enforced" | "CUSTOMER" | "Prevents universe creation when the enforced tags are not provided." | "Boolean" |
| "Enforced User Tags List" | "yb.universe.user_tags.enforced_tags" | "CUSTOMER" | "A list of enforced user tag and accepted value pairs during universe creation. Pass '*' to accept all values for a tag. Ex: [\"yb_task:dev\",\"yb_task:test\",\"yb_owner:*\",\"yb_dept:eng\",\"yb_dept:qa\", \"yb_dept:product\", \"yb_dept:sales\"]" | "Key Value SetMultimap" |
| "Allow Unsupported Instances" | "yb.internal.allow_unsupported_instances" | "PROVIDER" | "Enabling removes supported instance type filtering on AWS providers." | "Boolean" |
| "Default AWS Instance Type" | "yb.aws.default_instance_type" | "PROVIDER" | "Default AWS Instance Type" | "String" |
| "Default GCP Instance Type" | "yb.gcp.default_instance_type" | "PROVIDER" | "Default GCP Instance Type" | "String" |
| "Default Azure Instance Type" | "yb.azure.default_instance_type" | "PROVIDER" | "Default Azure Instance Type" | "String" |
| "Default Kubernetes Instance Type" | "yb.kubernetes.default_instance_type" | "PROVIDER" | "Default Kubernetes Instance Type" | "String" |
| "Default AWS Storage Type" | "yb.aws.storage.default_storage_type" | "PROVIDER" | "Default AWS Storage Type" | "String" |
| "Default GCP Storage Type" | "yb.gcp.storage.default_storage_type" | "PROVIDER" | "Default GCP Storage Type" | "String" |
| "Default Azure Storage Type" | "yb.azure.storage.default_storage_type" | "PROVIDER" | "Default Azure Storage Type" | "String" |
| "Universe Boot Script" | "yb.universe_boot_script" | "PROVIDER" | "Custom script to run on VM boot during universe provisioning" | "String" |
| "Default AWS Volume Count" | "yb.aws.default_volume_count" | "PROVIDER" | "Default AWS Volume Count" | "Integer" |
| "Default AWS Volume Size" | "yb.aws.default_volume_size_gb" | "PROVIDER" | "Default AWS Volume Size" | "Integer" |
| "Default GCP Volume Size" | "yb.gcp.default_volume_size_gb" | "PROVIDER" | "Default GCP Volume Size" | "Integer" |
| "Default Azure Volume Size" | "yb.azure.default_volume_size_gb" | "PROVIDER" | "Default Azure Volume Size" | "Integer" |
| "Default Kubernetes Volume Count" | "yb.kubernetes.default_volume_count" | "PROVIDER" | "Default Kubernetes Volume Count" | "Integer" |
| "Default Kubernetes Volume Size" | "yb.kubernetes.default_volume_size_gb" | "PROVIDER" | "Default Kubernetes Volume Size" | "Integer" |
| "Default Kubernetes CPU cores" | "yb.kubernetes.default_cpu_cores" | "PROVIDER" | "Default Kubernetes CPU cores" | "Integer" |
| "Minimum Kubernetes CPU cores" | "yb.kubernetes.min_cpu_cores" | "PROVIDER" | "Minimum Kubernetes CPU cores" | "Integer" |
| "Maximum Kubernetes CPU cores" | "yb.kubernetes.max_cpu_cores" | "PROVIDER" | "Maximum Kubernetes CPU cores" | "Integer" |
| "Default Kubernetes Memory Size" | "yb.kubernetes.default_memory_size_gb" | "PROVIDER" | "Default Kubernetes Memory Size" | "Integer" |
| "Minimum Kubernetes Memory Size" | "yb.kubernetes.min_memory_size_gb" | "PROVIDER" | "Minimum Kubernetes Memory Size" | "Integer" |
| "Maximum Kubernetes Memory Size" | "yb.kubernetes.max_memory_size_gb" | "PROVIDER" | "Maximum Kubernetes Memory Size" | "Integer" |
| "Enable Node Agent Client" | "yb.node_agent.client.enabled" | "PROVIDER" | "Enable node agent client for communication to DB nodes." | "Boolean" |
| "Install Node Agent Server" | "yb.node_agent.server.install" | "PROVIDER" | "Install node agent server on DB nodes." | "Boolean" |
| "Enable Ansible Offloading" | "yb.node_agent.ansible_offloading.enabled" | "PROVIDER" | "Offload ansible tasks to the DB nodes." | "Boolean" |
| "Max Number of Parallel Node Checks" | "yb.health.max_num_parallel_node_checks" | "GLOBAL" | "Number of parallel node checks, spawned as part of universes health check process" | "Integer" |
| "Log Script Output For YBA HA Feature" | "yb.ha.logScriptOutput" | "GLOBAL" | "To log backup restore script output for debugging issues" | "Boolean" |
| "Use Kubectl" | "yb.use_kubectl" | "GLOBAL" | "Use java library instead of spinning up kubectl process." | "Boolean" |
| "Enable SSH2" | "yb.security.ssh2_enabled" | "GLOBAL" | "Flag for enabling ssh2 on YBA" | "Boolean" |
| "Enable Custom Hooks" | "yb.security.custom_hooks.enable_custom_hooks" | "GLOBAL" | "Flag for enabling custom hooks on YBA" | "Boolean" |
| "Enable SUDO" | "yb.security.custom_hooks.enable_sudo" | "GLOBAL" | "Flag for enabling sudo access while running custom hooks" | "Boolean" |
| "Enable API Triggered Hooks" | "yb.security.custom_hooks.enable_api_triggered_hooks" | "GLOBAL" | "Flag for enabling API Triggered Hooks on YBA" | "Boolean" |
| "Disable XX Hash Checksum" | "yb.backup.disable_xxhash_checksum" | "GLOBAL" | "Flag for disabling xxhsum based checksums for computing the backup" | "Boolean" |
| "Enable K8s Support Bundle" | "yb.support_bundle.k8s_enabled" | "GLOBAL" | "This config lets you enable support bundle creation on k8s universes." | "Boolean" |
| "Enable On Prem Support Bundle" | "yb.support_bundle.onprem_enabled" | "GLOBAL" | "This config lets you enable support bundle creation for onprem universes." | "Boolean" |
| "Snapshot creation max attempts" | "yb.snapshot_creation.max_attempts" | "GLOBAL" | "Max attempts while waiting for AWS Snapshot Creation" | "Integer" |
| "Snapshot creation delay" | "yb.snapshot_creation.delay" | "GLOBAL" | "Delay per attempt while waiting for AWS Snapshot Creation" | "Integer" |
| "Runtime Config UI" | "yb.runtime_conf_ui.enable_for_all" | "GLOBAL" | "Allows users to view the runtime configuration properties via UI" | "Boolean" |
| "Allow Platform Downgrade" | "yb.is_platform_downgrade_allowed" | "GLOBAL" | "Allow Downgrading the Platform Version" | "Boolean" |
| "YBC Upgrade Interval" | "ybc.upgrade.scheduler_interval" | "GLOBAL" | "YBC Upgrade interval" | "Duration" |
| "YBC Universe Upgrade Batch Size" | "ybc.upgrade.universe_batch_size" | "GLOBAL" | "The number of maximum universes on which ybc will be upgraded simultaneously" | "Integer" |
| "YBC Node Upgrade Batch Size" | "ybc.upgrade.node_batch_size" | "GLOBAL" | "The number of maximum nodes on which ybc will be upgraded simultaneously" | "Integer" |
| "YBC Stable Release" | "ybc.releases.stable_version" | "GLOBAL" | "Stable version for Yb-Controller" | "String" |
| "YBC admin operation timeout" | "ybc.timeout.admin_operation_timeout_ms" | "GLOBAL" | "YBC client timeout in milliseconds for admin operations" | "Integer" |
| "YBC socket read timeout" | "ybc.timeout.socket_read_timeout_ms" | "GLOBAL" | "YBC client socket read timeout in milliseconds" | "Integer" |
| "YBC operation timeout" | "ybc.timeout.operation_timeout_ms" | "GLOBAL" | "YBC client timeout in milliseconds for operations" | "Integer" |
| "Enable Cert Reload" | "yb.features.cert_reload.enabled" | "GLOBAL" | "Enable hot reload of TLS certificates without restart of the DB nodes" | "Boolean" |
| "Delete Output File" | "yb.logs.cmdOutputDelete" | "GLOBAL" | "Flag to delete temp output file created by the shell command" | "Boolean" |
| "Shell Output Retention Duration" | "yb.logs.shell.output_retention_hours" | "GLOBAL" | "Output logs for shell commands are written to tmp folder.This setting defines how long will we wait before garbage collecting them." | "Integer" |
| "Shell Output Max Directory Size" | "yb.logs.shell.output_dir_max_size" | "GLOBAL" | "Output logs for shell commands are written to tmp folder.This setting defines rotation policy based on directory size." | "Bytes" |
| "Max Size of each log message" | "yb.logs.max_msg_size" | "GLOBAL" | "We limit the length of each log line as sometimes we dump entire output of script. If you want to debug something specific and the script output isgetting truncated in application log then increase this limit" | "Bytes" |
| "KMS Refresh Interval" | "yb.kms.refresh_interval" | "GLOBAL" | "Default refresh interval for the KMS providers." | "Duration" |
| "Enable Detailed Logs" | "yb.security.enable_detailed_logs" | "GLOBAL" | "Enable detailed security logs" | "Boolean" |
| "Task Garbage Collector Check Interval" | "yb.taskGC.gc_check_interval" | "GLOBAL" | "How frequently do we check for completed tasks in database" | "Duration" |
| "API support for backward compatible date fields" | "yb.api.backward_compatible_date" | "GLOBAL" | "Enable when a client to the YBAnywhere API wants to continue using the older date  fields in non-ISO format. Default behaviour is to not populate such deprecated API fields and only return newer date fields." | "Boolean" |
| "Allow universes to be detached/attached" | "yb.attach_detach.enabled" | "GLOBAL" | "Allow universes to be detached from a source platform and attached to dest platform" | "Boolean" |
| "Whether installation of YugabyteDB version higher than YBA version is allowed" | "yb.allow_db_version_more_than_yba_version" | "GLOBAL" | "It indicates whether the installation of YugabyteDB with a version higher than YBA version is allowed on universe nodes" | "Boolean" |
| "Path to pg_dump on the YBA node" | "db.default.pg_dump_path" | "GLOBAL" | "Set during yba-installer for both custom postgres and version specific postgres installation" | "String" |
| "Path to pg_restore on the YBA node" | "db.default.pg_restore_path" | "GLOBAL" | "Set during yba-installer for both custom postgres and version specific postgres installation" | "String" |
| "Regex for match Yugabyte DB release .tar.gz files" | "yb.regex.release_pattern.ybdb" | "GLOBAL" | "Regex pattern used to find Yugabyte DB release .tar.gz files" | "String" |
| "Regex for match Yugabyte DB release helm .tar.gz files" | "yb.regex.release_pattern.helm" | "GLOBAL" | "Regex pattern used to find Yugabyte DB helm .tar.gz files" | "String" |
| "Clock Skew" | "yb.alert.max_clock_skew_ms" | "UNIVERSE" | "Default threshold for Clock Skew alert" | "Duration" |
| "Health Log Output" | "yb.health.logOutput" | "UNIVERSE" | "It determines whether to log the output of the node health check script to the console" | "Boolean" |
| "Node Checkout Time" | "yb.health.nodeCheckTimeoutSec" | "UNIVERSE" | "The timeout (in seconds) for node check operation as part of universe health check" | "Integer" |
| "YB Upgrade Blacklist Leaders" | "yb.upgrade.blacklist_leaders" | "UNIVERSE" | "Determines (boolean) whether we enable/disable leader blacklisting when performing universe/node tasks" | "Boolean" |
| "YB Upgrade Blacklist Leader Wait Time in Ms" | "yb.upgrade.blacklist_leader_wait_time_ms" | "UNIVERSE" | "The timeout (in milliseconds) that we wait of leader blacklisting on a node to complete" | "Integer" |
| "YB Upgrade Max Follower Lag Threshold " | "yb.upgrade.max_follower_lag_threshold_ms" | "UNIVERSE" | "The maximum time (in milliseconds) that we allow a tserver to be behind its peers" | "Integer" |
| "YB Upgrade Use Single Connection Param" | "yb.upgrade.single_connection_ysql_upgrade" | "UNIVERSE" | "The flag, which controls, if YSQL catalog upgrade will be performed in single or multi connection mode.Single connection mode makes it work even on tiny DB nodes." | "Boolean" |
| "Default Releases Count" | "yb.releases.num_releases_to_keep_default" | "UNIVERSE" | "Number of Releases to Keep" | "Integer" |
| "Cloud Releases Count" | "yb.releases.num_releases_to_keep_cloud" | "UNIVERSE" | "Number Of Cloud Releases To Keep" | "Integer" |
| "DB Available Mem Limit" | "yb.dbmem.checks.mem_available_limit_kb" | "UNIVERSE" | "Minimum available memory required on DB nodes for software upgrade." | "Long" |
| "PG Based Backup" | "yb.backup.pg_based" | "UNIVERSE" | "Enable PG-based backup" | "Boolean" |
| "DB Read Write Test" | "yb.metrics.db_read_write_test" | "UNIVERSE" | "The flag defines, if we perform DB write-read check on DB nodes or not." | "Boolean" |
| "Metrics Collection Level" | "yb.metrics.collection_level" | "UNIVERSE" | "DB node metrics collection level.ALL - collect all metrics, NORMAL - default value, which only limits some per-table metrics, MINIMAL - limits both node level and further limits table level metrics we collect and OFF to completely disable metric collection." | "String" |
| "Universe Version Check Mode" | "yb.universe_version_check_mode" | "UNIVERSE" | "Possible values: NEVER, HA_ONLY, ALWAYS" | "VersionCheckMode" |
| "Override Force Universe Lock" | "yb.task.override_force_universe_lock" | "UNIVERSE" | "Whether overriding universe lock is allowed when force option is selected.If it is disabled, force option will wait for the lock to be released." | "Boolean" |
| "Enable SSH Key Expiration" | "yb.security.ssh_keys.enable_ssh_key_expiration" | "UNIVERSE" | "TODO" | "Boolean" |
| "SSh Key Expiration Threshold" | "yb.security.ssh_keys.ssh_key_expiration_threshold_days" | "UNIVERSE" | "TODO" | "Integer" |
| "NFS Directry Path" | "yb.ybc_flags.nfs_dirs" | "UNIVERSE" | "Authorised NFS directories for backups" | "String" |
| "Enable Verbose Logging" | "yb.ybc_flags.enable_verbose" | "UNIVERSE" | "Enable verbose ybc logging" | "Boolean" |
| "Max Thread Count" | "yb.perf_advisor.max_threads" | "UNIVERSE" | "Max number of threads to support parallel querying of nodes" | "Integer" |
| "Allow Scheduled YBC Upgrades" | "ybc.upgrade.allow_scheduled_upgrade" | "UNIVERSE" | "Enable Scheduled upgrade of ybc on the universe" | "Boolean" |
| "Allow User Gflags Override" | "yb.gflags.allow_user_override" | "UNIVERSE" | "Allow users to override default Gflags values" | "Boolean" |
| "Enable Trigger API" | "yb.health.trigger_api.enabled" | "UNIVERSE" | "Allow trigger_health_check API to be called" | "Boolean" |
| "Verbose Backup Log" | "yb.backup.log.verbose" | "UNIVERSE" | "Enable verbose backup logging" | "Boolean" |
| "Wait for LB for Added Nodes" | "yb.wait_for_lb_for_added_nodes" | "UNIVERSE" | "Wait for Load Balancer for added nodes" | "Boolean" |
| "Wait For master Leader timeout" | "yb.wait_for_master_leader_timeout" | "UNIVERSE" | "Time in seconds to wait for master leader before timeout for List tables API" | "Duration" |
| "Ansible Strategy" | "yb.ansible.strategy" | "UNIVERSE" | "strategy can be linear, mitogen_linear or debug" | "String" |
| "Ansible Connection Timeout Duration" | "yb.ansible.conn_timeout_secs" | "UNIVERSE" | "This is the default timeout for connection plugins to use." | "Integer" |
| "Ansible Verbosity Level" | "yb.ansible.verbosity" | "UNIVERSE" | "verbosity of ansible logs, 0 to 4 (more verbose)" | "Integer" |
| "Ansible Debug Output" | "yb.ansible.debug" | "UNIVERSE" | "Debug output (can include secrets in output)" | "Boolean" |
| "Ansible Diff Always" | "yb.ansible.diff_always" | "UNIVERSE" | "Configuration toggle to tell modules to show differences when in 'changed' status, equivalent to --diff." | "Boolean" |
| "Ansible Local Temp Directory" | "yb.ansible.local_temp" | "UNIVERSE" | "Temporary directory for Ansible to use on the controller." | "String" |
| "Universe Metrics view" | "yb.metrics.ui.topk.enable" | "UNIVERSE" | "Option to switch between old and new universe metrics UI" | "Boolean" |
| "Enable Performance Advisor" | "yb.perf_advisor.enabled" | "UNIVERSE" | "Defines if performance advisor is enabled for the universe or not" | "Boolean" |
| "Performance Advisor Run Frequency" | "yb.perf_advisor.universe_frequency_mins" | "UNIVERSE" | "Defines performance advisor run frequency for universe" | "Integer" |
| "Performance Advisor connection skew threshold" | "yb.perf_advisor.connection_skew_threshold_pct" | "UNIVERSE" | "Defines max difference between avg connections count usage and node connection count before connection skew recommendation is raised" | "Double" |
| "Performance Advisor connection skew min connections" | "yb.perf_advisor.connection_skew_min_connections" | "UNIVERSE" | "Defines minimal number of connections for connection skew recommendation to be raised" | "Integer" |
| "Performance Advisor connection skew interval mins" | "yb.perf_advisor.connection_skew_interval_mins" | "UNIVERSE" | "Defines time interval for connection skew recommendation check, in minutes" | "Integer" |
| "Performance Advisor cpu skew threshold" | "yb.perf_advisor.cpu_skew_threshold_pct" | "UNIVERSE" | "Defines max difference between avg cpu usage and node cpu usage before cpu skew recommendation is raised" | "Double" |
| "Performance Advisor cpu skew min usage" | "yb.perf_advisor.cpu_skew_min_usage_pct" | "UNIVERSE" | "Defines minimal cpu usage for cpu skew recommendation to be raised" | "Double" |
| "Performance Advisor cpu skew interval mins" | "yb.perf_advisor.cpu_skew_interval_mins" | "UNIVERSE" | "Defines time interval for cpu skew recommendation check, in minutes" | "Integer" |
| "Performance Advisor CPU usage threshold" | "yb.perf_advisor.cpu_usage_threshold" | "UNIVERSE" | "Defines max allowed average CPU usage per 10 minutes before CPU usage recommendation is raised" | "Double" |
| "Performance Advisor cpu usage interval mins" | "yb.perf_advisor.cpu_usage_interval_mins" | "UNIVERSE" | "Defines time interval for cpu usage recommendation check, in minutes" | "Integer" |
| "Performance Advisor query skew threshold" | "yb.perf_advisor.query_skew_threshold_pct" | "UNIVERSE" | "Defines max difference between avg queries count and node queries count before query skew recommendation is raised" | "Double" |
| "Performance Advisor query skew min queries" | "yb.perf_advisor.query_skew_min_queries" | "UNIVERSE" | "Defines minimal queries count for query skew recommendation to be raised" | "Integer" |
| "Performance Advisor query skew interval mins" | "yb.perf_advisor.query_skew_interval_mins" | "UNIVERSE" | "Defines time interval for query skew recommendation check, in minutes" | "Integer" |
| "Performance Advisor rejected connections threshold" | "yb.perf_advisor.rejected_conn_threshold" | "UNIVERSE" | "Defines number of rejected connections during configured interval for rejected connections recommendation to be raised " | "Integer" |
| "Performance Advisor rejected connections interval mins" | "yb.perf_advisor.rejected_conn_interval_mins" | "UNIVERSE" | "Defines time interval for rejected connections recommendation check, in minutes" | "Integer" |
| "Performance Advisor hot shard write skew threshold" | "yb.perf_advisor.hot_shard_write_skew_threshold_pct" | "UNIVERSE" | "Defines max difference between average node writes and hot shard node writes before hot shard recommendation is raised" | "Double" |
| "Performance Advisor hot shard read skew threshold" | "yb.perf_advisor.hot_shard_read_skew_threshold_pct" | "UNIVERSE" | "Defines max difference between average node reads and hot shard node reads before hot shard recommendation is raised" | "Double" |
| "Performance Advisor hot shard interval mins" | "yb.perf_advisor.hot_shard_interval_mins" | "UNIVERSE" | "Defines time interval for hot hard recommendation check, in minutes" | "Integer" |
| "Performance Advisor hot shard minimal writes" | "yb.perf_advisor.hot_shard_min_node_writes" | "UNIVERSE" | "Defines min writes for hot shard recommendation to be raised" | "Integer" |
| "Performance Advisor hot shard minimal reads" | "yb.perf_advisor.hot_shard_min_node_reads" | "UNIVERSE" | "Defines min reads for hot shard recommendation to be raised" | "Integer" |
| "Skip TLS Cert Validation" | "yb.tls.skip_cert_validation" | "UNIVERSE" | "Used to skip certificates validation for the configure phase.Possible values - ALL, HOSTNAME, NONE" | "SkipCertValidationType" |
| "Clean Orphan snapshots" | "yb.snapshot_cleanup.delete_orphan_on_startup" | "UNIVERSE" | "Clean orphan(non-scheduled) snapshots on Yugaware startup/restart" | "Boolean" |
| "Enable https on Master/TServer UI" | "yb.node_ui.https.enabled" | "UNIVERSE" | "Allow https on Master/TServer UI for a universe" | "Boolean" |
| "Helm Timeout in Seconds" | "yb.helm.timeout_secs" | "UNIVERSE" | "Timeout used for internal universe-level helm operations like install/upgrade in secs" | "Long" |
| "Enable Perf Advisor to view recommendations" | "yb.ui.feature_flags.perf_advisor" | "UNIVERSE" | "Builds recommendations to help tune our applications accordingly" | "Boolean" |
| "Promote AutoFlags" | "yb.upgrade.promote_auto_flag" | "UNIVERSE" | "Promotes Auto flags while upgrading YB-DB" | "Boolean" |
| "Allow upgrade on transit universe" | "yb.upgrade.allow_upgrade_on_transit_universe" | "UNIVERSE" | "Allow universe upgrade when nodes are in transit mode" | "Boolean" |
| "Promote AutoFlags Forcefully" | "yb.upgrade.promote_flags_forcefully" | "UNIVERSE" | "Promote AutoFlags Forcefully during software upgrade" | "Boolean" |
| "Minimum Incremental backup schedule frequency" | "yb.backup.minIncrementalScheduleFrequencyInSecs" | "UNIVERSE" | "Minimum Incremental backup schedule frequency in seconds" | "Long" |
| "Universe logs regex pattern" | "yb.support_bundle.universe_logs_regex_pattern" | "UNIVERSE" | "Universe logs regex pattern in support bundle" | "String" |
| "Postgres logs regex pattern" | "yb.support_bundle.postgres_logs_regex_pattern" | "UNIVERSE" | "Postgres logs regex pattern in support bundle" | "String" |
