import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.0"

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.modules.json")
    bundledPlugin("tanvd.grazi")
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.json.backend")
    bundledModule("intellij.grazie.core")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
}

// The IDE the tests run in still bundles IntelliJ's TOML, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("org.toml.lang")
}
