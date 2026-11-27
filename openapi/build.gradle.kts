import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.2.5"

dependencies {
  intellijPlatform {
    bundledPlugin("org.jetbrains.plugins.yaml")
    bundledPlugin("com.intellij.modules.json")
    // tests only: a Java controller stands in for an endpoint
    bundledPlugin("com.intellij.java")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
}
