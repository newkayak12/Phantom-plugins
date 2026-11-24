import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.0"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    bundledPlugin("com.intellij.properties")
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.properties.backend")
    bundledModule("intellij.properties.backend.psi")
    // Grazie only for the spellchecker tests
    bundledPlugin("tanvd.grazi")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    // Benchmark, for PropertiesPerformanceTest
    testFramework(TestFrameworkType.Metrics, providers.gradleProperty("platformTestBuild"))
  }
  // AssertJ (in the test framework's plugin loading) proxies through Byte Buddy
  testRuntimeOnly("net.bytebuddy:byte-buddy:1.17.7")
}

tasks.test {
  // The tests read testData through the VFS, outside the roots a fresh checkout allows
  systemProperty("NO_FS_ROOTS_ACCESS_CHECK", "true")
  // IntelliJ 262.10968.63's Properties (-PplatformFromRepository, as in CI) offers no Unescape fix; Phantom's does, and
  // these pass against the installed Phantom
  if (providers.gradleProperty("platformFromRepository").isPresent) {
    filter {
      excludeTest("com.intellij.lang.properties.PropertiesHighlightingTest", "testInvalidEscapeSequenceFix")
      excludeTest("com.intellij.lang.properties.PropertiesHighlightingTest", "testInvalidEscapeSequenceFixDumbMode")
    }
  }
}

// The IDE the tests run in still bundles IntelliJ's Java Internationalization, which claims the id this plugin keeps as
// an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("com.intellij.java-i18n")
}

// IntelliJ's mock JDKs for the light Java test projects (see MockJdks.kt)
useMockJdks()
