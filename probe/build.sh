#!/bin/bash
set -e

SDK="${ANDROID_SDK:-$HOME/workStation/android/sdk}"
BT=$SDK/build-tools/35.0.0
AJ=$SDK/platforms/android-29/android.jar
PROJ="$(cd "$(dirname "$0")" && pwd)"
OUT=$PROJ/out

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -A "$PROJ/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 29

javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$AJ" \
  -d "$OUT/classes" \
  $(find "$PROJ/src" -name '*.java') \
  $(find "$OUT/gen" -name '*.java' 2>/dev/null)

"$BT/d8" --release --min-api 29 --lib "$AJ" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

(cd "$OUT/dex" && zip -q -u "$OUT/base.apk" classes.dex)

"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

KS=$PROJ/debug.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -validity 10000 -keyalg RSA -keysize 2048 >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/ZCProbe.apk" "$OUT/aligned.apk"

echo "built: $OUT/ZCProbe.apk"
