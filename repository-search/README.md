# Phantom Repository Search

`dev.phantom.repository-search` · [한국어](./KOR.md)

The dependency completion service behind Maven `pom.xml` and Gradle build script completion (group, artifact and version suggestions from the local Maven index, Gradle caches and project modules), and the API Maven and Gradle use to search artifact repositories. JetBrains' Package Search service (package-search.services.jetbrains.com) is never called: its client fails every request before it leaves, as in Phantom IDEA. No usage statistics are collected.

Based on the IntelliJ Community Repository Search plugin (`plugins/repository-search`, Apache 2.0), under Phantom's own id.
`org.jetbrains.idea.reposearch` stays as an alias and the content module names stay the IntelliJ ones, so plugins and
bundled modules that depend on them (Maven, Gradle, Kotlin) keep working. Phantom bundles this build instead of the IntelliJ plugin.
