#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: resolve-java-dependencies.sh source-root output-manifest" >&2
  exit 64
fi

source_root="$1"
output_file="$2"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
mkdir -p "$(dirname "${output_file}")"
: > "${output_file}"

work_root="$(mktemp -d)"
trap 'rm -rf -- "${work_root}"' EXIT
maven_manifest="${work_root}/maven.tsv"
gradle_manifest="${work_root}/gradle.tsv"
: > "${maven_manifest}"
: > "${gradle_manifest}"

"${script_dir}/resolve-maven-dependencies.sh" "${source_root}" "${maven_manifest}"
"${script_dir}/resolve-gradle-dependencies.sh" "${source_root}" "${gradle_manifest}"

cut -f1 "${maven_manifest}" "${gradle_manifest}" | sed '/^$/d' | sort -u \
  > "${work_root}/source-sets.txt"
while IFS= read -r source_set; do
  [[ -n "${source_set}" ]] || continue
  awk -F '\t' -v key="${source_set}" '$1 == key { print $2 }' \
    "${maven_manifest}" > "${work_root}/maven-list.txt"
  awk -F '\t' -v key="${source_set}" '$1 == key { print $2 }' \
    "${gradle_manifest}" > "${work_root}/gradle-list.txt"

  if [[ -s "${work_root}/maven-list.txt" && -s "${work_root}/gradle-list.txt" ]]; then
    if ! diff -u "${work_root}/maven-list.txt" "${work_root}/gradle-list.txt" >/dev/null; then
      echo "dependency resolvers disagree for source set ${source_set}" >&2
      : > "${output_file}"
      exit 70
    fi
    selected="${work_root}/maven-list.txt"
  elif [[ -s "${work_root}/maven-list.txt" ]]; then
    selected="${work_root}/maven-list.txt"
  else
    selected="${work_root}/gradle-list.txt"
  fi

  while IFS= read -r jar; do
    [[ -n "${jar}" ]] || continue
    printf '%s\t%s\n' "${source_set}" "${jar}" >> "${output_file}"
  done < "${selected}"
done < "${work_root}/source-sets.txt"
