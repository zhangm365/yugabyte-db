#!/usr/bin/env bash
set -ue -o pipefail

# Source common-build-env to get "log" function.
. "${BASH_SOURCE[0]%/*}/../build-support/common-build-env.sh"

readonly BASEDIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
(
cd "${BASEDIR}"
readonly APISERVERDIR=${BASEDIR}/apiserver/cmd/server
readonly UIDIR=${BASEDIR}/ui
readonly OUTDIR="${BUILD_ROOT:-/tmp/yugabyted-ui}/gobin"
readonly OUTFILE="${OUTDIR}/yugabyted-ui"
mkdir -p "${OUTDIR}"

if ! command -v npm -version &> /dev/null
then
  log "npm could not be found"
  exit 1
fi

if ! command -v go version &> /dev/null
then
  log "go lang could not be found"
  exit 1
fi

(
cd $UIDIR
npm ci
npm run build
tar cz ui | tar -C "${APISERVERDIR}" -xz
)

cd $APISERVERDIR
go build -o "${OUTFILE}"

if [[ -f "${OUTFILE}" ]]
then
  log "Yugabyted UI Binary generated successfully at ${OUTFILE}"
else
  log "Build Failed."
  exit 1
fi
)
