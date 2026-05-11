#!/bin/bash
# Host-side driver for emulator-based instrumentation tests.
#
# Spins up four Python fake-upstream proxies on the host:
#
#   :1080  SOCKS5,   no auth
#   :1081  SOCKS5,   user=$AUTH_USER / pass=$AUTH_PASS
#   :8081  HTTP CONNECT, no auth
#   :8082  HTTP CONNECT, user=$AUTH_USER / pass=$AUTH_PASS
#
# Then runs `connectedAndroidTest`, which from inside the emulator reaches the
# host loopback as 10.0.2.2 and exercises both protocols × {no-auth, auth-ok,
# auth-wrong-creds} via:
#
#   HostSocks5ProxyIntegrationTest
#   HostHttpConnectProxyIntegrationTest
#
# Invoked from the CI workflow (or locally with an already-running emulator).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

AUTH_USER="${AUTH_USER:-alice}"
AUTH_PASS="${AUTH_PASS:-s3cret}"

PIDS=()
cleanup() {
    set +e
    for pid in "${PIDS[@]:-}"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null
        fi
    done
}
trap cleanup EXIT

start_bg() {
    local name="$1"; shift
    echo "=== Starting $name: $* ==="
    "$@" &
    PIDS+=("$!")
}

echo "=== Installing APK ==="
adb install -r "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

echo "=== Verifying app installation ==="
adb shell pm list packages | grep org.proxydroid

echo "=== Booting fake upstream proxies on host ==="
start_bg "SOCKS5 no-auth :1080" \
    python3 "$SCRIPT_DIR/socks5_test_server.py" --port 1080 --quiet
start_bg "SOCKS5 user/pass :1081" \
    python3 "$SCRIPT_DIR/socks5_test_server.py" --port 1081 --auth "$AUTH_USER:$AUTH_PASS" --quiet
start_bg "HTTP CONNECT no-auth :8081" \
    python3 "$SCRIPT_DIR/http_connect_test_server.py" --port 8081 --quiet
start_bg "HTTP CONNECT user/pass :8082" \
    python3 "$SCRIPT_DIR/http_connect_test_server.py" --port 8082 --auth "$AUTH_USER:$AUTH_PASS" --quiet

# Give listeners a moment to bind.
sleep 2
for port in 1080 1081 8081 8082; do
    if ! (echo > "/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
        echo "FAIL: nothing listening on 127.0.0.1:$port"
        exit 1
    fi
done
echo "=== All four proxies are listening ==="

echo "=== Running connectedAndroidTest ==="
cd "$PROJECT_DIR"
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.socksHost=10.0.2.2 \
    -Pandroid.testInstrumentationRunnerArguments.socksPort=1080 \
    -Pandroid.testInstrumentationRunnerArguments.socksAuthPort=1081 \
    -Pandroid.testInstrumentationRunnerArguments.socksAuthUser="$AUTH_USER" \
    -Pandroid.testInstrumentationRunnerArguments.socksAuthPass="$AUTH_PASS" \
    -Pandroid.testInstrumentationRunnerArguments.httpProxyHost=10.0.2.2 \
    -Pandroid.testInstrumentationRunnerArguments.httpProxyPort=8081 \
    -Pandroid.testInstrumentationRunnerArguments.httpProxyAuthPort=8082 \
    -Pandroid.testInstrumentationRunnerArguments.httpProxyAuthUser="$AUTH_USER" \
    -Pandroid.testInstrumentationRunnerArguments.httpProxyAuthPass="$AUTH_PASS"

echo "=== Emulator instrumentation tests passed ==="
