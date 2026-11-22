#!/usr/bin/env bash
# Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
#
# Creates the GitHub release languagetool-<version> that Phantom Grammar downloads its LanguageTool language bundles
# from (GraziePlugin.LanguageTool.url): <iso>-<version>.jar for every language in LanguageToolDescriptor.kt.
#
#     tools/languagetool-release.sh --dry-run   # list the files and where they come from
#     tools/languagetool-release.sh             # download, check the MD5 checksums, create the release (needs gh)
#
# The bundles are LanguageTool (LGPL 2.1) with its language data, which embeds components under other licenses
# (tools/languagetool-release-notes.md), as JetBrains builds them from
# https://github.com/JetBrains/languagetool for IntelliJ. They are taken once from JetBrains' download location so
# that the files are byte for byte the ones whose MD5 checksums the plugin checks (LanguageToolDescriptor.kt); each is
# checked before anything is uploaded. The release also carries the license texts, and tools/attach-sources.sh adds
# the corresponding sources jars of the language modules.
set -euo pipefail

repo="newkayak12/Phantom-plugins"
root="$(cd "$(dirname "$0")/.." && pwd)"
descriptors="$root/grammar/src/main/kotlin/com/intellij/grazie/remote/LanguageToolDescriptor.kt"
plugin="$root/grammar/src/main/kotlin/com/intellij/grazie/GraziePlugin.kt"
origin="https://resources.jetbrains.com/grazie/model/language-tool"

dry_run=false
[[ "${1:-}" == "--dry-run" ]] && dry_run=true

version=$(sed -nE 's/.*const val version = "([^"]+)".*/\1/p' "$plugin")
tag="languagetool-$version"

# "  GERMAN(listOf(...), 20, LanguageISO.DE, DE_CHECKSUM)," and "private const val DE_CHECKSUM = "706709b4..."" (the
# descriptors of English span several lines, so the ISO codes are taken from the LanguageISO references)
isos=$(grep -oE 'LanguageISO\.[A-Z]+' "$descriptors" | sed 's/LanguageISO\.//' | sort -u)
checksum() { sed -nE "s/^private const val ${1}_CHECKSUM = \"([0-9a-f]+)\"/\1/p" "$descriptors"; }

echo "Release $tag on $repo:"
for iso in $isos; do
  lower=$(tr '[:upper:]' '[:lower:]' <<<"$iso")
  echo "  $lower-$version.jar  md5 $(checksum "$iso")  from $origin/$version/$lower-$version.jar"
done
echo "  LICENSE-{LGPL-2.1,GPL-2.0,GPL-3.0,CC-BY-SA-4.0,Unicode-3.0,Apache-2.0}.txt  from licenses/ and LICENSE"
if $dry_run; then
  bash "$root/tools/attach-sources.sh" "$tag" --dry-run
  exit 0
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
files=()
for iso in $isos; do
  lower=$(tr '[:upper:]' '[:lower:]' <<<"$iso")
  file="$work/$lower-$version.jar"
  curl -fsSL -o "$file" "$origin/$version/$lower-$version.jar"
  expected=$(checksum "$iso")
  actual=$( (md5 -q "$file" 2>/dev/null || md5sum "$file") | cut -d' ' -f1)
  if [[ "$actual" != "$expected" ]]; then
    echo "error: $lower-$version.jar has MD5 $actual, LanguageToolDescriptor.kt expects $expected" >&2
    exit 1
  fi
  files+=("$file")
done
# The bundles embed components under other licenses (tools/languagetool-release-notes.md lists them); their texts
for spdx in LGPL-2.1-only:LGPL-2.1 GPL-2.0-only:GPL-2.0 GPL-3.0-only:GPL-3.0 CC-BY-SA-4.0:CC-BY-SA-4.0 Unicode-3.0:Unicode-3.0; do
  cp "$root/licenses/${spdx%%:*}.txt" "$work/LICENSE-${spdx##*:}.txt"
  files+=("$work/LICENSE-${spdx##*:}.txt")
done
cp "$root/LICENSE" "$work/LICENSE-Apache-2.0.txt"
files+=("$work/LICENSE-Apache-2.0.txt")
bash "$root/tools/attach-sources.sh" "$tag" --download-dir "$work/sources"
files+=("$work/sources/"*.jar)

# Written for 6.8.27 from the POMs and README files inside the bundles; check them again for another version
notes_file="$root/tools/languagetool-release-notes.md"
grep -q "LanguageTool $version " "$notes_file" || { echo "error: $notes_file is not about LanguageTool $version" >&2; exit 1; }

gh release create "$tag" --repo "$repo" --target main --latest=false \
  --title "LanguageTool $version language bundles" --notes-file "$notes_file" "${files[@]}"
