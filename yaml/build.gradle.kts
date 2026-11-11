import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.0"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.modules.json")
    bundledModule("intellij.json")
    bundledModule("intellij.json.backend")
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.platform.searchEverywhere")
    bundledModule("intellij.platform.searchEverywhere.backend")
    bundledModule("intellij.platform.structureView")
    bundledModule("intellij.spellchecker")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
}

// The IDE the tests run in still bundles IntelliJ's YAML, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("org.jetbrains.plugins.yaml")
}
