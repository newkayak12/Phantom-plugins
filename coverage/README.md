# Code Coverage for Java

`Coverage` · [한국어](./KOR.md)

Run with Coverage for Java run configurations, with the IntelliJ or the JaCoCo runner: the Coverage tool window, line and branch marks in the editor and the Project view, HTML reports, and imported `.ic`, `.exec` and LCOV reports.

The IntelliJ Community Code Coverage for Java plugin (`plugins/coverage`, Apache 2.0), built here so it updates without a new Phantom build. The plugin id
and content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them keep working, and its
version (`262.261111.x`) is above the bundled build: installing it replaces a copy your Phantom still bundles.

The JUnit and TestNG listeners that run in your test JVM are `java-coverage-rt.jar`, Java 8 bytecode in a jar of their own; JaCoCo and the IntelliJ coverage report come from Maven.
