import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.3.0"

dependencies {
  intellijPlatform {
    // optional: the Java class kind icons and the Java cup on .java files (iconpack-java.xml)
    bundledPlugin("com.intellij.java")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
}
