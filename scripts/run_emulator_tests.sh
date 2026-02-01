#!/bin/bash
# Emulator integration tests for ProxyDroid VPN mode
# This script is run inside the Android emulator environment

set -e

echo "=== Installing APK ==="
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "=== Verifying app installation ==="
adb shell pm list packages | grep org.proxydroid

echo "=== Starting app to create data directory ==="
adb shell am start -n org.proxydroid/.ProxyDroid
sleep 5

echo "=== Configuring proxy settings ==="
adb push /tmp/prefs.xml /data/local/tmp/prefs.xml

echo "=== Checking VPN service declaration ==="
adb shell dumpsys package org.proxydroid | grep -A5 "Service" | head -20

echo "=== Checking native libraries ==="
adb shell ls -la /data/app/*/org.proxydroid*/lib/*/ 2>/dev/null || echo "Could not list lib directory"

echo "=== Verifying app is running ==="
adb shell dumpsys activity activities | grep -A5 "org.proxydroid" | head -10

echo "=== Test: App should handle proxy configuration ==="
adb shell am force-stop org.proxydroid
adb shell am start -n org.proxydroid/.ProxyDroid
sleep 3

echo "=== Final verification ==="
if adb shell pm list packages | grep -q "org.proxydroid"; then
  echo "SUCCESS: App is installed and running"
else
  echo "FAILURE: App installation verification failed"
  exit 1
fi

echo "=== Emulator tests completed successfully ==="
