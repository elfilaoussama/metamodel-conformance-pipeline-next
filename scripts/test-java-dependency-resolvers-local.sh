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
for argument in "$@"; do
  case "$argument" in
    type=bind,src=*,dst=/workspace/out)
      out_mount="${argument#type=bind,src=}"
      out_mount="${out_mount%%,dst=/workspace/out}" ;;
  esac
done
test -n "$out_mount"
mkdir -p "$out_mount/result" "$out_mount/gradle/cache"
printf jar > "$out_mount/gradle/cache/main.jar"
printf jar > "$out_mount/gradle/cache/test.jar"
printf 'src/main/java\t/workspace/out/gradle/cache/main.jar\nsrc/test/java\t/workspace/out/gradle/cache/main.jar\nsrc/test/java\t/workspace/out/gradle/cache/test.jar\n' \
  > "$out_mount/result/classpath.tsv"
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
