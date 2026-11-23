#!/usr/bin/env bash
# Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
#
# Attaches the corresponding sources of the copyleft (GPL, LGPL, MPL, EPL) components a release ships to that GitHub
# release, as NOTICE promises: the -sources.jar files of the Maven repositories the build takes the libraries from, and
# for dictionary data published without one, a snapshot of the upstream project the data is built from (its word
# lists and build scripts, at the commit or tag of the version shipped) or the upstream Hunspell files it was converted from.
#
#     tools/attach-sources.sh <tag> --dry-run   # list the files
#     tools/attach-sources.sh <tag> --download-dir <directory> # prepare without publishing
#     tools/attach-sources.sh <tag>             # download them and upload them to the release <tag> (needs gh)
#
# <tag> is a release tag of:
#   grammar-<version>        LanguageTool core and English (LGPL 2.1), english-pos-dict (LGPL 2.1), openregex (LGPL)
#   coverage-<version>       JaCoCo (EPL 2.0)
#   languagetool-<version>   LanguageTool core and the language modules of the bundles (LGPL 2.1), and the dictionary
#                            data under GPL, LGPL or MPL the bundles embed (tools/languagetool-release-notes.md)
# The library versions are those of the plugin's build.gradle.kts in this checkout: check out the release's commit first.
# The upstream commits of the dictionary data are those of LanguageTool 6.8.27 (the 6.8 versions of its pom.xml).
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
# <owner/repository> <commit or tag>: a snapshot of a GitHub project, attached as <repository>-<commit or tag>.tar.gz
add_github() {
  urls+=("https://codeload.github.com/$1/tar.gz/$2#${1#*/}-${2:0:10}.tar.gz")
}
# <tag> <directory>: the Hunspell files of one language in the LibreOffice dictionaries
# (https://git.libreoffice.org/dictionaries, mirrored at github.com/LibreOffice/dictionaries), attached as
# dictionaries-<tag>-<directory>.tar.gz
add_libreoffice() {
  urls+=("libreoffice:$1:$2#dictionaries-$1-$2.tar.gz")
}

case "$tag" in
  grammar-*)
    lt=$(version_of grammar org.jetbrains.intellij.deps.languagetool:languagetool-core)
    add "$jetbrains" org.jetbrains.intellij.deps.languagetool languagetool-core "$lt" sources
    add "$jetbrains" org.jetbrains.intellij.deps.languagetool language-en "$lt" sources
    add "$central" edu.washington.cs.knowitall openregex "$(version_of grammar edu.washington.cs.knowitall:openregex)" sources
    # english-pos-dict has no -sources.jar and its jar holds only compiled dictionaries: the source is the upstream
    # project (word lists and build scripts) at the commit that made version 0.6
    [[ "$(version_of grammar org.languagetool:english-pos-dict)" == 0.6 ]] || { echo 'error: update the english-pos-dict commit' >&2; exit 1; }
    add_github languagetool-org/english-pos-dict 2c68994244eba9f54a0b561b9213c60c6cbf9c6c
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
    [[ "$lt" == 6.8.27 ]] || { echo "error: the dictionary sources below are those of LanguageTool 6.8.27" >&2; exit 1; }
    # Libraries the bundles embed that have a -sources.jar
    add "$central" edu.washington.cs.knowitall openregex 1.1.1 sources              # de, ru (LGPL)
    add "$central" com.github.lucene-gosen lucene-gosen 6.2.1 sources              # ja (LGPL 2.1)
    # Dictionary data with no -sources.jar: the upstream project at the commit that made the version embedded
    add_github languagetool-org/english-pos-dict 2c68994244eba9f54a0b561b9213c60c6cbf9c6c # en, 0.6 (LGPL 2.1)
    add_github esbardu/asturian-pos-dict c587ea3843dc581e98324f362e68cb7a3ef4b262       # ast, 0.1 (GPL 3.0)
    add_github Softcatala/catalan-dict-tools 753d62b18d58d26f401a4d441445f6deb7199703   # ca, 3.3 (GPL 2.0 / LGPL 2.1)
    add_github jaumeortola/spanish-dict-tools 3d1f700c82d8bcfa9de7ecb8535fdf217cdc6fc1  # es, 2.5 (LGPL 2.1)
    add_github jimregan/languagetool-ga-dicts 2a127b3d079fb3824b89b60d21ae3db1cb348b28  # ga, 0.02 (GPL 2.0)
    add_github brown-uk/dict_uk v6.8.0                                                  # uk, morfologik-ukrainian-lt 6.8.0 (LGPL)
    # Spelling dictionaries converted from Hunspell files: the LibreOffice release whose README is the one in the bundle
    add_libreoffice libreoffice-7.0.0.1 it_IT # it, dict-it 3.3.1 (GPL 3.0)
    add_libreoffice libreoffice-7.0.0.1 da_DK # da (GPL 2.0 / LGPL 2.1 / MPL 1.1)
    add_libreoffice libreoffice-7.0.0.1 sl_SI # sl (GPL / LGPL 2.1)
    add_libreoffice libreoffice-6.0.0.1 gl    # gl, 12.10 (GPL 3.0)
    ;;
  *)
    echo "error: no copyleft sources are known for $tag" >&2
    exit 1
    ;;
esac

echo "Sources for release $tag on $repo:"
for entry in "${urls[@]}"; do
  if [[ "$entry" == *'#'* ]]; then echo "  ${entry##*#}  from ${entry%%#*}"; else echo "  $entry"; fi
done
$dry_run && exit 0

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
files=()
for entry in "${urls[@]}"; do
  url="${entry%%#*}"
  if [[ "$entry" == *'#'* ]]; then file="$work/${entry##*#}"; else file="$work/$(basename "$url")"; fi
  case "$url" in
    libreoffice:*)
      # The files of one directory at one tag, packed as <directory>/...
      python3 -I - "$url" "$file" <<'PY'
import io, json, subprocess, sys, tarfile
_, tag, directory = sys.argv[1].split(':')
base = 'LibreOffice/dictionaries'
def get(url):
    return subprocess.run(['curl', '--retry', '3', '-fsSL', url], capture_output=True, check=True).stdout
tree = json.loads(get(f'https://api.github.com/repos/{base}/git/trees/{tag}?recursive=1'))
paths = [e['path'] for e in tree['tree'] if e['type'] == 'blob' and e['path'].startswith(directory + '/')]
if not paths or tree.get('truncated'):
    raise SystemExit(f'No files for {directory} at {tag}')
with tarfile.open(sys.argv[2], 'w:gz') as archive:
    for path in sorted(paths):
        data = get(f'https://raw.githubusercontent.com/{base}/{tag}/{path}')
        info = tarfile.TarInfo(f'dictionaries-{tag}/{path}')
        info.size = len(data)
        archive.addfile(info, io.BytesIO(data))
PY
      ;;
    *)
      curl --retry 3 -fsSL -o "$file" "$url"
      ;;
  esac
  python3 -I - "$file" <<'PY'
import sys
import tarfile
import zipfile
name = sys.argv[1]
if name.endswith('.tar.gz'):
    with tarfile.open(name) as archive:
        if not archive.getmembers():
            raise SystemExit('Empty source archive: ' + name)
else:
    with zipfile.ZipFile(name) as jar:
        if not jar.namelist() or jar.testzip() is not None:
            raise SystemExit('Invalid or empty source archive: ' + name)
PY
  files+=("$file")
done
if [[ -n "$download_dir" ]]; then
  mkdir -p "$download_dir"
  cp "${files[@]}" "$download_dir/"
  exit 0
fi
gh release upload "$tag" --repo "$repo" --clobber "${files[@]}"
