#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: resolve-maven-dependencies.sh source-root output-manifest" >&2
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

plugin_version="${MAVEN_DEPENDENCY_PLUGIN_VERSION:-}"
resolver_image="${MAVEN_RESOLVER_IMAGE:-}"
if [[ -z "${plugin_version}" ]]; then
  echo "MAVEN_DEPENDENCY_PLUGIN_VERSION is required for reproducible Maven resolution" >&2
  exit 64
fi
if [[ ! "${plugin_version}" =~ ^[0-9A-Za-z_.-]+$ ]]; then
  echo "invalid MAVEN_DEPENDENCY_PLUGIN_VERSION" >&2
  exit 64
fi
if [[ -z "${resolver_image}" ]]; then
  echo "MAVEN_RESOLVER_IMAGE is required for isolated Maven resolution" >&2
  exit 64
fi
if [[ ! "${resolver_image}" =~ ^[0-9A-Za-z._/:@-]+$ ]]; then
  echo "invalid MAVEN_RESOLVER_IMAGE" >&2
  exit 64
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required to isolate Maven dependency resolution" >&2
  exit 69
fi

readonly plugin_goal="org.apache.maven.plugins:maven-dependency-plugin:${plugin_version}:build-classpath"
readonly resolution_root="${output_file}.cache"
rm -rf -- "${resolution_root}"
mkdir -p "${resolution_root}/home" "${resolution_root}/repository" "${resolution_root}/classpaths"
chmod u+rwx "${resolution_root}" "${resolution_root}/home" \
  "${resolution_root}/repository" "${resolution_root}/classpaths"
readonly container_source="/workspace/source"
readonly container_out="/workspace/out"

resolve_module() {
  local pom="$1"
  local index="$2"
  local module_dir module_key container_pom classpath_file container_classpath classpath

  module_dir="$(dirname "${pom}")"
  module_key="$(realpath --relative-to="${source_root}" "${module_dir}")"
  if [[ "${module_key}" == "." ]]; then
    container_pom="${container_source}/pom.xml"
  else
    if [[ "${module_key}" == /* || "${module_key}" == ../* || "${module_key}" == *'/../'* ]]; then
      echo "Maven module escaped source root: ${module_key}" >&2
      return 70
    fi
    container_pom="${container_source}/${module_key}/pom.xml"
  fi

  classpath_file="${resolution_root}/classpaths/${index}.txt"
  container_classpath="${container_out}/classpaths/${index}.txt"
  rm -f -- "${classpath_file}"

  docker run --rm \
    --read-only \
    --cap-drop=ALL \
    --security-opt=no-new-privileges \
    --pids-limit=256 \
    --memory=2g \
    --cpus=2 \
    --tmpfs /tmp:rw,nosuid,nodev,size=128m \
    --user "$(id -u):$(id -g)" \
    --mount "type=bind,src=${source_root},dst=${container_source},readonly" \
    --mount "type=bind,src=${resolution_root},dst=${container_out}" \
    -e HOME="${container_out}/home" \
    -w "${container_source}" \
    "${resolver_image}" \
    mvn --batch-mode --no-transfer-progress -q \
      -f "${container_pom}" \
      -Dmaven.repo.local="${container_out}/repository" \
      -DincludeScope=test \
      -Dmdep.outputFile="${container_classpath}" \
      "${plugin_goal}"

  if [[ ! -f "${classpath_file}" ]]; then
    echo "isolated Maven resolver did not produce a dependency classpath for module ${module_key}" >&2
    return 70
  fi

  classpath="$(tr -d '\r\n' < "${classpath_file}")"
  if [[ -z "${classpath}" ]]; then
    return 0
  fi

  IFS=':' read -r -a entries <<< "${classpath}"
  declare -A seen=()
  local entry host_path real
  for entry in "${entries[@]}"; do
    [[ -n "${entry}" ]] || continue
    case "${entry}" in
      "${container_out}"/*)
        host_path="${resolution_root}/${entry#${container_out}/}"
        ;;
      "${container_source}"/*)
        host_path="${source_root}/${entry#${container_source}/}"
        ;;
      *)
        echo "resolved dependency escaped isolated mount boundaries: ${entry}" >&2
        return 70
        ;;
    esac
    if [[ -L "${host_path}" || ! -f "${host_path}" ]]; then
      echo "resolved dependency is not a regular file: ${host_path}" >&2
      return 70
    fi
    if [[ "${host_path}" != *.jar ]]; then
      echo "resolved dependency is not a JAR: ${host_path}" >&2
      return 70
    fi
    real="$(realpath -e -- "${host_path}")"
    if [[ -z "${seen[${real}]+x}" ]]; then
      printf '%s\t%s\n' "${module_key}" "${real}" >> "${output_file}"
      seen[${real}]=1
    fi
  done
}

for index in "${!poms[@]}"; do
  resolve_module "${poms[${index}]}" "${index}"
done
