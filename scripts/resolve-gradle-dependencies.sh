#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: resolve-gradle-dependencies.sh source-root output-manifest" >&2
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

mapfile -d '' settings_files < <(
  find "${source_root}" -type f \( -name settings.gradle -o -name settings.gradle.kts \) \
    -not -path '*/.git/*' -not -path '*/.gradle/*' -not -path '*/build/*' -print0 | sort -z
)
declare -A settings_roots=()
for settings in "${settings_files[@]}"; do
  settings_roots["$(dirname "${settings}")"]=1
done

declare -A build_roots_map=()
for root in "${!settings_roots[@]}"; do
  build_roots_map["${root}"]=1
done
mapfile -d '' build_files < <(
  find "${source_root}" -type f \( -name build.gradle -o -name build.gradle.kts \) \
    -not -path '*/.git/*' -not -path '*/.gradle/*' -not -path '*/build/*' -print0 | sort -z
)
for build_file in "${build_files[@]}"; do
  dir="$(dirname "${build_file}")"
  claimed=0
  for settings_root in "${!settings_roots[@]}"; do
    if [[ "${dir}" == "${settings_root}" || "${dir}" == "${settings_root}/"* ]]; then
      claimed=1
      break
    fi
  done
  if [[ ${claimed} -eq 0 ]]; then
    build_roots_map["${dir}"]=1
  fi
