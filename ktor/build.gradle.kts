import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.2"

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    bundledPlugin("org.jetbrains.kotlin")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
}
