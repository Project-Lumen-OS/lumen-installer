#!/bin/sh
# build.sh: build the installable Lumen Installer APK.
#
#   sh build.sh [OUT_APK]        default: build/LumenInstaller.apk
#
# It runs on the projector's stock XGIMI firmware as an ordinary third-party app (no sharedUserId, no platform
# privileges), so it is signed with ONE dedicated app key for every build, so any published installer updates any
# other. LI_KEY_DIR (default ~/.lumen-keys/installer) holds installerkey.pk8 + installerkey.x509.pem; a key whose
# certificate is not the published one stops the build (another key cannot update installed copies). LI_ANY_KEY=1
# builds with any key (your own test build; it will not update an installed official copy).
# minSdk 29 / targetSdk 29: legacy storage, so a LumenOS folder at the root of a USB stick is readable with the
# storage permission.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
K=${LI_KEY_DIR:-$HOME/.lumen-keys/installer}
OUT=${1:-$HERE/build/LumenInstaller.apk}
# the certificate of every published Lumen Installer
PIN=875174db3c39b8bed5b05327e203c17c1d640b75bb55fe7f47462cd0c4d4995b
if [ ! -f "$K/installerkey.pk8" ] || [ ! -f "$K/installerkey.x509.pem" ]; then
  echo "build.sh: no Lumen Installer key in $K (installerkey.pk8 + installerkey.x509.pem; LI_KEY_DIR)" >&2; exit 1
fi
GOT=$(openssl x509 -in "$K/installerkey.x509.pem" -outform DER | shasum -a 256 | cut -d' ' -f1)
if [ "$GOT" != "$PIN" ] && [ "${LI_ANY_KEY:-0}" != 1 ]; then
  echo "build.sh: key in $K is not the published Lumen Installer key ($GOT); LI_ANY_KEY=1 for a test build" >&2; exit 1
fi
sh -n "$HERE/assets/lumen_installer.sh" || { echo "build.sh: syntax error in assets/lumen_installer.sh" >&2; exit 1; }
# The Lumen font (Onest, SIL OFL 1.1, with its license file) is not kept in git: it is taken from Google Fonts at a
# pinned commit and checked by sha256 (cached in build/fonts), and packed into a staging copy of this folder.
FONT_URL=https://raw.githubusercontent.com/google/fonts/4c1db3aec83c67dd223dd82a68c039cab30917a9/ofl/onest
FONT_SHA=966c5c29b4755da84b6854d5c21dd4eaa2420225d0e9874de602de176d4a9f31
OFL_SHA=071195d8806e226faeee60259c28ca67b458227af5195a73f5cfcab06e3003bc
FD=$HERE/build/fonts
mkdir -p "$FD"
sha() { if [ -f "$1" ]; then shasum -a 256 "$1" | cut -d' ' -f1; fi; }
get() {  # name url sha
  [ "$(sha "$FD/$1")" = "$3" ] && return 0
  curl -fsSL --retry 3 -o "$FD/$1.part" "$2" || { echo "build.sh: cannot download $2" >&2; exit 1; }
  [ "$(sha "$FD/$1.part")" = "$3" ] || { echo "build.sh: $1 from $2 has another sha256" >&2; rm -f "$FD/$1.part"; exit 1; }
  mv "$FD/$1.part" "$FD/$1"
}
get Onest-Variable.ttf "$FONT_URL/Onest%5Bwght%5D.ttf" "$FONT_SHA"
get OFL-Onest.txt "$FONT_URL/OFL.txt" "$OFL_SHA"
ST=$HERE/build/stage/LumenInstaller
rm -rf "$HERE/build/stage"
if [ -f "$HERE/app.layers" ]; then
  # the Lumen OS source tree: the app and the shared source modules its app.layers names, assembled by
  # tools/devices/assemble_app.py (the public repository has those modules in src/ already)
  GSI=$(cd "$HERE/../.." && pwd)
  python3 -B "$GSI/tools/devices/assemble_app.py" --quiet "$HERE/app.layers" --out "$ST" \
    || { echo "build.sh: app.layers does not assemble" >&2; exit 1; }
else
  mkdir -p "$ST"
  for f in AndroidManifest.xml res src assets; do cp -R "$HERE/$f" "$ST/"; done
fi
mkdir -p "$ST/assets/fonts"
cp "$FD/Onest-Variable.ttf" "$FD/OFL-Onest.txt" "$ST/assets/fonts/"
KEY_PK8=$K/installerkey.pk8 KEY_PEM=$K/installerkey.x509.pem MIN_SDK=29 TARGET_SDK=29 BUILD_DIR=$HERE/build/out \
  sh "$HERE/tools/build_apk.sh" "$ST" "$OUT"
echo "build.sh: $OUT"
