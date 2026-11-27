import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.4"

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    // Kotlin entities in tests; phantom-compat.yml: -PkotlinPlugin=<Kotlin plugin zip or dir> takes Kotlin from Phantom's own plugin
    providers.gradleProperty("kotlinPlugin").orNull?.let { localPlugin(it) } ?: bundledPlugin("org.jetbrains.kotlin")
  }
}
