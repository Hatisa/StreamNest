#!/usr/bin/env bash
# Installs the R8-shrunk release APK on the booted emulator and checks that it
# stays up. This lives in a file on purpose: android-emulator-runner runs every
# line of `script:` in its own shell, so a variable assigned on one line is gone
# by the next — the install used to silently receive an empty APK path.
set -euo pipefail

apk_dir="${GITHUB_WORKSPACE:-.}/apk"
apk="$(find "$apk_dir" -name '*.apk' -type f 2>/dev/null | head -n 1 || true)"
if [ -z "$apk" ]; then
  echo "::error::no APK found under $apk_dir"
  ls -laR "$apk_dir" || true
  exit 1
fi
echo "Installing $apk"
adb install -r "$apk"

adb shell am start -W -n com.streamnest.tv/.MainActivity
sleep 25

pid="$(adb shell pidof com.streamnest.tv | tr -d '\r' || true)"
if [ -z "$pid" ]; then
  echo "::error::release APK did not stay running (possible R8 keep-rule problem)"
  adb logcat -d | tail -n 300
  exit 1
fi

if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  echo "::error::release APK crashed with a fatal exception"
  adb logcat -d | tail -n 300
  exit 1
fi

echo "smoke test passed, pid=$pid"
