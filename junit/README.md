# Phantom for JUnit
`dev.phantom.junit` · [한국어](./KOR.md)

JUnit 3, 4, 5 and 6 for Java: run configurations for a class, method, package, directory, pattern, tags, category or
unique id, run from the gutter or a context, with the test tree, rerun of failed tests, forked runs per module or class
and debugging; JUnit test generation and navigation between a test and its subject; references from `@MethodSource`,
`@FieldSource`, `@EnumSource` and the like to their targets; and inspections and quick fixes such as malformed test
declarations, migrating JUnit 4 to JUnit 5 and converting assertions.

Based on the IntelliJ Community JUnit plugin (`plugins/junit`, `plugins/junit_rt`, `plugins/junit5_rt` and
`plugins/junit6_rt`, Apache 2.0), under Phantom's own id. `JUnit` stays as an alias, so plugins that depend on it (Code
Coverage, Groovy, Kotlin) keep working. Phantom bundles this build instead of the IntelliJ plugin.
