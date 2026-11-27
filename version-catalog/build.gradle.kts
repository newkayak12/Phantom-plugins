import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.1"

dependencies {
  intellijPlatform {
    testFramework(TestFrameworkType.Platform, providers.gradleProperty("platformTestBuild"))
  }
}