done
if [[ ${#build_roots_map[@]} -eq 0 ]]; then
  exit 0
fi
mapfile -t build_roots < <(printf '%s\n' "${!build_roots_map[@]}" | sort)

resolver_image="${GRADLE_RESOLVER_IMAGE:-}"
if [[ -z "${resolver_image}" ]]; then
  echo "GRADLE_RESOLVER_IMAGE is required for isolated Gradle resolution" >&2
  exit 64
fi
if [[ ! "${resolver_image}" =~ ^[0-9A-Za-z._/:@-]+$ ]]; then
  echo "invalid GRADLE_RESOLVER_IMAGE" >&2
  exit 64
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required to isolate Gradle dependency resolution" >&2
  exit 69
fi

readonly resolution_root="${output_file}.cache"
rm -rf -- "${resolution_root}"
mkdir -p "${resolution_root}/home" "${resolution_root}/gradle" "${resolution_root}/result"
chmod u+rwx "${resolution_root}" "${resolution_root}/home" \
  "${resolution_root}/gradle" "${resolution_root}/result"
readonly container_source="/workspace/source"
readonly container_out="/workspace/out"
readonly container_init="/workspace/mcp-init.gradle"
readonly init_script="${resolution_root}/mcp-init.gradle"

cat > "${init_script}" <<'GRADLE'
import org.gradle.api.GradleException
import org.gradle.api.tasks.SourceSetContainer

gradle.projectsEvaluated {
    def root = gradle.rootProject
    def outputPath = System.getProperty('mcp.classpath.output')
    def prefix = System.getProperty('mcp.source.prefix', '.')
    if (outputPath == null || outputPath.trim().isEmpty()) {
        throw new GradleException('mcp.classpath.output is required')
    }
    def output = new File(outputPath)
    output.parentFile.mkdirs()
    output.text = ''

    root.tasks.register('__mcpResolveClasspath') {
        doLast {
            root.allprojects.sort { a, b -> a.path <=> b.path }.each { project ->
                boolean android = project.plugins.hasPlugin('com.android.application') ||
                        project.plugins.hasPlugin('com.android.library') ||
                        project.plugins.hasPlugin('com.android.test') ||
                        project.plugins.hasPlugin('com.android.dynamic-feature')
                if (android) {
                    def conventional = [new File(project.projectDir, 'src/main/java'),
                                        new File(project.projectDir, 'src/test/java')]
                            .any { dir -> dir.isDirectory() && project.fileTree(dir)
                                    .matching { include '**/*.java' }.files.size() > 0 }
                    if (conventional) {
                        throw new GradleException(
                                'Android variant-dependent Java classpath is ambiguous for project ' + project.path)
                    }
                    return
                }

                def sourceSets = project.extensions.findByType(SourceSetContainer)
                if (sourceSets == null) {
                    return
                }
                sourceSets.findAll { it.name == 'main' || it.name == 'test' }
                        .sort { a, b -> a.name <=> b.name }
                        .each { sourceSet ->
                    sourceSet.java.srcDirs.sort { a, b -> a.path <=> b.path }.each { sourceDir ->
                        if (!sourceDir.isDirectory()) {
                            return
                        }
                        def javaFiles = project.fileTree(sourceDir).matching { include '**/*.java' }.files
                        if (javaFiles.isEmpty()) {
                            return
                        }
                        def withinBuild = root.projectDir.toPath().relativize(sourceDir.toPath())
                                .toString().replace('\\', '/')
                        def relative = prefix == '.' ? withinBuild : prefix + '/' + withinBuild
                        if (!(relative ==~ /(?:[^\/]+\/)*src\/(?:main|test)\/java/)) {
                            return
                        }
                        def files = sourceSet.compileClasspath.files.toList()
                                .sort { a, b -> a.path <=> b.path }
                        files.each { entry ->
                            if (!entry.isFile() || !entry.name.endsWith('.jar')) {
                                throw new GradleException(
                                        'non-JAR or unavailable classpath entry for ' + relative + ': ' + entry)
                            }
                            output << relative + '\t' + entry.canonicalPath + System.lineSeparator()
                        }
                    }
                }
            }
        }
    }
}
GRADLE
chmod a-w "${init_script}"

merge_manifest() {
  local current="$1"
  local source_set existing_list current_list
  mapfile -t source_sets < <(cut -f1 "${current}" | sed '/^$/d' | sort -u)
  for source_set in "${source_sets[@]}"; do
    existing_list="${resolution_root}/result/existing-list.txt"
    current_list="${resolution_root}/result/current-list.txt"
    awk -F '\t' -v key="${source_set}" '$1 == key { print $2 }' "${output_file}" > "${existing_list}"
    awk -F '\t' -v key="${source_set}" '$1 == key { print $2 }' "${current}" > "${current_list}"
    if [[ -s "${existing_list}" ]]; then
      if ! diff -u "${existing_list}" "${current_list}" >/dev/null; then
        echo "independent Gradle builds disagree for source set ${source_set}" >&2
        : > "${output_file}"
        return 70
      fi
      continue
    fi
    while IFS= read -r jar; do
      [[ -n "${jar}" ]] || continue
      printf '%s\t%s\n' "${source_set}" "${jar}" >> "${output_file}"
    done < "${current_list}"
  done
}

for index in "${!build_roots[@]}"; do
  build_root="${build_roots[${index}]}"
  build_key="$(realpath --relative-to="${source_root}" "${build_root}")"
  if [[ "${build_key}" == /* || "${build_key}" == ../* || "${build_key}" == *'/../'* ]]; then
    echo "Gradle build root escaped source root: ${build_key}" >&2
    exit 70
  fi
  if [[ "${build_key}" == "." ]]; then
    container_build="${container_source}"
  else
    container_build="${container_source}/${build_key}"
  fi

  raw_output="${resolution_root}/result/classpath-${index}.tsv"
  container_raw_output="${container_out}/result/classpath-${index}.tsv"
  current_manifest="${resolution_root}/result/manifest-${index}.tsv"
  : > "${current_manifest}"

  if [[ -f "${build_root}/gradlew" && ! -L "${build_root}/gradlew" ]]; then
    gradle_command=(bash "${container_build}/gradlew")
  else
    gradle_command=(gradle)
  fi

  docker run --rm \
    --read-only \
    --cap-drop=ALL \
    --security-opt=no-new-privileges \
    --pids-limit=256 \
    --memory=2g \
    --cpus=2 \
    --tmpfs /tmp:rw,nosuid,nodev,size=256m \
    --user "$(id -u):$(id -g)" \
    --mount "type=bind,src=${source_root},dst=${container_source},readonly" \
    --mount "type=bind,src=${resolution_root},dst=${container_out}" \
    --mount "type=bind,src=${init_script},dst=${container_init},readonly" \
    -e HOME="${container_out}/home" \
    -e GRADLE_USER_HOME="${container_out}/gradle" \
    -w "${container_build}" \
    "${resolver_image}" \
    "${gradle_command[@]}" --no-daemon --console=plain --stacktrace \
      --init-script "${container_init}" \
      -Dmcp.classpath.output="${container_raw_output}" \
      -Dmcp.source.prefix="${build_key}" \
      __mcpResolveClasspath

  if [[ ! -f "${raw_output}" ]]; then
    echo "isolated Gradle resolver did not produce a classpath manifest for ${build_key}" >&2
    exit 70
  fi

  declare -A seen=()
  while IFS=$'\t' read -r source_set entry extra; do
    [[ -z "${source_set}" && -z "${entry}" && -z "${extra:-}" ]] && continue
    if [[ -z "${source_set}" || -z "${entry}" || -n "${extra:-}" ]]; then
      echo "invalid Gradle resolver output row" >&2
      : > "${output_file}"
      exit 70
    fi
    if [[ ! "${source_set}" =~ ^([^/]+/)*src/(main|test)/java$ ]] \
        || [[ "${source_set}" == /* || "${source_set}" == *'/../'* || "${source_set}" == ../* ]]; then
      echo "invalid Gradle source-set identity: ${source_set}" >&2
      : > "${output_file}"
      exit 70
    fi

    host_path=''
    case "${entry}" in
      "${container_out}"/*)
        host_path="${resolution_root}/${entry#${container_out}/}"
        ;;
      "${container_source}"/*)
        host_path="${source_root}/${entry#${container_source}/}"
        ;;
      *)
        echo "resolved Gradle dependency escaped isolated mount boundaries: ${entry}" >&2
        : > "${output_file}"
        exit 70
        ;;
    esac
    if [[ -L "${host_path}" || ! -f "${host_path}" || "${host_path}" != *.jar ]]; then
      echo "resolved Gradle dependency is not a regular JAR: ${host_path}" >&2
      : > "${output_file}"
      exit 70
    fi
    real="$(realpath -e -- "${host_path}")"
    key="${source_set}"$'\0'"${real}"
    if [[ -z "${seen[${key}]+x}" ]]; then
      printf '%s\t%s\n' "${source_set}" "${real}" >> "${current_manifest}"
      seen[${key}]=1
    fi
  done < "${raw_output}"
  merge_manifest "${current_manifest}"
done
