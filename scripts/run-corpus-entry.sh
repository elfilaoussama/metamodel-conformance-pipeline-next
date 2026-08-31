#!/usr/bin/env bash
set -u -o pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: run-corpus-entry.sh owner/repository commit output-directory" >&2
  exit 64
fi

readonly repository="$1"
readonly commit="$2"
readonly output_root="$3"
readonly pipeline_jar="${PIPELINE_JAR:-target/metamodel-conformance-pipeline-next-0.4.0-SNAPSHOT.jar}"

if [[ ! "${repository}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  echo "invalid repository name" >&2
  exit 64
fi
if [[ ! "${commit}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "invalid commit SHA" >&2
  exit 64
fi

mkdir -p "${output_root}"
readonly work_root="$(mktemp -d)"
trap 'rm -rf -- "${work_root}"' EXIT

source_root="${work_root}/source"
discovery_root="${work_root}/discovery"
result_root="${output_root}/result"
mkdir -p "${result_root}"

clone_exit=0
git clone --quiet --no-checkout --filter=blob:none \
  "https://github.com/${repository}.git" "${source_root}" \
  >"${output_root}/clone.log" 2>&1 || clone_exit=$?
if [[ ${clone_exit} -eq 0 ]]; then
  git -C "${source_root}" checkout --quiet --detach "${commit}" \
    >>"${output_root}/clone.log" 2>&1 || clone_exit=$?
fi

java_files=0
discovery_exit=99
analysis_exit=99
verification_exit=99
external_parent_count=0

if [[ ${clone_exit} -eq 0 ]]; then
  java_files="$(find "${source_root}" -type f -name '*.java' | wc -l)"

  java -jar "${pipeline_jar}" analyze \
    --source "${source_root}" \
    --output "${discovery_root}" \
    >"${output_root}/discovery.log" 2>&1
  discovery_exit=$?

  sed -n '/^Unresolved parents:/,/^Capsule:/ {
    /^  / s/^  \([^ ]*\) (.*$/\1/p
  }' "${output_root}/discovery.log" | sort -u >"${output_root}/external-parents.txt"

  external_args=()
  while IFS= read -r parent; do
    if [[ -n "${parent}" ]]; then
      external_args+=(--external-parent "${parent}")
    fi
  done <"${output_root}/external-parents.txt"
  external_parent_count="${#external_args[@]}"
  external_parent_count="$((external_parent_count / 2))"

  java -jar "${pipeline_jar}" analyze \
    --source "${source_root}" \
    --output "${result_root}" \
    "${external_args[@]}" \
    >"${output_root}/analysis.log" 2>&1
  analysis_exit=$?

  if [[ -f "${result_root}/verification-capsule.json" ]]; then
    java -jar "${pipeline_jar}" verify-capsule \
      --capsule "${result_root}/verification-capsule.json" \
      >"${output_root}/verification.log" 2>&1
    verification_exit=$?
  fi
fi

if [[ -f "${result_root}/verification-capsule.json" ]]; then
  jq --arg repository "${repository}" \
    --arg commit "${commit}" \
    --argjson cloneExit "${clone_exit}" \
    --argjson discoveryExit "${discovery_exit}" \
    --argjson analysisExit "${analysis_exit}" \
    --argjson verificationExit "${verification_exit}" \
    --argjson javaFiles "${java_files}" \
    --argjson externalParents "${external_parent_count}" \
    '{
      repository: $repository,
      commit: $commit,
      javaFiles: $javaFiles,
      externalParents: $externalParents,
      cloneExit: $cloneExit,
      discoveryExit: $discoveryExit,
      analysisExit: $analysisExit,
      verificationExit: $verificationExit,
      toolOutcome: (if $verificationExit == 0 then "ANALYZED" else "CAPSULE_INVALID" end),
      decisions: [.decisions[] | {
        constraint,
        status,
        witnessCount: (.witnesses | length)
      }]
    }' "${result_root}/verification-capsule.json" >"${output_root}/report.json"
else
  jq -n --arg repository "${repository}" \
    --arg commit "${commit}" \
    --argjson cloneExit "${clone_exit}" \
    --argjson discoveryExit "${discovery_exit}" \
    --argjson analysisExit "${analysis_exit}" \
    --argjson javaFiles "${java_files}" \
    '{
      repository: $repository,
      commit: $commit,
      javaFiles: $javaFiles,
      cloneExit: $cloneExit,
      discoveryExit: $discoveryExit,
      analysisExit: $analysisExit,
      verificationExit: 99,
      toolOutcome: "TOOL_FAILURE",
      decisions: []
    }' >"${output_root}/report.json"
fi

jq -c . "${output_root}/report.json" | sed 's/^/CORPUS_RESULT /'
exit 0
