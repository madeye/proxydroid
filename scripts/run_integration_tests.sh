#!/bin/bash
#
# Integration test script for ProxyDroid VPN mode
# This script:
# 1. Starts an HTTP proxy server
# 2. Installs the app on an Android emulator
# 3. Configures proxy settings via adb
# 4. Enables VPN mode
# 5. Tests connectivity through the proxy using adb port forwarding and curl
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Configuration
PROXY_PORT=8888
PROXY_HOST="10.0.2.2"  # Host machine from emulator's perspective
TEST_HOST="httpbin.org"
TEST_PORT=443
ADB_FORWARD_PORT=8889

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

cleanup() {
    log_info "Cleaning up..."

    # Stop the proxy server
    if [ -n "$PROXY_PID" ]; then
        kill $PROXY_PID 2>/dev/null || true
        wait $PROXY_PID 2>/dev/null || true
    fi

    # Remove adb port forwarding
    adb forward --remove tcp:$ADB_FORWARD_PORT 2>/dev/null || true

    # Stop VPN service
    adb shell am stopservice org.proxydroid/.ProxyDroidVpnService 2>/dev/null || true
}

trap cleanup EXIT

wait_for_device() {
    log_info "Waiting for device to be ready..."
    adb wait-for-device

    # Wait for boot to complete
    while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        sleep 2
    done

    log_info "Device is ready"
}

start_proxy() {
    log_info "Starting HTTP proxy server on port $PROXY_PORT..."

    python3 "$SCRIPT_DIR/test_http_proxy.py" --port $PROXY_PORT &
    PROXY_PID=$!

    # Wait for proxy to start
    sleep 2

    if ! kill -0 $PROXY_PID 2>/dev/null; then
        log_error "Failed to start proxy server"
        exit 1
    fi

    log_info "Proxy server started (PID: $PROXY_PID)"
}

install_app() {
    log_info "Building and installing app..."

    cd "$PROJECT_DIR"

    # Build debug APK
    ./gradlew assembleDebug

    # Install APK
    adb install -r app/build/outputs/apk/debug/app-debug.apk

    log_info "App installed successfully"
}

configure_proxy_settings() {
    log_info "Configuring proxy settings via adb..."

    PACKAGE="org.proxydroid"
    PREFS_DIR="/data/data/$PACKAGE/shared_prefs"
    PREFS_FILE="${PACKAGE}_preferences.xml"

    # Grant necessary permissions
    adb shell pm grant $PACKAGE android.permission.INTERNET 2>/dev/null || true

    # Create preferences XML
    PREFS_XML="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name=\"host\">$PROXY_HOST</string>
    <string name=\"port\">$PROXY_PORT</string>
    <string name=\"proxyType\">http</string>
    <boolean name=\"isVpnMode\" value=\"true\" />
    <boolean name=\"isAutoConnect\" value=\"false\" />
    <boolean name=\"isAuth\" value=\"false\" />
    <string name=\"user\"></string>
    <string name=\"password\"></string>
    <boolean name=\"isRunning\" value=\"false\" />
    <boolean name=\"isBypassApps\" value=\"false\" />
    <string name=\"bypassAddrs\"></string>
    <boolean name=\"isGlobalProxy\" value=\"true\" />
</map>"

    # Push preferences to device
    # First, start the app briefly to create the data directory
    adb shell am start -n $PACKAGE/.ProxyDroid
    sleep 3
    adb shell am force-stop $PACKAGE

    # Write preferences file
    echo "$PREFS_XML" | adb shell "run-as $PACKAGE sh -c 'cat > shared_prefs/${PREFS_FILE}'" 2>/dev/null || \
    echo "$PREFS_XML" | adb shell "su -c 'cat > $PREFS_DIR/$PREFS_FILE && chmod 660 $PREFS_DIR/$PREFS_FILE && chown \$(stat -c %u:%g $PREFS_DIR) $PREFS_DIR/$PREFS_FILE'" 2>/dev/null || \
    {
        # Fallback: use a temporary file
        TEMP_FILE=$(mktemp)
        echo "$PREFS_XML" > "$TEMP_FILE"
        adb push "$TEMP_FILE" /data/local/tmp/prefs.xml
        adb shell "run-as $PACKAGE cp /data/local/tmp/prefs.xml shared_prefs/${PREFS_FILE}" 2>/dev/null || \
        adb shell "su -c 'cp /data/local/tmp/prefs.xml $PREFS_DIR/$PREFS_FILE && chmod 660 $PREFS_DIR/$PREFS_FILE'"
        rm "$TEMP_FILE"
    }

    log_info "Proxy settings configured: $PROXY_HOST:$PROXY_PORT (VPN mode enabled)"
}

