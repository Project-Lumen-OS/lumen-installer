#!/bin/sh
# build_apk.sh: build a signed APK with the Android SDK command line tools only (no Gradle).
#
#   KEY_PK8=key.pk8 KEY_PEM=key.x509.pem sh tools/build_apk.sh APP_DIR OUT_APK
#
# APP_DIR holds AndroidManifest.xml, res/, assets/ and src/**/*.java.
# Needs: a JDK 17 or newer (JAVA_HOME, or /usr/libexec/java_home on a Mac), the Android SDK (ANDROID_SDK or
# ANDROID_HOME, default ~/Library/Android/sdk) with platforms/android-34 (PLATFORM) and build-tools/36.0.0
# (BUILD_TOOLS). MIN_SDK / TARGET_SDK default 29. BUILD_DIR default APP_DIR/build (wiped first).
# Pipeline: aapt2 compile + link -> javac (Java 8 language, against android.jar) -> d8 -> classes.dex stored
# uncompressed -> zipalign -p 4 -> apksigner (v2 + v3) -> apksigner verify.
set -eu
die() { echo "build_apk: $*" >&2; exit 1; }
log() { echo "build_apk: $*"; }
[ $# -eq 2 ] || die "usage: KEY_PK8=... KEY_PEM=... build_apk.sh APP_DIR OUT_APK"
APP=$(cd "$1" && pwd) || die "no such dir: $1"
OUT=$2
case $OUT in /*) ;; *) OUT=$(pwd)/$OUT ;; esac
SDK=${ANDROID_SDK:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}
BT=${BUILD_TOOLS:-$SDK/build-tools/36.0.0}
AJ=$SDK/platforms/${PLATFORM:-android-34}/android.jar
MIN=${MIN_SDK:-29}
TGT=${TARGET_SDK:-29}
B=${BUILD_DIR:-$APP/build}
: "${KEY_PK8:?KEY_PK8 (the signing key, PKCS#8 DER)}" "${KEY_PEM:?KEY_PEM (its certificate)}"
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || true)
fi
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] || die "need a JDK 17+ (set JAVA_HOME)"
PATH=$JAVA_HOME/bin:$PATH   # d8 and apksigner run 'java' from PATH
export PATH JAVA_HOME
for f in "$APP/AndroidManifest.xml" "$AJ" "$KEY_PK8" "$KEY_PEM" "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$BT/core-lambda-stubs.jar"; do
  [ -e "$f" ] || die "missing $f"
done
rm -rf "$B"
mkdir -p "$B/gen" "$B/cls" "$B/dex" "$(dirname "$OUT")"

log "aapt2 compile + link (min $MIN, target $TGT)"
"$BT/aapt2" compile --dir "$APP/res" -o "$B/res.zip"
set -- link -o "$B/base.apk" -I "$AJ" --manifest "$APP/AndroidManifest.xml" --min-sdk-version "$MIN" \
  --target-sdk-version "$TGT" --java "$B/gen"
[ -d "$APP/assets" ] && set -- "$@" -A "$APP/assets"
"$BT/aapt2" "$@" "$B/res.zip"

# argfile entries are quoted: the folder may have spaces
{ find "$APP/src" -name '*.java' -type f; find "$B/gen" -name '*.java' -type f; } | sort | sed 's/.*/"&"/' > "$B/sources.txt"
log "javac $(wc -l < "$B/sources.txt" | tr -d ' ') files"
# lambdas compile against the build tools' LambdaMetafactory stubs (core-lambda-stubs.jar); d8 desugars them
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -classpath "$AJ" \
  -d "$B/cls" @"$B/sources.txt"
find "$B/cls" -name '*.class' | sort > "$B/classes.txt"
log "d8 --min-api $MIN"
"$BT/d8" --release --min-api "$MIN" --lib "$AJ" --output "$B/dex" @"$B/classes.txt"
cp "$B/base.apk" "$B/unaligned.apk"
(cd "$B/dex" && touch -t 200801010000 classes.dex && zip -q -0 -X "$B/unaligned.apk" classes.dex)
log "zipalign -p 4"
"$BT/zipalign" -f -p 4 "$B/unaligned.apk" "$B/aligned.apk"
log "apksigner sign (v2 + v3)"
"$BT/apksigner" sign --key "$KEY_PK8" --cert "$KEY_PEM" --v1-signing-enabled false --v2-signing-enabled true \
  --v3-signing-enabled true --out "$OUT" "$B/aligned.apk"
"$BT/apksigner" verify "$OUT" || die "apksigner verify failed"
rm -f "$OUT.idsig"
log "OK $OUT"
log "   signer SHA-256 $(openssl x509 -in "$KEY_PEM" -outform DER | shasum -a 256 | cut -d' ' -f1)"
