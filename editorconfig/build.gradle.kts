import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
  id("org.jetbrains.kotlin.plugin.serialization")
}

version = "0.1.3"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

dependencies {
  intellijPlatform {
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.platform.frontend")
    bundledModule("intellij.spellchecker")
    bundledModule("intellij.platform.structureView")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    // Benchmark, for the inspections performance test
    testFramework(TestFrameworkType.Metrics, providers.gradleProperty("platformTestBuild"))
  }
  // The .editorconfig parser and resolver (the bundled plugin packs it into its backend module jar)
  implementation("org.ec4j.core:ec4j-core:0.3.0")
}

// The tests open testData/ through the VFS, which the test IDE does not allow on a CI checkout
tasks.test {
  systemProperty("NO_FS_ROOTS_ACCESS_CHECK", "true")
}

// The IDE the tests run in still bundles IntelliJ's EditorConfig, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("org.editorconfig.editorconfigjetbrains")
}
