import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.3.2"

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    // Phantom Database Console: its SQL is injected by language id; the optional phantom.mybatis.db module uses its API
    localPlugin(project(":db"))
    bundledPlugin("intellij.grid.core.plugin")
    bundledModule("intellij.grid")
    bundledModule("intellij.grid.core.impl")
    bundledModule("intellij.grid.impl")
    bundledModule("intellij.libraries.sshj")
  }
}
