# Phantom Repository Search

`dev.phantom.repository-search` · [한국어](./KOR.md)

The dependency completion service behind Maven `pom.xml` and Gradle build script completion (group, artifact and version suggestions from the local Maven index, Gradle caches and project modules), and the API Maven and Gradle use to search artifact repositories. JetBrains' Package Search service (package-search.services.jetbrains.com) is never called: its client is not in the plugin. No usage statistics are collected.

Based on the IntelliJ Community Repository Search plugin (`plugins/repository-search`, Apache 2.0), under Phantom's own id.
`org.jetbrains.idea.reposearch` stays as an alias and the content module names stay the IntelliJ ones, so plugins and
bundled modules that depend on them (Maven, Gradle, Kotlin) keep working. Phantom bundles this build instead of the IntelliJ plugin.

**0.1.1**: the hidden registry switch that pointed the Package Search client at JetBrains' maven-deps-search.labs.jb.gg
is gone; the client still fails every request before it leaves. The plugin jar lists its packaged libraries
(Package Search API client, krypto, kotlinx-document-store) and their licenses in `META-INF/THIRD-PARTY-NOTICES.txt`.

**0.1.2**: the Package Search client (`PackageSearchApiClientService`) and the libraries it needed (Package Search API
client, krypto, kotlinx-document-store) are gone. They came from JetBrains' own Maven repository, and nothing in Phantom
called the client; every request already failed before it left. The `maven.packagesearch.enabled` registry key is gone too.
