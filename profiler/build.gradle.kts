import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.2.1"

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    bundledPlugin("com.intellij.gradle")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
}
