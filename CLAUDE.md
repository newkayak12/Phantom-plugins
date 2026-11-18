# CLAUDE.md

Rules for working in this repository, by people and by coding agents (Claude Code reads this file). They were taken
from how the repository has been worked on so far; README.md has the user-facing description.

## What this is

Plugins for Phantom, a fork of IntelliJ IDEA Community. Each top-level folder with a `build.gradle.kts` is one plugin
(`plugins { id("phantom-plugin") }`, shared setup in `buildSrc/src/main/kotlin/phantom-plugin.gradle.kts`). Phantom
reads `.phantom/marketplace.json` from `main` and downloads the zips from this repository's GitHub releases.

Many folders (json, yaml, terminal, github, images, groovy ...) are IntelliJ Community plugins built here instead of
inside Phantom. Phantom bundles them from this repository (`PHANTOM_PLUGINS_BUNDLED` in Phantom's
`IdeaCommunityProperties.kt`), so a fix to one of them goes here, not into Phantom's `plugins/<name>`.

## Plugins

- Id `dev.phantom.<folder>`, package `dev.phantom.<folder>`, vendor `Phantom`, `since-build` 262.
- A plugin ported from IntelliJ keeps the IntelliJ id as `<module value="..."/>` alias and keeps IntelliJ's module
  names, so plugins depending on the IntelliJ id still resolve. Build scripts ask for bundled plugins by their
  IntelliJ id (`bundledPlugin("com.intellij.properties")`), never `dev.phantom.*`.
- Ported plugins lose JetBrains usage statistics (FUS collectors), plugin advertisements and Compose previews.
- Names: Phantom's own plugins are `Phantom <Name>`. A plugin named after another project's trademark is
  `<X> Support` (Spring Support, Kafka Support), and Java-only ones say `for Java`. Ids never change with a rename.
- Every new source file starts with
  `// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.`
  Code taken from IntelliJ keeps its JetBrains header; a folder with another license keeps its own `LICENSE`.
- User-visible strings go in the plugin's `messages/<Name>Bundle.properties`.
- Each plugin folder has `README.md` and `KOR.md` (Korean) and a row in the tables of the root README.md and KOR.md.
- Adding a plugin: README.md → "Adding a plugin".

## Code

- Kotlin, JDK 25, IntelliJ code style: 2-space indent, wildcard imports allowed, lines up to 140 (`.editorconfig`).
- Spotless (ktlint) checks only files changed against `origin/main`, and detekt fails only on findings not in
  `config/detekt/baseline.xml`; existing code keeps its form until it is edited. ktlint rules that fight the IntelliJ
  style are off in `.editorconfig`, detekt's in `config/detekt/detekt.yml`. Before pushing: `./gradlew spotlessApply detekt`. A deliberate new detekt finding goes into
  `config/detekt/baseline.xml` with `./gradlew detektBaseline`, not by turning a rule off.
- Comments say why, in full sentences, and name the IntelliJ class or behavior they work around.
- No speculative options or abstractions; touch only what the change needs.

## Tests

- Every behavior change comes with a test in `<folder>/src/test/kotlin` (IntelliJ test framework, JUnit 4).
- Run `./gradlew :<folder>:test :<folder>:buildPlugin`. Locally the plugins compile against the installed Phantom
  (`phantomIde`); CI has none and uses `-PplatformFromRepository=true` (the IntelliJ Community build in
  `platformTestBuild`). Something can pass locally and fail in CI: before a release tag, run the CI command in a clean
  clone outside the home directory (`git clone` to /tmp).
- Tests that need a plugin Phantom no longer bundles (Kotlin since 26.11.13) are skipped when it is absent, as in
  `db/build.gradle.kts`.

## Versions and releases

- Bump `version` in `<folder>/build.gradle.kts` for any change that ships. The marketplace entry's `version` and
  `download` (`<folder>-<version>/<folder>-<version>.zip`) change together; `DbDriverRegistryTest` checks db's.
- A release is the tag `<folder>-<version>` on `main`; pushing it runs `.github/workflows/plugin-release.yml`, which
  tests, builds and publishes the zip. At most three tags per push. `kotlin-*` and `maven-*` are released by Phantom's
  `phantom-release.sh`, not here.
- Never move or delete a released tag except to re-release the same version after a failed CI run.

## Git

- `main` is protected: changes go through a branch and a pull request (`<type>/<topic>`, e.g. `fix/db-comment`).
  Admins too: the GitHub ruleset `.github/rulesets/main.json` requires a PR and the `check` job of
  `pull-request.yml`, forbids force pushes and deletion, and lets admins bypass only inside a PR (merging with
  `gh pr merge --admin` before the checks finish). Phantom's `phantom-release.sh` updates `marketplace.json` that way.
  Apply a change to the ruleset with
  `gh api repos/newkayak12/Phantom-plugins/rulesets[/<id>] -X POST|PUT --input .github/rulesets/main.json`.
- Commit subject: `<folder> <version>: <what users see now>`, e.g.
  `db 0.25.8: Modify Table shows and keeps column comments`. Several plugins: `toml, yaml 0.1.0: ...`. Changes without
  a plugin release describe the effect: `Release workflow skips maven-* tags: ...`. The body says why.
- Commits by people appear under their own GitHub identity; no company e-mail addresses in commits.
- Not in git: build output, IDE and OS files, agent working files (`.gitignore`). `*/testData/**` keeps its `.idea`,
  `out` and `.log` files on purpose.

## For Claude

- Answer the user in Korean, short and direct. Code, comments, commit messages and docs are in English, except KOR.md.
- Long build or test output goes through the context-mode tools, not into the conversation.
- Ask before anything outward-facing or hard to undo: release tags, GitHub releases, force pushes, using the Phantom
  keychain token, changing repository settings. A yes holds for that one action.
- Another session may be working in the same checkout: check `git status` and running builds before building,
  rebasing or resetting, and never `reset --hard` over someone else's work.
- Report what was verified and how; a step not run is said to be not run.
