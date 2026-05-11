#!/usr/bin/env bash
#
# Local emulator instrumentation runner that bypasses AGP's Unified Test
# Platform (UTP).
#
# Why: on AGP 8.1.2 + JDK 17, `./gradlew connectedDebugAndroidTest` fails with
#
#   java.lang.IllegalAccessError: class com.google.protobuf.GeneratedMessageV3
#     tried to access method
#     'boolean com.google.protobuf.CodedInputStream.shouldDiscardUnknownFields()'
#     (com.google.protobuf.GeneratedMessageV3 is in unnamed module of loader
#      java.net.URLClassLoader @...;
#      com.google.protobuf.CodedInputStream is in unnamed module of loader 'app')
#
# Root cause: UTP host plugins live in a separate URLClassLoader, ddmlib lives
# in Gradle's "app" classloader, and each pulls its own protobuf-java version.
# AGP 8.1.2 has no `useUnifiedTestPlatform=false` opt-out (removed in AGP 8.x),
# and upgrading AGP retriggers the rust-android-gradle 0.9.6 jniLibs dup bug.
#
# This script builds the APKs via gradle but launches instrumentation through
# `adb shell am instrument -w`, which avoids UTP and ddmlib's protobuf parsing
# entirely. Same code path CI's android-emulator-runner action uses.
#
# Prereqs: a running emulator (or device) reachable via adb, and Python 3 on
# the host for the four fake upstream proxies. The script auto-picks free TCP
# ports if the defaults (1080/1081/8081/8082) are taken locally (e.g. by a
# sslocal already listening on 8081).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

PACKAGE="org.proxydroid"
TEST_PACKAGE="org.proxydroid.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"

AUTH_USER="${AUTH_USER:-alice}"
AUTH_PASS="${AUTH_PASS:-s3cret}"

# Defaults match the CI rig; the picker below will hop to a free port if any
# is already bound on this host.
DEFAULT_SOCKS_NOAUTH=1080
DEFAULT_SOCKS_AUTH=1081
DEFAULT_HTTP_NOAUTH=8081
DEFAULT_HTTP_AUTH=8082

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------

