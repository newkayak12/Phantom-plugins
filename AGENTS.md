# Phantom-plugins agent guide

This repository builds Phantom IDE plugins with Gradle. Read the affected plugin's `README.md`, `build.gradle.kts`, descriptor in `src/main/resources/META-INF/`, and any nearer agent instructions before editing. Shared build behavior lives in `buildSrc/src/main/kotlin/phantom-plugin.gradle.kts`; projects are registered in `settings.gradle.kts`.

## Find the source owner

- Plugin features belong here. Platform behavior, product branding, and the IDE bundle selection belong in the separate Phantom repository.
- Some plugins are adapted upstream sources. Preserve their plugin ID aliases, content module names, package compatibility, and license notices. A `bundledPlugin(...)` dependency uses the original IntelliJ ID; the shared convention resolves Phantom aliases for the installed IDE.
- Before fixing a bundled plugin, check which repository supplies the shipped source. Editing `plugins/<name>` in Phantom does not update a plugin built from this repository. When both repositories need changes, describe and validate each part explicitly.

## Implementation

- Keep changes within the affected plugin; use shared conventions only when the behavior applies across plugins.
- Put user-facing strings in resource bundles. Follow existing platform threading and lifetime rules; avoid unnecessary startup work and network requests.
- Preserve root `LICENSE` and `NOTICE` and each plugin's license. The convention packages these in plugin jars. Never copy decompiled proprietary code.
- Do not change plugin versions, compatibility ranges, marketplace metadata, tags, or release workflows merely to make a local build pass. Publishing is a separate user-requested action.

## Verify

Use JDK 25 and the checked-in Gradle wrapper. The default platform is the installed app from `gradle.properties` (`phantomIde`); override it with `-PphantomIde=/absolute/path/to/Phantom.app`.

```sh
./gradlew :<plugin>:test
./gradlew :<plugin>:buildPlugin
```

If no compatible Phantom is installed, use `-PplatformFromRepository=true` to download the configured `platformTestBuild`, for example `./gradlew :spring:test -PplatformFromRepository=true`. This checks against the upstream platform, so Phantom-only integrations still need verification in Phantom. If an installed bundled copy shadows the plugin under test, inspect `platformFromRepositoryFor` and the shared convention before interpreting results.

Run affected tests after code changes and build the affected plugin zip when packaging or descriptors change. Use a fully qualified test filter when narrowing a run (`--tests 'package.ClassName'`). Report commands, results, and anything unverified; documentation-only edits do not need an IDE build.

Binding rules for commits, pull requests and plugin ports are in [`.ai/rules/`](.ai/rules/README.md); read them before committing.

See [`.ai/workflow.md`](.ai/workflow.md) for the task and skill-routing workflow. Personal preferences belong in ignored `.ai/local.md` or `.claude/settings.local.json`; shared skills and instructions remain reviewable in git.

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

- Submit changes through a PR. The prepared ruleset requires zero approvals, permits administrator bypass and retains the Kotlin `check` status. Live enforcement is unavailable while GitHub returns HTTP 403 for this private repository.
- Commit subject: `<folder> <version>: <what users see now>`, e.g.
  `db 0.25.8: Modify Table shows and keeps column comments`. Several plugins: `toml, yaml 0.1.0: ...`. Changes without
  a plugin release describe the effect: `Release workflow skips maven-* tags: ...`. The body says why.
- Commits by people appear under their own GitHub identity; no company e-mail addresses in commits.
- Not in git: build output, IDE and OS files, agent working files (`.gitignore`). `*/testData/**` keeps its `.idea`,
  `out` and `.log` files on purpose.

## For Claude

- Answer the user in Korean, short and direct. Code, comments, commit messages and docs are in English, except KOR.md.
- Long build or test output goes through the context-mode tools, not into the conversation.
- Releases, release tags, force pushes, keychain token access and repository setting changes require explicit authorization. Honor authorization already given for the current work.
- Another session may be working in the same checkout: check `git status` and running builds before building,
  rebasing or resetting, and never `reset --hard` over someone else's work.
- Report what was verified and how; a step not run is said to be not run.
