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
