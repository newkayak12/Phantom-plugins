# Changelog

Users and reviewers read what changed in a plugin release from its `CHANGELOG.md`, not from the git log.

- Each plugin folder has a `CHANGELOG.md`. A change that users or plugin authors can notice adds one line to it in the
  same pull request; a plugin without the file gets it with that line. Refactors, tests and build-only changes need no
  line.
- Layout: `# <Plugin name> 변경 이력`, then `## 미배포`, then one section per release, newest first:
  `## <version> (<YYYY-MM-DD>)`. Lines are Korean, like Phantom's `CHANGELOG.md`, and end with the PR number: `(#11)`.
- A version bump in `build.gradle.kts` moves the `미배포` lines under the new version heading in the same commit, so
  the release tag `<folder>-<version>` matches a section.
- Repository-wide changes (buildSrc, CI, rules) are not plugin changes; they go in the pull request description only.
