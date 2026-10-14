#!/bin/bash
set -euo pipefail
BRIDGE_SOURCE="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$BRIDGE_SDK" ]; then
    echo 'Set ANDROID_SDK_ROOT to the Android SDK directory.' >&2; exit 1
fi
BRIDGE_TOOLS="$BRIDGE_SDK/build-tools/36.0.0"
BRIDGE_BUILD="$BRIDGE_SOURCE/build"
BRIDGE_JAR="$BRIDGE_SDK/platforms/android-35/android.jar"
BRIDGE_KEY="${BRIDGE_SIGNING_KEY:-$BRIDGE_SOURCE/local-signing.jks}"
BRIDGE_ALIAS="${BRIDGE_KEY_ALIAS:-bridge}"
if [ -n "${JAVA_HOME:-}" ]; then
    BRIDGE_JAVAC="$JAVA_HOME/bin/javac"
else
    BRIDGE_JAVAC=javac
fi
if [ ! -f "$BRIDGE_KEY" ]; then
    echo 'Set BRIDGE_SIGNING_KEY to an existing signing keystore.' >&2; exit 1
fi
: "${BRIDGE_STORE_PASSWORD:?Set BRIDGE_STORE_PASSWORD}"
export BRIDGE_STORE_PASSWORD
export BRIDGE_KEY_PASSWORD="${BRIDGE_KEY_PASSWORD:-$BRIDGE_STORE_PASSWORD}"
for BRIDGE_TOOL in d8 aapt2 zipalign apksigner; do
    test -x "$BRIDGE_TOOLS/$BRIDGE_TOOL" || { echo "Missing build tool: $BRIDGE_TOOL" >&2; exit 1; }
done
test -f "$BRIDGE_JAR" || { echo 'Missing Android SDK platform 35.' >&2; exit 1; }
rm -rf "$BRIDGE_BUILD/classes" "$BRIDGE_BUILD/dex"
rm -f "$BRIDGE_BUILD/classes.jar"
mkdir -p "$BRIDGE_BUILD/classes" "$BRIDGE_BUILD/dex"
find "$BRIDGE_SOURCE/src" -name '*.java' -print > "$BRIDGE_BUILD/sources.txt"
"$BRIDGE_JAVAC" --release 8 -encoding UTF-8 -classpath "$BRIDGE_JAR" -d "$BRIDGE_BUILD/classes" @"$BRIDGE_BUILD/sources.txt"
"$BRIDGE_SOURCE/test.sh"
(cd "$BRIDGE_BUILD/classes" && zip -qr "$BRIDGE_BUILD/classes.jar" .)
"$BRIDGE_TOOLS/d8" --min-api 26 --lib "$BRIDGE_JAR" --output "$BRIDGE_BUILD/dex" "$BRIDGE_BUILD/classes.jar"
"$BRIDGE_TOOLS/aapt2" compile --dir "$BRIDGE_SOURCE/res" -o "$BRIDGE_BUILD/resources.zip"
"$BRIDGE_TOOLS/aapt2" link -I "$BRIDGE_JAR" --manifest "$BRIDGE_SOURCE/AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 28 -o "$BRIDGE_BUILD/unsigned.apk" "$BRIDGE_BUILD/resources.zip"
(cd "$BRIDGE_BUILD/dex" && zip -q "$BRIDGE_BUILD/unsigned.apk" classes.dex)
"$BRIDGE_TOOLS/zipalign" -f 4 "$BRIDGE_BUILD/unsigned.apk" "$BRIDGE_BUILD/aligned.apk"
"$BRIDGE_TOOLS/apksigner" sign --ks "$BRIDGE_KEY" --ks-key-alias "$BRIDGE_ALIAS" --ks-pass env:BRIDGE_STORE_PASSWORD --key-pass env:BRIDGE_KEY_PASSWORD --out "$BRIDGE_BUILD/DiPlayClusterBridge.apk" "$BRIDGE_BUILD/aligned.apk"
"$BRIDGE_TOOLS/apksigner" verify --verbose "$BRIDGE_BUILD/DiPlayClusterBridge.apk"
"$BRIDGE_TOOLS/aapt2" dump badging "$BRIDGE_BUILD/DiPlayClusterBridge.apk"
