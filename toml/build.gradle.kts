import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

// Above the bundled IntelliJ build (262.261027.0), so this copy replaces one a Phantom still bundles
version = "262.261027.1"

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.modules.json")
    bundledPlugin("tanvd.grazi")
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.json.backend")
    bundledModule("intellij.grazie.core")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
}
