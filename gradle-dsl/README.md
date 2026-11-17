# Gradle DSL Support

`dev.phantom.gradle-dsl` · [한국어](./KOR.md)

A technical plugin with no UI of its own: an API that reads Gradle build files as a model and edits them in place —
dependencies, plugins, repositories, `ext` properties, version catalogs (`libs.versions.toml`) and the Android blocks,
in Groovy, Kotlin script and TOML. Gradle-Java's dependency updater and build analysis use it. The Kotlin script parser
switches on only when the Kotlin plugin is installed.

Based on the IntelliJ Community Gradle DSL API plugin (`android/gradle-dsl*`, Apache 2.0), under Phantom's own id.
`org.jetbrains.idea.gradle.dsl` stays as an alias and the content module names stay the IntelliJ ones, so plugins and
bundled modules that depend on them keep working. Phantom bundles this build instead of the IntelliJ plugin.
