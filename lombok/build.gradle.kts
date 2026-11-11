import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

// Above the bundled IntelliJ build (262.261027.0), so this copy replaces one a Phantom still bundles
version = "262.261027.1"

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    bundledModule("intellij.platform.structureView")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
  testImplementation("org.mockito:mockito-core:5.20.0")
}
