#!/usr/bin/env bash
# Dogo Phone Launcher — dev helper.
#
# Toolchain is pinned via ../.mise.toml (JDK 21 / ANDROID_HOME), so simply
# activating mise gives us a working `java` + `sdk.dir`.
#
# Usage:
#   ./dev.sh build          # assemble the dogo-channel debug APK
#   ./dev.sh build github   # assemble the github-channel debug APK
#   ./dev.sh test           # run root-module unit tests
#   ./dev.sh test <Class>   # run a single test class/method
#   ./dev.sh spotless       # formatting gate (same check CI runs)
#   ./dev.sh setup          # one-time: init submodules + local.properties
#   ./dev.sh shell          # gradle shell with JDK21 + ANDROID_HOME set
set -euo pipefail

REPO="${DOGO_REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
PROJ_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The app module is the *root* Gradle project, variant =
# <app:lawn><recents:WithQuickstep><channel><buildType:Debug>
# `dogo` is our own channel (applicationId app.dogo.launcher); `github` builds
# the upstream-flavoured variant and is handy for side-by-side comparison.
CHANNEL="${DOGO_CHANNEL:-dogo}"
VARIANT="lawnWithQuickstep${CHANNEL^}Debug"

cd "$REPO"

# Activate mise-pinned JDK 21 + ANDROID_HOME if available.
if command -v mise >/dev/null 2>&1; then
  eval "$(mise activate bash --shims 2>/dev/null || true)"
  if [ -z "${JAVA_HOME:-}" ] || [ -z "${ANDROID_HOME:-}" ]; then
    JAVA_HOME="$(mise where java 2>/dev/null || true)"
    ANDROID_HOME="$PROJ_ROOT/.android-sdk"
    ANDROID_SDK_ROOT="$ANDROID_HOME"
    export JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT
  fi
fi
export PATH="${JAVA_HOME:-}/bin:$PATH"

cmd="${1:-build}"; shift || true

# Allow `./dev.sh build github` to target a different channel.
case "$cmd" in
  build|test)
    case "${1:-}" in
      github|nightly|play|dogo)
        CHANNEL="$1"; VARIANT="lawnWithQuickstep${CHANNEL^}Debug"; shift ;;
    esac
    ;;
esac

case "$cmd" in
  setup)
    git submodule update --init --recursive
    # local.properties is gitignored in the fork — safe to write
    [ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
    echo "sdk.dir -> $ANDROID_HOME"
    ;;
  build)
    ./gradlew "assemble${VARIANT^}" "$@"
    ;;
  test)
    if [ $# -gt 0 ]; then
      ./gradlew ":test${VARIANT^}UnitTest" --tests "$1" "$@"
    else
      ./gradlew ":test${VARIANT^}UnitTest" "$@"
    fi
    ;;
  spotless)
    ./gradlew spotlessCheck "$@"
    ;;
  shell)
    ./gradlew "$@"
    ;;
  apk)
    find build/outputs -name '*.apk' -exec ls -lh {} \;
    ;;
  *)
    echo "unknown command: $cmd" >&2; exit 2 ;;
esac