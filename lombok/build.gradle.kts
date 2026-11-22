import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.1"

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    bundledModule("intellij.platform.structureView")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
  testImplementation("org.mockito:mockito-core:5.20.0")
}

// The IDE the tests run in still bundles IntelliJ's Lombok, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("Lombook Plugin")
}

// IntelliJ's mock JDKs for the light Java test projects (see MockJdks.kt)
useMockJdks()
