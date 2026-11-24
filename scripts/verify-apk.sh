#!/usr/bin/env bash
# Verifies a Sovereign SMS release APK. Reads only the APK; never touches a keystore.
#
#   scripts/verify-apk.sh APK [--cert SHA256] [--previous OLD.apk]
#
#   --cert      expected signing-certificate SHA-256 (hex, colons optional). Without it the
#               digest in docs/release/SIGNING_CERT_SHA256 is used when that file exists.
#   --previous  an APK already installed on the phone: checks the update would install over it
#               (same package, same signing certificate, higher versionCode).
#
# Exit status 0 only when every check passes. Needs Android build-tools (apksigner, aapt2,
# zipalign) on PATH or under $ANDROID_HOME/build-tools/<ver>, plus unzip, readelf, sha256sum.
set -euo pipefail

apk=""; cert=""; prev=""
while [ $# -gt 0 ]; do
  case "$1" in
    --cert) cert="$2"; shift 2 ;;
    --previous) prev="$2"; shift 2 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) apk="$1"; shift ;;
  esac
done
[ -f "$apk" ] || { echo "usage: $0 APK [--cert SHA256] [--previous OLD.apk]" >&2; exit 2; }

root="$(cd "$(dirname "$0")/.." && pwd)"
bt="${BUILD_TOOLS:-}"
if [ -z "$bt" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -z "$sdk" ] && [ -f "$root/local.properties" ] && sdk="$(sed -n 's/^sdk.dir=//p' "$root/local.properties")"
  [ -n "$sdk" ] && bt="$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1)"
fi
tool() { if command -v "$1" >/dev/null 2>&1; then command -v "$1"; elif [ -n "$bt" ] && [ -x "$bt/$1" ]; then echo "$bt/$1"; else echo "missing tool: $1" >&2; exit 2; fi; }
APKSIGNER="$(tool apksigner)"; AAPT2="$(tool aapt2)"; ZIPALIGN="$(tool zipalign)"

fail=0
ok()  { printf 'PASS  %s\n' "$1"; }
bad() { printf 'FAIL  %s\n' "$1"; fail=1; }
norm() { tr -d ': ' | tr 'A-F' 'a-f'; }

signer() { "$APKSIGNER" verify --print-certs "$1" 2>/dev/null | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1 | norm; }
badging() { "$AAPT2" dump badging "$1" 2>/dev/null; }
# The space before the name matters: "compileSdkVersionCodename='16'" also ends in name=.
field() { badging "$1" | sed -n "s/^package:.* $2='\([^']*\)'.*/\1/p" | head -1; }

echo "APK      $apk"
echo "SHA-256  $(sha256sum "$apk" | cut -d' ' -f1)"
echo "Size     $(stat -c %s "$apk") bytes"

if "$APKSIGNER" verify --verbose "$apk" >/dev/null 2>&1; then ok "apksigner verify"; else bad "apksigner verify"; fi
digest="$(signer "$apk")"
echo "Signer   ${digest:-none}"
[ -z "$cert" ] && [ -f "$root/docs/release/SIGNING_CERT_SHA256" ] && cert="$(grep -v -e '^#' -e '^[[:space:]]*$' "$root/docs/release/SIGNING_CERT_SHA256" | head -1 || true)"
if [ -n "$cert" ]; then
  if [ "$digest" = "$(echo "$cert" | norm)" ]; then ok "signing certificate matches the pinned digest"; else bad "signing certificate does not match the pinned digest"; fi
else
  echo "NOT_RUN  certificate pin (no --cert, and docs/release/SIGNING_CERT_SHA256 holds no fingerprint)"
fi
signers="$("$APKSIGNER" verify --print-certs "$apk" 2>/dev/null | grep -c '^Signer #[0-9]* certificate DN' || true)"
[ "$signers" = "1" ] && ok "exactly one signer" || bad "signer count is $signers"

pkg="$(field "$apk" name)"; vc="$(field "$apk" versionCode)"; vn="$(field "$apk" versionName)"
echo "Package  $pkg  versionCode $vc  versionName $vn"
[ "$pkg" = "com.sovereignops.sms" ] && ok "release package id" || bad "package is $pkg, not com.sovereignops.sms"
if badging "$apk" | grep -q "application-debuggable"; then bad "APK is debuggable"; else ok "not debuggable"; fi
if badging "$apk" | grep -q "uses-permission: name='android.permission.INTERNET'"; then bad "INTERNET permission present"; else ok "no INTERNET permission"; fi

if "$ZIPALIGN" -c -P 16 4 "$apk" >/dev/null 2>&1; then ok "zipalign -P 16 (16 KB page alignment of stored .so)"; else bad "zipalign -P 16"; fi
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$apk" 'lib/*' -d "$tmp" 2>/dev/null || true
so_count=0; so_bad=0
while IFS= read -r so; do
  so_count=$((so_count + 1))
  if readelf -lW "$so" | awk '$1=="LOAD"{print $NF}' | grep -qv '^0x4000$\|^0x10000$'; then so_bad=$((so_bad + 1)); echo "      ${so#"$tmp"/}: LOAD alignment below 16 KB"; fi
done < <(find "$tmp" -name '*.so' | sort)
[ "$so_bad" = 0 ] && ok "ELF LOAD segments 16 KB aligned ($so_count native libraries)" || bad "$so_bad of $so_count native libraries not 16 KB aligned"

if [ -n "$prev" ]; then
  [ -f "$prev" ] || { bad "previous APK not found: $prev"; exit 1; }
  ppkg="$(field "$prev" name)"; pvc="$(field "$prev" versionCode)"; pdig="$(signer "$prev")"
  echo "Previous $prev  versionCode $pvc  signer $pdig"
  [ "$ppkg" = "$pkg" ] && ok "same package as the installed build" || bad "package differs from the installed build ($ppkg)"
  [ "$pdig" = "$digest" ] && ok "same signing certificate as the installed build" || bad "signing certificate differs: Android will refuse the update"
  [ "${vc:-0}" -gt "${pvc:-0}" ] && ok "versionCode increases ($pvc -> $vc)" || bad "versionCode does not increase ($pvc -> $vc)"
fi

exit "$fail"
