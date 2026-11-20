import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.3.4"

dependencies {
  intellijPlatform {
    // optional: the Java class kind icons, also on .java files (iconpack-java.xml)
    bundledPlugin("com.intellij.java")
    // optional: the Kotlin class kind icons, also on .kt files of one class (iconpack-kotlin.xml)
    // phantom-compat.yml: Phantom publishes Kotlin as a plugin of its own; -PkotlinPlugin=<Kotlin plugin zip or dir> takes it from there
    providers.gradleProperty("kotlinPlugin").orNull?.let { localPlugin(it) } ?: bundledPlugin("org.jetbrains.kotlin")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
}
