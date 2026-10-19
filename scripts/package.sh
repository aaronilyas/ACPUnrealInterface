#!/usr/bin/env bash
# Build a platform app image (or another jpackage type) for UnrealInterface.
# The skills bundle is inside the application jar. Agent presets still run npx from PATH.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

if ! command -v mvn >/dev/null 2>&1; then
  echo "mvn is not on PATH." >&2
  exit 1
fi
if ! command -v jpackage >/dev/null 2>&1; then
  echo "jpackage is not on PATH. Install a JDK 17 or newer." >&2
  exit 1
fi

version=$(sed -n 's/[[:space:]]*<version>\([^<]*\)<\/version>.*/\1/p' pom.xml | head -n 1)
if [[ -z "${version}" ]]; then
  echo "Could not read the project version from pom.xml." >&2
  exit 1
fi

javafx_classifier() {
  local os arch
  os=$(uname -s)
  arch=$(uname -m)
  case "${os}:${arch}" in
    Linux:x86_64) echo linux ;;
    Linux:aarch64 | Linux:arm64) echo linux-aarch64 ;;
    Darwin:x86_64) echo mac ;;
    Darwin:arm64 | Darwin:aarch64) echo mac-aarch64 ;;
    MINGW*:x86_64 | MSYS*:x86_64 | CYGWIN*:x86_64) echo win ;;
    *)
      echo "No JavaFX classifier for ${os} ${arch}." >&2
      exit 1
      ;;
  esac
}

classifier=$(javafx_classifier)
type=${JPACKAGE_TYPE:-app-image}
name=UnrealInterface
main_jar="unreal-interface-${version}.jar"

rm -rf target/jpackage target/dist
mvn -B -Pjpackage package "$@"

if [[ ! -f "target/jpackage/input/${main_jar}" ]]; then
  echo "Missing target/jpackage/input/${main_jar}." >&2
  exit 1
fi

mkdir -p target/jpackage/javafx-mods
mods=0
for jar in target/jpackage/javafx-all/javafx-*-"${classifier}".jar; do
  if [[ -f "${jar}" ]]; then
    cp "${jar}" target/jpackage/javafx-mods/
    mods=$((mods + 1))
  fi
done
if (( mods < 3 )); then
  echo "Expected JavaFX base, graphics, and controls for classifier ${classifier}." >&2
  ls -1 target/jpackage/javafx-all >&2 || true
  exit 1
fi

controls=""
for jar in target/jpackage/javafx-mods/javafx-controls-*-"${classifier}".jar; do
  if [[ -f "${jar}" ]]; then
    controls=${jar}
  fi
done
if [[ -z "${controls}" ]]; then
  echo "JavaFX controls jar for classifier ${classifier} was not copied." >&2
  exit 1
fi
module_desc=$(jar --describe-module --file "${controls}")
case "${module_desc}" in
  *javafx.controls*) ;;
  *)
    echo "JavaFX controls jar is not the javafx.controls module." >&2
    exit 1
    ;;
esac

listing=$(jar tf "target/jpackage/input/${main_jar}")
case "${listing}" in
  *skills-bundle/unreal-router/SKILL.md*) ;;
  *)
    echo "The application jar is missing the skills bundle." >&2
    exit 1
    ;;
esac
case "${listing}" in
  *com/unrealinterface/acp/AgentPresets.class*) ;;
  *)
    echo "The application jar is missing the agent presets." >&2
    exit 1
    ;;
esac

mkdir -p target/dist
jpackage \
  --type "${type}" \
  --dest target/dist \
  --name "${name}" \
  --app-version "${version}" \
  --vendor UnrealInterface \
  --description "ACP client that installs Unreal skills and configures Unreal MCP" \
  --input target/jpackage/input \
  --main-jar "${main_jar}" \
  --main-class com.unrealinterface.UnrealInterfaceApp \
  --module-path target/jpackage/javafx-mods \
  --add-modules javafx.controls \
  --java-options -Dfile.encoding=UTF-8

case "$(uname -s)" in
  Darwin)
    echo "Packaged ${root}/target/dist/${name}.app"
    ;;
  *)
    echo "Packaged ${root}/target/dist/${name}"
    ;;
esac