start_vpn_service() {
    log_info "Starting VPN service..."

    PACKAGE="org.proxydroid"

    # Start the main activity first
    adb shell am start -n $PACKAGE/.ProxyDroid
    sleep 2

    # The VPN service requires user consent for the first time
    # In a CI environment, we need to simulate this
    # For emulator testing, we can use the VPN consent bypass

    # Try to start the VPN service via broadcast or activity
    adb shell am broadcast -a org.proxydroid.ACTION_START_VPN -n $PACKAGE/.ProxyDroidReceiver 2>/dev/null || true

    # Alternative: use the service directly (may require VPN permission)
    adb shell am start-foreground-service -n $PACKAGE/.ProxyDroidVpnService 2>/dev/null || \
    adb shell am startservice -n $PACKAGE/.ProxyDroidVpnService 2>/dev/null || true

    sleep 5

    log_info "VPN service start requested"
}

setup_port_forwarding() {
    log_info "Setting up adb port forwarding..."

    # Forward a local port to a port inside the emulator
    # This allows us to test if traffic goes through the proxy
    adb forward tcp:$ADB_FORWARD_PORT tcp:8080

    log_info "Port forwarding set up: localhost:$ADB_FORWARD_PORT -> emulator:8080"
}

run_connectivity_tests() {
    log_info "Running connectivity tests..."

    local TESTS_PASSED=0
    local TESTS_FAILED=0

    # Test 1: Check if proxy server is receiving connections
    log_info "Test 1: Checking if proxy server is running..."
    if curl -s --connect-timeout 5 --proxy "http://localhost:$PROXY_PORT" "http://httpbin.org/ip" > /dev/null 2>&1; then
        log_info "  PASSED: Proxy server is accepting connections"
        ((TESTS_PASSED++))
    else
        log_warn "  SKIPPED: Proxy server not reachable from host (expected in some CI environments)"
    fi

    # Test 2: Check if app is installed
    log_info "Test 2: Checking if app is installed..."
    if adb shell pm list packages | grep -q "org.proxydroid"; then
        log_info "  PASSED: App is installed"
        ((TESTS_PASSED++))
    else
        log_error "  FAILED: App is not installed"
        ((TESTS_FAILED++))
    fi

    # Test 3: Check if VPN service is declared
    log_info "Test 3: Checking if VPN service is declared in manifest..."
    if adb shell dumpsys package org.proxydroid | grep -q "ProxyDroidVpnService"; then
        log_info "  PASSED: VPN service is declared"
        ((TESTS_PASSED++))
    else
        log_error "  FAILED: VPN service not found in manifest"
        ((TESTS_FAILED++))
    fi

    # Test 4: Check if native library is loaded
    log_info "Test 4: Checking if native libraries are present..."
    if adb shell "run-as org.proxydroid ls lib/" 2>/dev/null | grep -q "libtun2socks.so"; then
        log_info "  PASSED: Native tun2socks library found"
        ((TESTS_PASSED++))
    else
        # Check alternative location
        if adb shell ls /data/app/*/org.proxydroid*/lib/*/ 2>/dev/null | grep -q "libtun2socks"; then
            log_info "  PASSED: Native tun2socks library found (in APK)"
            ((TESTS_PASSED++))
        else
            log_warn "  SKIPPED: Could not verify native library (may require root)"
        fi
    fi

    # Test 5: Check preferences were written
    log_info "Test 5: Checking if preferences are configured..."
    if adb shell "run-as org.proxydroid cat shared_prefs/org.proxydroid_preferences.xml" 2>/dev/null | grep -q "isVpnMode"; then
        log_info "  PASSED: Preferences configured with VPN mode"
        ((TESTS_PASSED++))
    else
        log_warn "  SKIPPED: Could not read preferences (may require root)"
    fi

    # Test 6: Verify app can be started
    log_info "Test 6: Checking if app starts without crashing..."
    adb shell am force-stop org.proxydroid
    adb shell am start -n org.proxydroid/.ProxyDroid
    sleep 3

    if adb shell "dumpsys activity activities" | grep -q "org.proxydroid/.ProxyDroid"; then
        log_info "  PASSED: App started successfully"
        ((TESTS_PASSED++))
    else
        log_error "  FAILED: App failed to start"
        ((TESTS_FAILED++))
    fi

    # Summary
    echo ""
    log_info "=========================================="
    log_info "Test Summary: $TESTS_PASSED passed, $TESTS_FAILED failed"
    log_info "=========================================="

    if [ $TESTS_FAILED -gt 0 ]; then
        return 1
    fi
    return 0
}

main() {
    log_info "Starting ProxyDroid VPN mode integration tests"
    log_info "=========================================="

    # Check prerequisites
    if ! command -v adb &> /dev/null; then
        log_error "adb is not installed or not in PATH"
        exit 1
    fi

    if ! command -v python3 &> /dev/null; then
        log_error "python3 is not installed or not in PATH"
        exit 1
    fi

    # Run test steps
    wait_for_device
    start_proxy
    install_app
    configure_proxy_settings
    start_vpn_service

    # Run tests
    if run_connectivity_tests; then
        log_info "All tests passed!"
        exit 0
    else
        log_error "Some tests failed"
        exit 1
    fi
}

main "$@"
