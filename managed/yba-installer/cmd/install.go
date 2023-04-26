package cmd

import (
	"os"

	"github.com/spf13/cobra"

	"github.com/yugabyte/yugabyte-db/managed/yba-installer/common"
	log "github.com/yugabyte/yugabyte-db/managed/yba-installer/logging"
	"github.com/yugabyte/yugabyte-db/managed/yba-installer/preflight"
	"github.com/yugabyte/yugabyte-db/managed/yba-installer/ybactlstate"
)

var installCmd = &cobra.Command{
	Use:   "install",
	Short: "Install YugabyteDB Anywhere.",
	Long: `
        The install command will install the version of YugabyteDB Anywhere associated with the
				downloaded version of YBA Installer onto the local machine.
        `,
	Args: cobra.NoArgs,
	PreRun: func(cmd *cobra.Command, args []string) {
		if _, err := os.Stat(common.YbaInstalledMarker()); err == nil {
			log.Fatal("YugabyteDB Anywhere already installed, cannot install twice.")
		}
		if common.RunFromInstalled() {
			log.Fatal("install must be run from the yba bundle that is getting installed.")
		}
	},
	Run: func(cmd *cobra.Command, args []string) {

		// Install the license if it is provided.
		if licensePath != "" {
			InstallLicense()
		}

		// Preflight checks
		results := preflight.Run(preflight.InstallChecksWithPostgres, skippedPreflightChecks...)
		// Only print results if we should fail.
		if preflight.ShouldFail(results) {
			preflight.PrintPreflightResults(results)
			log.Fatal("Preflight checks failed. To skip (not recommended), " +
				"rerun the command with --skip_preflight <check name1>,<check name2>")
		}

		// Run install
		ybaCtl.MarkYBAInstallStart()
		common.Install(common.GetVersion())

		for _, name := range serviceOrder {
			log.Info("About to install component " + name)
			if err := services[name].Install(); err != nil {
				log.Fatal("Failed while installing " + name + ": " + err.Error())
			}
			log.Info("Completed installing component " + name)
		}
		if err := ybaCtl.Install(); err != nil {
			log.Fatal("failed to install yba-ctl")
		}
		state := ybactlstate.New()
		if err := ybactlstate.StoreState(state); err != nil {
			log.Fatal("failed to write state: " + err.Error())
		}
		common.WaitForYBAReady()

		var statuses []common.Status
		for _, service := range services {
			status, err := service.Status()
			if err != nil {
				log.Fatal("failed to get status: " + err.Error())
			}
			statuses = append(statuses, status)
			if !common.IsHappyStatus(status) {
				log.Fatal(status.Service + " is not running! Install might have failed, please check " +
					common.YbactlLogFile())
			}
		}

		common.PrintStatus(statuses...)
		log.Info("Successfully installed YugabyteDB Anywhere!")
	},
}

func init() {
	installCmd.Flags().StringSliceVarP(&skippedPreflightChecks, "skip_preflight", "s",
		[]string{}, "Preflight checks to skip by name")
	installCmd.Flags().StringVarP(&licensePath, "license-path", "l", "", "path to license file")

	// Install must be run from directory of yba version
	rootCmd.AddCommand(installCmd)
}
