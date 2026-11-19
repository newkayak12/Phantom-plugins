import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.0"

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    // Kotlin tests in the tests; the plugin itself needs only UAST
    bundledPlugin("org.jetbrains.kotlin")
  }
}
