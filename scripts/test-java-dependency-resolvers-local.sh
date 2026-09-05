#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: test-java-dependency-resolvers-local.sh repository-root" >&2
  exit 64
fi
repo_root="$(realpath -e -- "$1")"
for script in resolve-gradle-dependencies.sh resolve-java-dependencies.sh; do
  [[ -f "$repo_root/scripts/$script" && ! -L "$repo_root/scripts/$script" ]] \
    || { echo "$script unavailable" >&2; exit 66; }
  bash -n "$repo_root/scripts/$script"
done

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
project="$work/project"
mkdir -p "$project/src/main/java/app" "$project/src/test/java/app" "$work/bin"
printf 'plugins { id "java" }\n' > "$project/build.gradle"
printf 'rootProject.name="fixture"\n' > "$project/settings.gradle"
printf 'package app; class Main {}\n' > "$project/src/main/java/app/Main.java"
printf 'package app; class Test {}\n' > "$project/src/test/java/app/Test.java"
printf '#!/usr/bin/env bash\nexit 99\n' > "$project/gradlew"

arguments="$work/docker-arguments.txt"
cat > "$work/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_DOCKER_ARGUMENTS"
out_mount=''
workdir=''
prefix='.'
for argument in "$@"; do
  case "$argument" in
    type=bind,src=*,dst=/workspace/out)
      out_mount="${argument#type=bind,src=}"
      out_mount="${out_mount%%,dst=/workspace/out}" ;;
    -w) capture_workdir=1 ;;
    -Dmcp.source.prefix=*) prefix="${argument#*=}" ;;
    -Dmcp.classpath.output=*) output="${argument#*=}" ;;
    *)
      if [[ "${capture_workdir:-0}" -eq 1 ]]; then
        workdir="$argument"
        capture_workdir=0
      fi ;;
  esac
done
test -n "$out_mount"
test -n "${output:-}"
mkdir -p "$out_mount/result" "$out_mount/gradle/cache"
index="$(basename "${output%.tsv}")"
printf jar > "$out_mount/gradle/cache/${index}-main.jar"
printf jar > "$out_mount/gradle/cache/${index}-test.jar"
if [[ "$prefix" == "." ]]; then
  main_key='src/main/java'
  test_key='src/test/java'
else
  main_key="$prefix/src/main/java"
  test_key="$prefix/src/test/java"
fi
host_output="$out_mount/${output#/workspace/out/}"
printf '%s\t/workspace/out/gradle/cache/%s-main.jar\n%s\t/workspace/out/gradle/cache/%s-main.jar\n%s\t/workspace/out/gradle/cache/%s-test.jar\n' \
  "$main_key" "$index" "$test_key" "$index" "$test_key" "$index" > "$host_output"
DOCKER
chmod +x "$work/bin/docker"

PATH="$work/bin:$PATH" \
FAKE_DOCKER_ARGUMENTS="$arguments" \
GRADLE_RESOLVER_IMAGE='local-test-gradle-image' \
  "$repo_root/scripts/resolve-gradle-dependencies.sh" \
  "$project" "$work/gradle.tsv"

[[ $(wc -l < "$work/gradle.tsv") -eq 3 ]]
grep -q '^src/main/java' "$work/gradle.tsv"
grep -q '^src/test/java' "$work/gradle.tsv"
grep -q -- '--read-only' "$arguments"
grep -q -- '--cap-drop=ALL' "$arguments"
grep -q -- '--security-opt=no-new-privileges' "$arguments"
grep -q -- 'bash /workspace/source/gradlew' "$arguments"
grep -q -- '--init-script /workspace/mcp-init.gradle' "$arguments"

nested="$work/nested-project"
mkdir -p "$nested/module/src/main/java/app"
printf 'plugins { id "java" }\n' > "$nested/module/build.gradle"
printf 'rootProject.name="nested-fixture"\n' > "$nested/module/settings.gradle"
printf 'package app; class Nested {}\n' > "$nested/module/src/main/java/app/Nested.java"
printf '#!/usr/bin/env bash\nexit 99\n' > "$nested/module/gradlew"
: > "$arguments"
PATH="$work/bin:$PATH" \
FAKE_DOCKER_ARGUMENTS="$arguments" \
GRADLE_RESOLVER_IMAGE='local-test-gradle-image' \
  "$repo_root/scripts/resolve-gradle-dependencies.sh" \
  "$nested" "$work/nested.tsv"

grep -q '^module/src/main/java' "$work/nested.tsv"
grep -q -- '-w /workspace/source/module' "$arguments"
grep -q -- 'bash /workspace/source/module/gradlew' "$arguments"
grep -q -- '-Dmcp.source.prefix=module' "$arguments"

dispatch_root="$work/dispatch"
mkdir -p "$dispatch_root/scripts" "$dispatch_root/project"
cp "$repo_root/scripts/resolve-java-dependencies.sh" "$dispatch_root/scripts/"
cat > "$dispatch_root/scripts/resolve-maven-dependencies.sh" <<'MAVEN'
#!/usr/bin/env bash
cat > "$2" <<ROWS
module-m/src/main/java	/cache/maven.jar
shared/src/main/java	/cache/shared.jar
ROWS
MAVEN
cat > "$dispatch_root/scripts/resolve-gradle-dependencies.sh" <<'GRADLE'
#!/usr/bin/env bash
cat > "$2" <<ROWS
module-g/src/main/java	/cache/gradle.jar
shared/src/main/java	/cache/shared.jar
ROWS
GRADLE
chmod +x "$dispatch_root/scripts"/*.sh
"$dispatch_root/scripts/resolve-java-dependencies.sh" \
  "$dispatch_root/project" "$work/merged.tsv"
[[ $(wc -l < "$work/merged.tsv") -eq 3 ]]

cat > "$dispatch_root/scripts/resolve-gradle-dependencies.sh" <<'GRADLE_CONFLICT'
#!/usr/bin/env bash
printf 'shared/src/main/java\t/cache/different.jar\n' > "$2"
GRADLE_CONFLICT
chmod +x "$dispatch_root/scripts/resolve-gradle-dependencies.sh"
set +e
"$dispatch_root/scripts/resolve-java-dependencies.sh" \
  "$dispatch_root/project" "$work/conflict.tsv" >/dev/null 2>"$work/conflict.err"
status=$?
set -e
[[ $status -eq 70 ]]
[[ ! -s "$work/conflict.tsv" ]]
grep -q 'resolvers disagree' "$work/conflict.err"

printf 'LOCAL_JAVA_DEPENDENCY_RESOLVERS_OK\n'
