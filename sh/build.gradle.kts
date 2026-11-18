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
    bundledPlugin("org.jetbrains.plugins.terminal")
    bundledPlugin("com.intellij.copyright")
    bundledPlugin("org.intellij.plugins.markdown")
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.spellchecker")
    bundledModule("intellij.markdown")
    // Grazie only for the spellchecker test
    bundledPlugin("tanvd.grazi")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
}

// The IDE the tests run in still bundles IntelliJ's Shell Script, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("com.jetbrains.sh")
}
