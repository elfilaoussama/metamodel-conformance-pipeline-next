#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY_URL="https://github.com/elfilaoussama/metamodel-conformance-pipeline.git"
readonly REPOSITORY_COMMIT="25a80241f7514aa0a9e9a5ad2c5ec3fa90277527"
readonly PIPELINE_JAR="target/metamodel-conformance-pipeline-next-0.4.0-SNAPSHOT.jar"

integration_root="$(mktemp -d)"
readonly integration_root
trap 'rm -rf -- "${integration_root}"' EXIT

git clone --quiet --no-checkout --filter=blob:none "${REPOSITORY_URL}" "${integration_root}/source"
git -C "${integration_root}/source" checkout --quiet --detach "${REPOSITORY_COMMIT}"

java -jar "${PIPELINE_JAR}" analyze \
  --source "${integration_root}/source" \
  --output "${integration_root}/result" \
  --external-parent java.lang.Exception \
  --external-parent javax.swing.JFrame \
  --external-parent javax.swing.JDialog \
  --external-parent javax.swing.SwingWorker \
  --external-parent javax.swing.table.AbstractTableModel \
  --external-parent org.eclipse.jgit.lib.ProgressMonitor

java -jar "${PIPELINE_JAR}" verify-capsule \
  --capsule "${integration_root}/result/verification-capsule.json"

grep --quiet '"status" : "CONFORMANT"' \
  "${integration_root}/result/verification-capsule.json"

if grep --extended-regexp --quiet '"status" : "(NON_CONFORMANT|NOT_EVALUATED)"' \
  "${integration_root}/result/verification-capsule.json"; then
  echo "Real-repository capsule contains a non-conformant or not-evaluated invariant" >&2
  exit 1
fi
