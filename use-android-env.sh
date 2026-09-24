#!/usr/bin/env bash
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$PROJECT_ROOT/.tools/android-sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$PROJECT_ROOT/.tools/gradle-8.13/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
echo "ANDROID_HOME=$ANDROID_HOME"
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  echo "SDK not installed yet. Run ./setup-android-env.sh first."
fi
