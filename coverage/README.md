# Phantom Code Coverage for Java

`dev.phantom.coverage` · [한국어](./KOR.md)

Run with Coverage for Java run configurations, with the IntelliJ or the JaCoCo runner: the Coverage tool window, line and branch marks in the editor and the Project view, HTML reports, and imported `.ic`, `.exec` and LCOV reports.

Based on the IntelliJ Community Code Coverage for Java plugin (`plugins/coverage`, Apache 2.0), under Phantom's own id. `Coverage` stays
as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.

The JUnit and TestNG listeners that run in your test JVM are `java-coverage-rt.jar`, Java 8 bytecode in a jar of their own; JaCoCo and the IntelliJ coverage report come from Maven.
