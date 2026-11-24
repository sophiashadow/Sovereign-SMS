#!/usr/bin/env bash
# Collects release artifacts for one commit into dist/<version>/ and writes SHA256SUMS.
# Prepares files only: it does not sign, tag, push or publish anything.
#
#   scripts/release-artifacts.sh APK [COMMIT]
#
# Writes: the APK (renamed sovereign-sms-<version>.apk), the GPL-3.0 corresponding source as
# sovereign-sms-<version>-source.tar.gz (git archive of COMMIT, default HEAD, so only tracked
# files), the third-party notices shipped in the APK (assets/licenses), the release notes, and SHA256SUMS over all of them.
set -euo pipefail
apk="${1:?usage: $0 APK [COMMIT]}"; commit="${2:-HEAD}"
root="$(cd "$(dirname "$0")/.." && pwd)"; cd "$root"
[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "tracked files have uncommitted changes; commit first" >&2; exit 1; }
version="$(git show "$commit:gradle.properties" | sed -n 's/^VERSION_NAME=//p')"
sha="$(git rev-parse "$commit")"
out="dist/$version"; mkdir -p "$out"
cp "$apk" "$out/sovereign-sms-$version.apk"
git archive --format=tar --prefix="sovereign-sms-$version/" "$sha" | gzip -n -9 > "$out/sovereign-sms-$version-source.tar.gz"
mkdir -p "$out/licenses" && git archive "$sha" app/src/main/assets/licenses | tar -x -O --wildcards "*NOTICES.txt" > "$out/NOTICES.txt"
for f in $(git ls-tree --name-only "$sha" app/src/main/assets/licenses/); do git show "$sha:$f" > "$out/licenses/$(basename "$f")"; done
cp "docs/release/RELEASE_NOTES_$version.md" "$out/RELEASE_NOTES.md"
cp LICENSE "$out/LICENSE"
( cd "$out" && printf '# Sovereign SMS %s, commit %s\n' "$version" "$sha" > SHA256SUMS && sha256sum -- *.apk *.tar.gz *.md *.txt LICENSE licenses/* >> SHA256SUMS )
cat "$out/SHA256SUMS"
