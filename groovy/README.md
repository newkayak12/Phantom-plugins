# Support for Groovy
`dev.phantom.groovy` · [한국어](./KOR.md)

Groovy: highlighting, completion, inspections and quick fixes, intentions, refactorings, quick documentation, formatting,
structural search, GDSL scripts, Groovy and Gant run configurations with hot swap in the debugger, the Groovy console,
Grape dependencies, compilation in the IDE build (with the Groovy or the Eclipse compiler and stub generation), Spock and
Geb support, language injection in Groovy strings, and the Ant, Git and Copyright integrations (each on
when its plugin is there).

Based on the IntelliJ Community Groovy plugin (`plugins/groovy`, Apache 2.0), under Phantom's own id. `org.intellij.groovy`
stays as an alias and the content module names stay the IntelliJ ones, so plugins that depend on them (Gradle, Maven,
Groovy Live Templates and others) keep working. The plugin folder keeps IntelliJ's layout (`lib/groovy-jps.jar`,
`groovy-rt.jar`, `standardDsls/`, `agent/gragent.jar`, `modules/` and the rest). Phantom bundles this build instead of the
IntelliJ plugin.

**0.1.3**: renamed from Groovy Support to Phantom for Groovy, so the name starts with Phantom and names the project it supports. The plugin id and features are unchanged.

**0.1.2**: the plugin jar carries the notice of ASM, which the bundled HotSwap agent (`lib/agent/gragent.jar`) contains
(`META-INF/NOTICE-groovy`). The Sun JDK 5 copy in the test data, whose sources are marked "SUN PROPRIETARY/CONFIDENTIAL", is removed: the tests run
on the JDK they run with, plus Groovy 1.0 (`testData/lib`); five completion cases tuned to that JDK copy are left out.