log() { printf '\033[1;34m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '\033[1;31m[FATAL]\033[0m %s\n' "$*" >&2; exit 1; }

port_free() { ! lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

pick_port() {
    # Walk from $1 upward (stride 100 to stay tidy) until a free port shows up.
    local p="$1"
    for _ in $(seq 1 64); do
        if port_free "$p"; then echo "$p"; return; fi
        p=$((p + 100))
    done
    die "no free port near $1"
}

PIDS=()
cleanup() {
    set +e
    log "cleaning up"
    for pid in "${PIDS[@]:-}"; do
        [ -n "$pid" ] && kill "$pid" 2>/dev/null
    done
    [ -n "${TMPDIR_RUN:-}" ] && rm -rf "$TMPDIR_RUN"
}
trap cleanup EXIT

start_bg() {
    local name="$1"; shift
    "$@" >/dev/null 2>&1 &
    local pid=$!
    PIDS+=("$pid")
    log "started $name (pid $pid): $*"
}

require_emulator() {
    local serial
    serial=$(adb devices | awk '/^emulator-[0-9]+\tdevice/{print $1; exit}')
    if [ -z "$serial" ]; then
        die "no emulator attached; boot one first (e.g. \$ANDROID_HOME/emulator/emulator -avd <avd>)"
    fi
    if [ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
        die "$serial is not finished booting"
    fi
    echo "$serial"
}

# -----------------------------------------------------------------------------
# Build & install
# -----------------------------------------------------------------------------

build_apks() {
    log "building debug + androidTest APKs"
    (
        cd "$PROJECT_DIR"
        ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -q
    )
}

install_apks() {
    local serial="$1"
    local app_apk="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    local test_apk="$PROJECT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    [ -f "$app_apk"  ] || die "missing $app_apk"
    [ -f "$test_apk" ] || die "missing $test_apk"
    log "uninstalling any prior $PACKAGE / $TEST_PACKAGE"
    adb -s "$serial" uninstall "$PACKAGE"      >/dev/null 2>&1 || true
    adb -s "$serial" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
    log "installing $app_apk"
    adb -s "$serial" install -r "$app_apk"  >/dev/null
    log "installing $test_apk"
    adb -s "$serial" install -r "$test_apk" >/dev/null
}

# -----------------------------------------------------------------------------
# Run
# -----------------------------------------------------------------------------

run_instrumentation() {
    local serial="$1"
    local socks_noauth="$2" socks_auth="$3" http_noauth="$4" http_auth="$5"

    TMPDIR_RUN=$(mktemp -d)
    local out="$TMPDIR_RUN/inst.out"

    log "am instrument -> $out"
    # Pass instrumentation args via -e KEY VALUE pairs.
    adb -s "$serial" shell am instrument -w -r \
        -e socksHost 10.0.2.2 \
        -e socksPort "$socks_noauth" \
        -e socksAuthPort "$socks_auth" \
        -e socksAuthUser "$AUTH_USER" \
        -e socksAuthPass "$AUTH_PASS" \
        -e httpProxyHost 10.0.2.2 \
        -e httpProxyPort "$http_noauth" \
        -e httpProxyAuthPort "$http_auth" \
        -e httpProxyAuthUser "$AUTH_USER" \
        -e httpProxyAuthPass "$AUTH_PASS" \
        "$TEST_PACKAGE/$RUNNER" | tee "$out"

    # `am instrument -w -r` prints a flat key/value stream. Tests passed iff
    # the final INSTRUMENTATION_CODE is -1 and no INSTRUMENTATION_STATUS_CODE
    # is -2 (failure).
    if grep -q '^INSTRUMENTATION_STATUS_CODE: -2' "$out"; then
        echo
        log "FAIL: at least one test reported failure"
        grep -E '^INSTRUMENTATION_STATUS: (test|class|stack|stream)=' "$out" | sed 's/^/  /'
        return 1
    fi
    if grep -q '^INSTRUMENTATION_CODE: -1' "$out"; then
        local n
        n=$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0' "$out" || true)
        log "PASS: $n tests"
        return 0
    fi
    log "FAIL: instrumentation did not report success"
    return 1
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

main() {
    : "${ANDROID_HOME:?ANDROID_HOME must be set}"
    : "${JAVA_HOME:?JAVA_HOME must be set}"

    local serial; serial=$(require_emulator)
    log "target emulator: $serial"

    local socks_noauth socks_auth http_noauth http_auth
    socks_noauth=$(pick_port "$DEFAULT_SOCKS_NOAUTH")
    socks_auth=$(  pick_port "$DEFAULT_SOCKS_AUTH"  )
    http_noauth=$( pick_port "$DEFAULT_HTTP_NOAUTH" )
    http_auth=$(   pick_port "$DEFAULT_HTTP_AUTH"   )
    log "ports: socks=$socks_noauth/$socks_auth http=$http_noauth/$http_auth"

    start_bg "socks5-noauth" \
        python3 "$SCRIPT_DIR/socks5_test_server.py" --port "$socks_noauth" --quiet
    start_bg "socks5-auth" \
        python3 "$SCRIPT_DIR/socks5_test_server.py" --port "$socks_auth" --auth "$AUTH_USER:$AUTH_PASS" --quiet
    start_bg "http-connect-noauth" \
        python3 "$SCRIPT_DIR/http_connect_test_server.py" --port "$http_noauth" --quiet
    start_bg "http-connect-auth" \
        python3 "$SCRIPT_DIR/http_connect_test_server.py" --port "$http_auth" --auth "$AUTH_USER:$AUTH_PASS" --quiet

    # Give listeners a moment to bind.
    sleep 2
    for p in "$socks_noauth" "$socks_auth" "$http_noauth" "$http_auth"; do
        if port_free "$p"; then die "nothing listening on 127.0.0.1:$p"; fi
    done
    log "all four upstreams listening"

    build_apks
    install_apks "$serial"

    if run_instrumentation "$serial" \
            "$socks_noauth" "$socks_auth" "$http_noauth" "$http_auth"; then
        log "DONE: emulator instrumentation green"
        exit 0
    else
        log "DONE: emulator instrumentation red"
        exit 1
    fi
}

main "$@"
