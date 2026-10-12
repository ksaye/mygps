#!/usr/bin/env bash
# Builds the release APK -> dist/MyGPS-<version>.apk
#
# Signing (optional; without it you get an unsigned APK):
#   MYGPS_KEYSTORE=/path/to/release.keystore MYGPS_KEYSTORE_PASSWORD=... [MYGPS_KEY_ALIAS=mygps] ./build-release.sh
# Needs JDK 17 and the Android SDK (ANDROID_HOME, or sdk.dir in local.properties).
set -euo pipefail
cd "$(dirname "$0")"

ARGS=()
if [ -n "${MYGPS_KEYSTORE:-}" ]; then
  ARGS+=(-PmygpsKeystore="$MYGPS_KEYSTORE"
         -PmygpsKeystorePassword="${MYGPS_KEYSTORE_PASSWORD:?set MYGPS_KEYSTORE_PASSWORD}"
         -PmygpsKeyPassword="${MYGPS_KEY_PASSWORD:-$MYGPS_KEYSTORE_PASSWORD}"
         -PmygpsKeyAlias="${MYGPS_KEY_ALIAS:-mygps}")
fi

VER=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
./gradlew -q clean assembleRelease "${ARGS[@]}"

mkdir -p dist
SRC=app/build/outputs/apk/release/app-release.apk
[ -f "$SRC" ] || SRC=app/build/outputs/apk/release/app-release-unsigned.apk
cp "$SRC" "dist/MyGPS-$VER.apk"
echo "built dist/MyGPS-$VER.apk"
