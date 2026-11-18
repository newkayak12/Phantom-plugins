# TestNG Support

`dev.phantom.testng` · [한국어](./KOR.md)

TestNG for Java: run configurations for a class, method, package, group, pattern or suite file, run from the gutter or a
context, with the test tree, rerun, forked runs per module and debugging; TestNG test generation and file templates;
references from `dataProvider`, `dependsOnMethods`, `groups` and `testng.xml` to their targets; and inspections and
quick fixes such as converting JUnit tests to TestNG. TestNG itself (`testng.jar`) ships in the plugin, so a conversion can
add it to a module that doesn't have it yet.

Based on the IntelliJ Community TestNG plugin (`plugins/testng` and `plugins/testng_rt`, Apache 2.0), under Phantom's own
id. `TestNG-J` stays as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that
depend on them keep working. Phantom bundles this build instead of the IntelliJ plugin.

**0.1.1**: The plugin jar lists what `testng.jar` is made of (TestNG and JCommander, Apache 2.0; jQuery, MIT) in
`META-INF/THIRD-PARTY-NOTICES.txt` and carries the MIT text in `META-INF/licenses/`.
