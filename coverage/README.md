# Phantom Code Coverage for Java

`dev.phantom.coverage` · [한국어](./KOR.md)

Run with Coverage for Java run configurations, with the IntelliJ or the JaCoCo runner: the Coverage tool window, line and branch marks in the editor and the Project view, HTML reports, and imported `.ic`, `.exec` and LCOV reports.

Based on the IntelliJ Community Code Coverage for Java plugin (`plugins/coverage`, Apache 2.0), under Phantom's own id. `Coverage` stays
as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.

The JUnit and TestNG listeners that run in your test JVM are `java-coverage-rt.jar`, Java 8 bytecode in a jar of their own; JaCoCo and the IntelliJ coverage report come from Maven.

**0.1.2**: The plugin jar lists its packaged libraries in `META-INF/THIRD-PARTY-NOTICES.txt` (JaCoCo, EPL 2.0; ASM,
BSD 3-Clause; FreeMarker and coverage-report, Apache 2.0) and carries the EPL 2.0 and BSD texts in `META-INF/licenses/`.
NOTICE says where JaCoCo's sources are.

**0.1.4**: besides the plugin's own release, the NOTICE in the plugin jar names the public phantom-libs release that
carries the plugin as a place with the JaCoCo sources, and takes written-offer requests through the issues of the public
Phantom repository.

**0.1.3**: `java-coverage-rt.jar` no longer carries a copy of `plugin.xml`, so `lib/` holds a single plugin descriptor and the Plugin
Verifier accepts the plugin.
