#!/usr/bin/env bash
set -u -o pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: run-corpus-entry.sh owner/repository commit output-directory" >&2
  exit 64
fi
readonly repository="$1"
readonly commit="$2"
readonly output_root="$3"
if [[ -n "${PIPELINE_JAR:-}" ]]; then
  pipeline_jar="${PIPELINE_JAR}"
else
  shopt -s nullglob
  pipeline_jars=(target/metamodel-conformance-pipeline-next-*.jar)
  shopt -u nullglob
  if [[ ${#pipeline_jars[@]} -ne 1 ]]; then
    echo "Expected exactly one built pipeline JAR; found ${#pipeline_jars[@]}" >&2
    exit 66
  fi
  pipeline_jar="${pipeline_jars[0]}"
fi
readonly pipeline_jar
if [[ ! "${repository}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then echo "invalid repository name" >&2; exit 64; fi
if [[ ! "${commit}" =~ ^[0-9a-f]{40}$ ]]; then echo "invalid commit SHA" >&2; exit 64; fi
mkdir -p "${output_root}"
readonly work_root="$(mktemp -d)"
trap 'rm -rf -- "${work_root}"' EXIT
source_root="${work_root}/source"
result_root="${output_root}/result"
dependency_manifest="${work_root}/dependency-manifest.tsv"
mkdir -p "${result_root}"
clone_exit=0
git clone --quiet --no-checkout --filter=blob:none "https://github.com/${repository}.git" "${source_root}" >"${output_root}/clone.log" 2>&1 || clone_exit=$?
if [[ ${clone_exit} -eq 0 ]]; then git -C "${source_root}" checkout --quiet --detach "${commit}" >>"${output_root}/clone.log" 2>&1 || clone_exit=$?; fi

java_files=0
dependency_resolution_exit=99
dependency_manifest_rows=0
dependency_jar_count=0
dependency_source_set_count=0
dependency_module_count=0
analysis_exit=99
verification_exit=99

if [[ ${clone_exit} -eq 0 ]]; then
  java_files="$(find "${source_root}" -type f -name '*.java' | wc -l)"
  bash ./scripts/resolve-java-dependencies.sh "${source_root}" "${dependency_manifest}" >"${output_root}/dependency-resolution.log" 2>&1
  dependency_resolution_exit=$?
  dependency_args=()
  if [[ ${dependency_resolution_exit} -eq 0 && -s "${dependency_manifest}" ]]; then
    dependency_args+=(--dependency-manifest "${dependency_manifest}")
    dependency_manifest_rows="$(wc -l < "${dependency_manifest}")"
    dependency_jar_count="$(cut -f2 "${dependency_manifest}" | sort -u | wc -l)"
    dependency_source_set_count="$(cut -f1 "${dependency_manifest}" | sort -u | wc -l)"
    dependency_module_count="$(awk -F '\t' '
      {
        key=$1
        if (key ~ /^src\/[^/]+\/java$/) { print "." }
        else { sub(/\/src\/[^/]+\/java$/, "", key); print key }
      }
    ' "${dependency_manifest}" | sort -u | wc -l)"
  fi
  java -jar "${pipeline_jar}" analyze --source "${source_root}" --output "${result_root}" "${dependency_args[@]}" >"${output_root}/analysis.log" 2>&1
  analysis_exit=$?
  if [[ -f "${result_root}/verification-capsule.json" ]]; then
    java -jar "${pipeline_jar}" verify-capsule --capsule "${result_root}/verification-capsule.json" >"${output_root}/verification.log" 2>&1
    verification_exit=$?
  fi
fi

common_jq_args=(
  --arg repository "${repository}"
  --arg commit "${commit}"
  --argjson cloneExit "${clone_exit}"
  --argjson dependencyResolutionExit "${dependency_resolution_exit}"
  --argjson dependencyManifestRows "${dependency_manifest_rows}"
  --argjson dependencyJars "${dependency_jar_count}"
  --argjson dependencySourceSets "${dependency_source_set_count}"
  --argjson dependencyModules "${dependency_module_count}"
  --argjson analysisExit "${analysis_exit}"
  --argjson javaFiles "${java_files}"
)
if [[ -f "${result_root}/verification-capsule.json" ]]; then
  jq "${common_jq_args[@]}" --argjson verificationExit "${verification_exit}" '
    {
      repository: $repository, commit: $commit, javaFiles: $javaFiles,
      dependencyManifestRows: $dependencyManifestRows,
      dependencyJars: $dependencyJars,
      dependencySourceSets: $dependencySourceSets,
      dependencyModules: $dependencyModules,
      cloneExit: $cloneExit, dependencyResolutionExit: $dependencyResolutionExit,
      analysisExit: $analysisExit, verificationExit: $verificationExit,
      toolOutcome: (if $verificationExit == 0 then "ANALYZED" else "CAPSULE_INVALID" end),
      observationDiagnostics: [.observationDiagnostics[] | {kind, sourcePath, line, message}],
      decisions: [.decisions[] | {invariantId, status, witnessCount: (.witnesses | length)}]
    }' "${result_root}/verification-capsule.json" >"${output_root}/report.json"
else
  jq -n "${common_jq_args[@]}" '
    {
      repository: $repository, commit: $commit, javaFiles: $javaFiles,
      dependencyManifestRows: $dependencyManifestRows,
      dependencyJars: $dependencyJars,
      dependencySourceSets: $dependencySourceSets,
      dependencyModules: $dependencyModules,
      cloneExit: $cloneExit, dependencyResolutionExit: $dependencyResolutionExit,
      analysisExit: $analysisExit, verificationExit: 99,
      toolOutcome: "TOOL_FAILURE", decisions: []
    }' >"${output_root}/report.json"
fi
jq -c . "${output_root}/report.json" | sed 's/^/CORPUS_RESULT /'
exit 0
