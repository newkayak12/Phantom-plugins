import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.25.1"

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
    bundledModule("intellij.libraries.sshj")
  }
}

// The tests load H2 the way users' drivers are loaded: from the local Gradle cache, in its own class loader.
// Resolving it here puts it in the cache on a fresh machine (CI) without adding it to the test classpath.
val testDrivers by configurations.creating
dependencies { testDrivers("com.h2database:h2:2.3.232") }
tasks.named<Test>("test") {
  inputs.files(testDrivers)
}
