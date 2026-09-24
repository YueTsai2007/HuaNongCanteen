#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$PROJECT_ROOT/.tools"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$TOOLS_DIR/android-sdk}}"
GRADLE_VERSION="8.13"
GRADLE_DIR="$TOOLS_DIR/gradle-$GRADLE_VERSION"
GRADLE_SHA256="20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
CMDLINE_TOOLS_VERSION="15859902"
CMDLINE_TOOLS_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
DOWNLOAD_DIR="$TOOLS_DIR/downloads"

if ! command -v java >/dev/null 2>&1; then
  echo "JDK 17 is required. Install it, then rerun this script." >&2
  exit 1
fi
JAVA_MAJOR="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
if [[ "$JAVA_MAJOR" != "17" ]]; then
  echo "This project is configured for JDK 17; found ${JAVA_MAJOR:-unknown}." >&2
  exit 1
fi
for TOOL in curl unzip sha256sum; do
  command -v "$TOOL" >/dev/null 2>&1 || { echo "Missing required utility: $TOOL" >&2; exit 1; }
done

mkdir -p "$DOWNLOAD_DIR" "$SDK_ROOT/cmdline-tools"

if [[ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
  ARCHIVE="$DOWNLOAD_DIR/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  curl -fL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" -o "$ARCHIVE"
  echo "$CMDLINE_TOOLS_SHA256  $ARCHIVE" | sha256sum --check --status || {
    echo "Android command-line tools checksum mismatch." >&2
    exit 1
  }
  TEMP_TOOLS="$DOWNLOAD_DIR/cmdline-tools-unpacked"
  mkdir -p "$TEMP_TOOLS"
  unzip -q -o "$ARCHIVE" -d "$TEMP_TOOLS"
  [[ -d "$TEMP_TOOLS/cmdline-tools" ]] || { echo "Unexpected Android tools archive layout." >&2; exit 1; }
  mkdir -p "$SDK_ROOT/cmdline-tools/latest"
  cp -R "$TEMP_TOOLS/cmdline-tools/." "$SDK_ROOT/cmdline-tools/latest/"
fi

if [[ ! -x "$GRADLE_DIR/bin/gradle" ]]; then
  GRADLE_ARCHIVE="$DOWNLOAD_DIR/gradle-${GRADLE_VERSION}-bin.zip"
  if ! curl -fL --connect-timeout 20 --max-time 300 "https://mirrors.cloud.tencent.com/gradle/gradle-${GRADLE_VERSION}-bin.zip" -o "$GRADLE_ARCHIVE"; then
    curl -fL --connect-timeout 20 --max-time 300 "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$GRADLE_ARCHIVE"
  fi
  echo "$GRADLE_SHA256  $GRADLE_ARCHIVE" | sha256sum --check --status || {
    echo "Gradle distribution checksum mismatch." >&2
    exit 1
  }
  unzip -q -o "$GRADLE_ARCHIVE" -d "$TOOLS_DIR"
fi

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
echo "Accept the Android SDK license terms when prompted."
"$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses
"$SDKMANAGER" --sdk_root="$SDK_ROOT" "platform-tools" "platforms;android-36" "build-tools;36.0.0"

printf 'sdk.dir=%s\n' "$SDK_ROOT" > "$PROJECT_ROOT/local.properties"
if [[ ! -f "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.jar" ]]; then
  WRAPPER_SEED="$DOWNLOAD_DIR/wrapper-seed"
  mkdir -p "$WRAPPER_SEED"
  touch "$WRAPPER_SEED/build.gradle"
  GRADLE_ARCHIVE="$DOWNLOAD_DIR/gradle-${GRADLE_VERSION}-bin.zip"
  "$GRADLE_DIR/bin/gradle" -p "$WRAPPER_SEED" wrapper \
    --gradle-version "$GRADLE_VERSION" \
    --gradle-distribution-url "file://$GRADLE_ARCHIVE"
  cp "$WRAPPER_SEED/gradlew" "$PROJECT_ROOT/gradlew"
  cp "$WRAPPER_SEED/gradlew.bat" "$PROJECT_ROOT/gradlew.bat"
  cp "$WRAPPER_SEED/gradle/wrapper/gradle-wrapper.jar" "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.jar"
  chmod +x "$PROJECT_ROOT/gradlew"
fi

cat > "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.properties" <<EOF
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\\://mirrors.cloud.tencent.com/gradle/gradle-${GRADLE_VERSION}-bin.zip
distributionSha256Sum=${GRADLE_SHA256}
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF

cat <<EOF

Android build environment is ready.
SDK: $SDK_ROOT
Gradle: $GRADLE_DIR
Build APK with: $GRADLE_DIR/bin/gradle -p "$PROJECT_ROOT" :app:assembleDebug
EOF
