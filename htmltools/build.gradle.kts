import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.1"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.platform.images")
    // Spellchecker and Grazie only for the highlighting test
    bundledModule("intellij.spellchecker")
    bundledPlugin("tanvd.grazi")
    bundledModule("intellij.grazie.core")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.Plugin.XML, providers.gradleProperty("platformTestBuild"))
    // Benchmark for HtmlHighlightingPerformanceTest
    testFramework(TestFrameworkType.Metrics, providers.gradleProperty("platformTestBuild"))
  }
}

tasks.test {
  // The tests read testData through the VFS, which the test IDE does not allow outside its roots on a CI checkout
  systemProperty("NO_FS_ROOTS_ACCESS_CHECK", "true")
}

// The IDE the tests run in still bundles IntelliJ's HTML Tools, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("HtmlTools")
}
