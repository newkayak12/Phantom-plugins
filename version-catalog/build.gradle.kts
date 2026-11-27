import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.0"

dependencies {
  intellijPlatform {
    testFramework(TestFrameworkType.Platform, providers.gradleProperty("platformTestBuild"))
  }
}
