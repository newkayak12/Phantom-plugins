import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.3"

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    // Kotlin tests in the tests; the plugin itself needs only UAST
    // phantom-compat.yml: Phantom publishes Kotlin as a plugin of its own; -PkotlinPlugin=<Kotlin plugin zip or dir> takes it from there
    providers.gradleProperty("kotlinPlugin").orNull?.let { localPlugin(it) } ?: bundledPlugin("org.jetbrains.kotlin")
  }
}
