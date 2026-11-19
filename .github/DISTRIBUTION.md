# Distribution checks

Plugin release, affected-plugin packaging and Strings analysis check the built ZIP with:

```sh
python3 tools/check-plugin-notices.py <plugin>/build/distributions/*.zip
```

The project's main JAR (named after the ZIP, with `META-INF/plugin.xml`) must carry nonempty `META-INF/LICENSE`, `META-INF/NOTICE` and `META-INF/THIRD-PARTY-NOTICES.txt`. License-text paths referenced by the generated third-party notice must exist and be nonempty in that JAR. A missing main JAR, invalid archive or missing notice fails the job before release publication. Dependency JARs are inventoried by the main JAR's notice rather than required to duplicate it.

This checks packaging, not license compatibility, source provenance, completeness of the dependency inventory or legal compliance. Embedded JavaScript, icons, native binaries and other resources still need their own attribution review.

## Corresponding sources

For Grammar and Coverage, the release workflow downloads and validates the source archives before publishing, then uploads them together with the plugin ZIP. Download or upload failure fails CI. Manual dry runs also prepare sources but do not publish them.

`tools/languagetool-release.sh` prepares the core and language sources before creating its release. Its `--dry-run` lists source URLs without downloading or publishing. The dictionary JAR is distributed as source data because it has no separate sources classifier.

To prepare sources without changing a release:

```sh
bash tools/attach-sources.sh grammar-<version> --download-dir /tmp/grammar-sources
```

Run from a checkout of the release commit: dependency versions come from that checkout's build files. `--dry-run` lists the URLs. Check the release's Assets list after publication; a failed upload can require a retry even when all downloads succeeded. This automation covers the components enumerated in `tools/attach-sources.sh`; new copyleft dependencies must be added explicitly.

## Existing releases

These checks apply to newly built packages; they do not modify old releases. The audit found missing common LICENSE/NOTICE in Icons 0.3.4 and no generated third-party inventory in Terminal 0.2.0. Review those packages and publish a new patch version where needed, without overwriting the existing ZIP. The Terminal inventory finding alone does not establish that a third-party license is missing.

The full IDE installers and every historical plugin archive have not been audited by this check.

## Regression tests

```sh
python3 tools/distribution-checks.test.py
```

Tests use synthetic ZIPs and mocked downloads/publishing. They check missing notices and license references, malformed archives, preparation without publication, and failure before source upload.
