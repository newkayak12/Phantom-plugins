# Support for Gradle (Java)
`dev.phantom.gradle-java` · [한국어](./KOR.md)

Java support for Gradle projects, on top of Support for Gradle: importing Java source sets, dependencies and
compiler settings, running and debugging applications and tests through Gradle, the Gradle Groovy DSL (completion,
navigation, inspections in `build.gradle`), version catalog navigation, the New Project wizard for Gradle, Maven
repository and dependency integration, coverage for Gradle test runs, and the JPS builder that processes Gradle
resources when the IDE builds the project (`gradle-jps.jar`, with Apache Ant 1.10.15 inside, as in IntelliJ).

Forked from the Gradle for Java plugin of IntelliJ IDEA Community 262.10968.63 (`plugins/gradle/java` and
`plugins/gradle-maven`, Apache 2.0) and developed here as Phantom's own code, under Phantom's own id.
`org.jetbrains.plugins.gradle` stays as an alias and the content module names stay the IntelliJ ones, so plugins and
bundled modules that depend on them keep working. Gradle features are the same as in the IntelliJ plugin, without
usage statistics; the ML completion ranking features for Gradle Groovy scripts are not included (Phantom does not
bundle ML completion ranking).
