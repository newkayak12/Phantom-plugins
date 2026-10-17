// Shared setup for every plugin in this repository: Kotlin, and the installed Phantom IDEA as the platform.
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.intellij.platform")
}

group = "dev.phantom"

kotlin {
  jvmToolchain(25)
  compilerOptions.jvmTarget = JvmTarget.JVM_25
}

java {
  targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
  intellijPlatform {
    // Plugins compile against an installed Phantom IDEA, not a downloaded IntelliJ
    local(providers.gradleProperty("phantomIde"))
    // Phantom is a fork of this IntelliJ build, whose test framework is published
    testFramework(TestFrameworkType.Platform, providers.gradleProperty("platformTestBuild"))
  }
  testImplementation("junit:junit:4.13.2")
  testRuntimeOnly("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
  buildSearchableOptions = false
  instrumentCode = false
  pluginConfiguration {
    ideaVersion {
      sinceBuild = providers.gradleProperty("sinceBuild")
      untilBuild = providers.gradleProperty("untilBuild")
    }
  }
}
