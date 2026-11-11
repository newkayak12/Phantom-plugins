import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

// Above the bundled IntelliJ build (262.261027.0), so this copy replaces one a Phantom still bundles
version = "262.261027.1"

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
