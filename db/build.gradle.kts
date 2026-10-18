import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.9.0"

dependencies {
  intellijPlatform {
    bundledPlugin("intellij.grid.core.plugin")
    bundledPlugin("com.intellij.java")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    // Kotlin strings in tests; the plugin itself needs only Java
    bundledPlugin("org.jetbrains.kotlin")
    bundledModule("intellij.grid")
    bundledModule("intellij.grid.core.impl")
    bundledModule("intellij.grid.impl")
  }
}
