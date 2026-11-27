import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.1"

dependencies {
  intellijPlatform {
    bundledPlugin("org.jetbrains.plugins.yaml")
    bundledPlugin("com.intellij.modules.json")
    bundledPlugin("com.intellij.java")
    bundledModule("intellij.json.backend")
    // tests only: the YAML inspection that checks a file against its JSON schema
    bundledModule("intellij.yaml.backend")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
}
