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

gradle_build=0
for marker in settings.gradle settings.gradle.kts build.gradle build.gradle.kts; do
  if [[ -f "${source_root}/${marker}" && ! -L "${source_root}/${marker}" ]]; then
    gradle_build=1
    break
  fi
done
if [[ ${gradle_build} -eq 0 ]]; then
  exit 0
fi

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
readonly raw_output="${resolution_root}/result/classpath.tsv"
readonly container_raw_output="${container_out}/result/classpath.tsv"
readonly init_script="${resolution_root}/mcp-init.gradle"

cat > "${init_script}" <<'GRADLE'
import org.gradle.api.GradleException
import org.gradle.api.tasks.SourceSetContainer

gradle.projectsEvaluated {
    def root = gradle.rootProject
    def outputPath = System.getProperty('mcp.classpath.output')
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
                        def relative = root.projectDir.toPath().relativize(sourceDir.toPath())
                                .toString().replace('\\', '/')
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

if [[ -f "${source_root}/gradlew" && ! -L "${source_root}/gradlew" ]]; then
  gradle_command=(bash "${container_source}/gradlew")
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
  -w "${container_source}" \
  "${resolver_image}" \
  "${gradle_command[@]}" --no-daemon --console=plain --stacktrace \
    --init-script "${container_init}" \
    -Dmcp.classpath.output="${container_raw_output}" \
    __mcpResolveClasspath

if [[ ! -f "${raw_output}" ]]; then
  echo "isolated Gradle resolver did not produce a classpath manifest" >&2
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
    printf '%s\t%s\n' "${source_set}" "${real}" >> "${output_file}"
    seen[${key}]=1
  fi
done < "${raw_output}"
