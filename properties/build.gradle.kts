import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.1"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

// Extends AbstractRtlTest / AbstractEditorTest from IntelliJ's platform-tests, which are not published (and pull in more
// unpublished test helpers)
val testsNeedingUnpublishedTestSources = listOf("com/intellij/lang/properties/editor/PropertiesRtlTest.java")
sourceSets.test {
  java.exclude(testsNeedingUnpublishedTestSources)
}

dependencies {
  intellijPlatform {
    // What the backend modules build on
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.platform.structuralSearch")
    bundledModule("intellij.platform.structureView")
    bundledModule("intellij.spellchecker")
    // Java for the tests on Java sources, Grazie only for the spellchecker test
    bundledPlugin("com.intellij.java")
    bundledPlugin("tanvd.grazi")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    // Benchmark, for the performance tests
    testFramework(TestFrameworkType.Metrics, providers.gradleProperty("platformTestBuild"))
  }
  testImplementation("org.assertj:assertj-core:3.27.6")
  // Property-based testing, for PropertiesCodeInsightSanityTest
  testImplementation("org.jetbrains:jetCheck:0.2.2")
  // AssertJ (in the test framework's plugin loading) proxies through Byte Buddy
  testRuntimeOnly("net.bytebuddy:byte-buddy:1.17.7")
}

tasks.test {
  // The tests read testData through the VFS, outside the roots a fresh checkout allows
  systemProperty("NO_FS_ROOTS_ACCESS_CHECK", "true")
}

// The IDE the tests run in still bundles IntelliJ's Properties, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("com.intellij.properties")
}
