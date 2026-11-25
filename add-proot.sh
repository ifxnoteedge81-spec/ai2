#!/usr/bin/env bash
# Optional: bundle a PRoot binary so the app can offer the Alpine Linux environment.
#
# Android 10+ only lets an app execute binaries from its APK native-library directory, so PRoot must be
# packaged as app/src/main/jniLibs/<abi>/libproot.so BEFORE building. This script does not download
# anything: you supply a PRoot binary you trust (static, built for Android/Linux of the right ABI) and
# its expected SHA-256. See docs/RUNTIME.md for licensing (PRoot is GPL-2.0) and build notes.
#
# Usage: runtime/scripts/add-proot.sh <abi: arm64-v8a|armeabi-v7a|x86_64> <path-to-proot> <sha256> [path-to-loader]
set -euo pipefail
abi="${1:?abi}"; src="${2:?path to proot binary}"; want="${3:?expected sha256}"; loader="${4:-}"
case "$abi" in arm64-v8a|armeabi-v7a|x86_64) ;; *) echo "unsupported abi: $abi" >&2; exit 2;; esac
got="$(sha256sum "$src" | cut -d' ' -f1)"
[ "$got" = "$want" ] || { echo "checksum mismatch: expected $want, got $got" >&2; exit 3; }
head -c4 "$src" | grep -q $'\x7fELF' || { echo "not an ELF binary" >&2; exit 4; }
dest="$(dirname "$0")/../../app/src/main/jniLibs/$abi"
mkdir -p "$dest"
install -m 0755 "$src" "$dest/libproot.so"
if [ -n "$loader" ]; then install -m 0755 "$loader" "$dest/libproot-loader.so"; fi
echo "Installed $dest/libproot.so. Rebuild the APK."
