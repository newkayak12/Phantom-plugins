# Releases

Mistakes agents repeated when publishing plugins. A release is published only when the user asked for one
(`pull-requests.md`).

- Publish with `tools/release.py <dir> --publish`, never with a bare `gh release create`: it checks the packaged notices,
  attaches the copyleft sources and fails when an asset is missing. (grammar-0.2.2 went out with its zip only, while
  NOTICE promises the LGPL sources on every Grammar release.)
- This repository's Actions do not run (no runner is assigned to its jobs), so a step that exists only in
  `.github/workflows/plugin-release.yml` never happens. Anything that workflow does for a release, the local tools must
  do too; change both together.
- After publishing, list the release's assets (`gh release view <tag> --json assets`) and compare them with what NOTICE
  promises (`tools/attach-sources.sh <tag> --dry-run`). Report the list; do not report a release you did not check.
- Never replace a published asset (`--clobber`, delete and re-upload): Phantom pins each bundled zip by sha256, so other
  bytes under the same name break its build. Changed contents get a new version; only missing files may be added.
- A plugin Phantom bundles is not released until Phantom points at it: run Phantom's
  `tools/phantom-plugins-bundle.sh`, which publishes the phantom-libs release and updates
  `build/phantom-plugins-bundle.txt`, in a Phantom PR.
- Check open issues before the PR (`gh issue list`); the release PR says `Closes #N` or `Part of #N`. Issues close only
  through the close-linked-issues workflow.
