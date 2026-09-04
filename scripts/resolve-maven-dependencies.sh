#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: resolve-maven-dependencies.sh source-root output-file" >&2
  exit 64
fi

source_root="$1"
output_file="$2"
if [[ ! -d "${source_root}" || -L "${source_root}" ]]; then
  echo "source root is not a regular directory: ${source_root}" >&2
  exit 66
fi
source_root="$(cd "${source_root}" && pwd -P)"
mkdir -p "$(dirname "${output_file}")"
: > "${output_file}"

mapfile -d '' poms < <(
  find "${source_root}" -type f -name pom.xml \
    -not -path '*/.git/*' -not -path '*/target/*' -print0 | sort -z
)
if [[ ${#poms[@]} -eq 0 ]]; then
  exit 0
fi
if [[ ${#poms[@]} -ne 1 ]]; then
  echo "Maven dependency auto-resolution is fail-closed for multi-module trees; found ${#poms[@]} pom.xml files" >&2
  exit 65
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven is required to resolve dependencies for a Maven project" >&2
  exit 69
fi

classpath_file="$(mktemp)"
trap 'rm -f -- "${classpath_file}"' EXIT

mvn --batch-mode --no-transfer-progress -q \
  -f "${poms[0]}" \
  -DincludeScope=test \
  -Dmdep.outputAbsoluteArtifactFilename=true \
  -Dmdep.outputFile="${classpath_file}" \
  dependency:build-classpath

if [[ ! -f "${classpath_file}" ]]; then
  echo "Maven did not produce a dependency classpath" >&2
  exit 70
fi

classpath="$(tr -d '\r\n' < "${classpath_file}")"
if [[ -z "${classpath}" ]]; then
  exit 0
fi

IFS=':' read -r -a entries <<< "${classpath}"
declare -A seen=()
for entry in "${entries[@]}"; do
  [[ -n "${entry}" ]] || continue
  if [[ -L "${entry}" || ! -f "${entry}" ]]; then
    echo "resolved dependency is not a regular file: ${entry}" >&2
    exit 70
  fi
  if [[ "${entry}" != *.jar ]]; then
    echo "resolved dependency is not a JAR: ${entry}" >&2
    exit 70
  fi
  real="$(realpath -e -- "${entry}")"
  if [[ -z "${seen[${real}]+x}" ]]; then
    printf '%s\n' "${real}" >> "${output_file}"
    seen[${real}]=1
  fi
done
