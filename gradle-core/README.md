# Support for Gradle
`dev.phantom.gradle-core` · [한국어](./KOR.md)

Gradle in the IDE: create and open Gradle projects, sync the project structure with the Gradle model, the Gradle tool
window, Gradle run configurations and task execution, Gradle daemon management, and editing help for Gradle files
(dependency completion in build scripts and version catalogs, `gradle.properties`). Language support builds on it:
Support for Gradle (Java) adds Java projects, the Gradle Groovy DSL and the JPS build.

Forked from the Gradle plugin of IntelliJ IDEA Community 262.10968.63 (`plugins/gradle`, Apache 2.0) and developed
here as Phantom's own code, under Phantom's own id. `com.intellij.gradle` stays as an alias and the content module
names stay the IntelliJ ones, so plugins and bundled modules that depend on them keep working. Gradle features are
the same as in the IntelliJ plugin, with Phantom's changes: no usage statistics, no automatic download of the
Gradle/JVM compatibility list from JetBrains, a Gradle JVM the project's Gradle supports when the project SDK is not
one, and Groovy for the tooling proxy taken from Groovy Scripting. Kotlin build script completion is not included;
it belongs with the Kotlin plugin, which Phantom does not bundle.

The plugin packs the Gradle API and Tooling API (`gradle-api-9.6.0.jar`) and the tooling extension jars the IDE hands
to the Gradle daemon (`gradle-tooling-extension-api.jar`, `gradle-tooling-extension-impl.jar`,
`gradle-tooling-proxy.jar`, compiled for Java 8), as IntelliJ lays them out.
