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

See [`.ai/workflow.md`](.ai/workflow.md) for the task and skill-routing workflow. Personal preferences belong in ignored `.ai/local.md` or `.claude/settings.local.json`; shared skills and instructions remain reviewable in git.
