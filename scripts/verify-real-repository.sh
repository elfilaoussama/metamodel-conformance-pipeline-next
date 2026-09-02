#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY_URL="https://github.com/elfilaoussama/metamodel-conformance-pipeline.git"
readonly REPOSITORY_COMMIT="25a80241f7514aa0a9e9a5ad2c5ec3fa90277527"
readonly PIPELINE_JAR="target/metamodel-conformance-pipeline-next-0.9.0-SNAPSHOT.jar"

integration_root="$(mktemp -d)"
readonly integration_root
trap 'rm -rf -- "${integration_root}"' EXIT

git clone --quiet --no-checkout --filter=blob:none "${REPOSITORY_URL}" "${integration_root}/source"
git -C "${integration_root}/source" checkout --quiet --detach "${REPOSITORY_COMMIT}"

analysis_exit=0
java -jar "${PIPELINE_JAR}" analyze \
  --source "${integration_root}/source" \
  --output "${integration_root}/result" \
  --external-parent java.lang.Exception \
  --external-parent javax.swing.JFrame \
  --external-parent javax.swing.JDialog \
  --external-parent javax.swing.SwingWorker \
  --external-parent javax.swing.table.AbstractTableModel \
  --external-parent org.eclipse.jgit.lib.ProgressMonitor || analysis_exit=$?

if [[ ${analysis_exit} -ne 3 ]]; then
  echo "Expected exit 3 because javac cannot close inherited-member evidence for this dependency-rich repository; got ${analysis_exit}" >&2
  exit 1
fi

java -jar "${PIPELINE_JAR}" verify-capsule \
  --capsule "${integration_root}/result/verification-capsule.json"

jq --exit-status '
  (.decisions | map({key: .invariantId, value: .status}) | from_entries) as $status |
  any(.observationDiagnostics[]; .kind == "EVIDENCE_INCOMPLETE") and
  all(.observationDiagnostics[]; .kind != "PARSE_ERROR") and
  $status["exclusive-declaration-ownership"] == "CONFORMANT" and
  $status["acyclic-generalization"] == "CONFORMANT" and
  $status["local-namespace-uniqueness"] == "CONFORMANT" and
  $status["inherited-view-consistency"] == "NOT_EVALUATED" and
  $status["local-inherited-separation"] == "NOT_EVALUATED"
' "${integration_root}/result/verification-capsule.json" >/dev/null
