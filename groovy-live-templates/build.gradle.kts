plugins {
  id("phantom-plugin")
}

version = "0.1.1"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
}

dependencies {
  intellijPlatform {
    // Groovy itself (intellij.libraries.groovy) comes from Groovy Scripting Support, which this plugin depends on
    localPlugin(project(":groovy-scripting"))
  }
  // which localPlugin doesn't put on the compile classpath: the same Groovy, compile only
  compileOnly("org.codehaus.groovy:groovy:3.0.25") { isTransitive = false }
}

// The IDE the tests run in still bundles IntelliJ's Groovy Live Templates and Groovy Scripting, which claim the ids these
// plugins keep as aliases
tasks.prepareTestSandbox {
  disabledPlugins.addAll("org.intellij.groovy.live.templates", "com.intellij.groovy.scripting")
}
