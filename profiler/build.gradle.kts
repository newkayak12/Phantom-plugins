import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.2.3"

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    bundledPlugin("com.intellij.gradle")
    // Phantom's Gradle plugin depends on the alias com.intellij.groovy.scripting (Phantom Groovy Scripting), whose artifact
    // the Gradle plugin creates only when it is asked for directly
    if (!providers.gradleProperty("platformFromRepository").isPresent) bundledPlugin("com.intellij.groovy.scripting")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
}
