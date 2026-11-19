plugins {
  id("phantom-plugin")
}

version = "0.1.1"

// No sources: plugin.xml and the descriptors of its three library modules. The libraries are the versions the IntelliJ
// plugin ships (libraries/groovy, libraries/groovy/jsr223 and libraries/ivy in IntelliJ, without transitive dependencies
// there either), packaged in lib/.
dependencies {
  implementation("org.codehaus.groovy:groovy:3.0.25") { isTransitive = false }
  implementation("org.codehaus.groovy:groovy-jsr223:3.0.25") { isTransitive = false }
  implementation("org.apache.ivy:ivy:2.5.2") { isTransitive = false }
}

// The IDE the tests run in still bundles IntelliJ's Groovy Scripting, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("com.intellij.groovy.scripting")
}
