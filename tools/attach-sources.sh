#!/usr/bin/env bash
# Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
#
# Attaches the corresponding sources of the copyleft (LGPL, EPL) libraries a release ships to that GitHub release, as
# NOTICE promises: their -sources.jar files, downloaded from the Maven repositories the build takes the libraries from.
#
#     tools/attach-sources.sh <tag> --dry-run   # list the files
#     tools/attach-sources.sh <tag> --download-dir <directory> # prepare without publishing
#     tools/attach-sources.sh <tag>             # download them and upload them to the release <tag> (needs gh)
#
# <tag> is a release tag of:
#   grammar-<version>        LanguageTool core and English (LGPL 2.1), english-pos-dict (LGPL 2.1), openregex (LGPL)
#   coverage-<version>       JaCoCo (EPL 2.0)
#   languagetool-<version>   LanguageTool core and the language modules of the bundles (LGPL 2.1)
# The library versions are those of the plugin's build.gradle.kts in this checkout: check out the release's commit first.
set -euo pipefail

repo="newkayak12/Phantom-plugins"
root="$(cd "$(dirname "$0")/.." && pwd)"
central="https://repo1.maven.org/maven2"
jetbrains="https://packages.jetbrains.team/maven/p/ij/intellij-dependencies"

tag="${1:?usage: tools/attach-sources.sh <tag> [--dry-run]}"
dry_run=false
download_dir=""
case "${2:-}" in
  --dry-run) [[ $# == 2 ]]; dry_run=true ;;
  --download-dir) [[ $# == 3 && -n "$3" ]]; download_dir="$3" ;;
  '') [[ $# == 1 ]] ;;
  *) echo 'error: expected --dry-run or --download-dir <directory>' >&2; exit 1 ;;
esac

# The version a build script gives group:artifact, e.g. "org.jacoco:org.jacoco.ant:0.8.15" -> 0.8.15
version_of() { grep -oE "\"$2:[^:\"]+(:|\")" "$root/$1/build.gradle.kts" | head -1 | sed -E "s/\"$2://; s/[:\"]$//"; }

# <repository> <group> <artifact> <version> [classifier]: one file to attach
urls=()
add() {
  [[ -n "$4" ]] || { echo "error: missing version for $2:$3" >&2; exit 1; }
  local path="${2//.//}/$3/$4/$3-$4${5:+-$5}.jar"
  urls+=("$1/$path")
}

case "$tag" in
  grammar-*)
    lt=$(version_of grammar org.jetbrains.intellij.deps.languagetool:languagetool-core)
    add "$jetbrains" org.jetbrains.intellij.deps.languagetool languagetool-core "$lt" sources
    add "$jetbrains" org.jetbrains.intellij.deps.languagetool language-en "$lt" sources
    add "$central" edu.washington.cs.knowitall openregex "$(version_of grammar edu.washington.cs.knowitall:openregex)" sources
    # Dictionary data with no -sources.jar: the jar is its own source form (https://github.com/languagetool-org/english-pos-dict)
    add "$central" org.languagetool english-pos-dict "$(version_of grammar org.languagetool:english-pos-dict)"
    ;;
  coverage-*)
    jacoco=$(version_of coverage org.jacoco:org.jacoco.ant)
    for artifact in org.jacoco.agent org.jacoco.ant org.jacoco.core org.jacoco.report; do
      add "$central" org.jacoco "$artifact" "$jacoco" sources
    done
    ;;
  languagetool-*)
    lt="${tag#languagetool-}"
    add "$jetbrains" org.jetbrains.intellij.deps.languagetool languagetool-core "$lt" sources
    descriptors="$root/grammar/src/main/kotlin/com/intellij/grazie/remote/LanguageToolDescriptor.kt"
    for iso in $(grep -oE 'LanguageISO\.[A-Z]+' "$descriptors" | sed 's/LanguageISO\.//' | tr '[:upper:]' '[:lower:]' | sort -u); do
      add "$jetbrains" org.jetbrains.intellij.deps.languagetool "language-$iso" "$lt" sources
    done
    ;;
  *)
    echo "error: no copyleft sources are known for $tag" >&2
    exit 1
    ;;
esac

echo "Sources for release $tag on $repo:"
printf '  %s\n' "${urls[@]}"
$dry_run && exit 0

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
files=()
for url in "${urls[@]}"; do
  file="$work/$(basename "$url")"
  curl --retry 3 -fsSL -o "$file" "$url"
  python3 - "$file" <<'PY'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1]) as jar:
    if not jar.namelist() or jar.testzip() is not None:
        raise SystemExit('Invalid or empty source archive: ' + sys.argv[1])
PY
  files+=("$file")
done
if [[ -n "$download_dir" ]]; then
  mkdir -p "$download_dir"
  cp "${files[@]}" "$download_dir/"
  exit 0
fi
gh release upload "$tag" --repo "$repo" --clobber "${files[@]}"
