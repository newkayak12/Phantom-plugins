import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.1"

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    // phantom-compat.yml: Phantom publishes Kotlin as a plugin of its own; -PkotlinPlugin=<Kotlin plugin zip or dir> takes it from there
    providers.gradleProperty("kotlinPlugin").orNull?.let { localPlugin(it) } ?: bundledPlugin("org.jetbrains.kotlin")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
}
