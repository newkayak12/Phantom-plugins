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

// IntelliJ puts testData on the test classpath (a test resource root); the JSON Schema 2019/2020 tests load their schemas
// from there
sourceSets.test {
  resources.srcDir("testData")
  resources.include("jsonSchema/newerFormats/**")
}

dependencies {
  intellijPlatform {
    // What the backend, regexp and structure view modules build on
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.platform.structureView")
    bundledModule("intellij.spellchecker")
    bundledModule("intellij.regexp")
    // Grazie only for the spellchecker test
    bundledPlugin("tanvd.grazi")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
    // Benchmark, for the performance tests
    testFramework(TestFrameworkType.Metrics, providers.gradleProperty("platformTestBuild"))
  }
  // The networknt validator and its regex engine: IntelliJ merges them into the networknt.wrapper module jar, a plugin
  // library, not a platform one. Jackson 3 (which IntelliJ also merged there, at 3.0.3) comes from the platform's lib/.
  implementation("com.networknt:json-schema-validator:3.0.0-jetbrains.10") { isTransitive = false }
  implementation("org.jruby.joni:joni:2.2.7") { isTransitive = false }
  implementation("org.jruby.jcodings:jcodings:1.0.64") { isTransitive = false }

  // Some tests are JUnit Jupiter; the rest are JUnit 4, run by the vintage engine
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("org.assertj:assertj-core:3.27.6")
  // IntelliJ's AssertJ extensions (com.intellij.testFramework.assertions), which the test framework only has at runtime
  testImplementation("com.jetbrains.intellij.platform:test-extensions:${providers.gradleProperty("platformTestBuild").get()}") { isTransitive = false }
  // AssertJ soft assertions proxy through Byte Buddy
  testRuntimeOnly("net.bytebuddy:byte-buddy:1.17.7")
}

tasks.test {
  useJUnitPlatform()
  // Written for a second IntelliJ run with -Djson.lazy.parsing=false; in the default (lazy) mode they throw
  // AssumptionViolatedException from a JUnit 3 test case, which Gradle reports as an error rather than a skip
  filter.excludeTestsMatching("com.intellij.json.JsonNonLazyParsingTest")
  filter.excludeTest("com.intellij.json.intentions.JsonSortPropertiesIntentionTest", "testSortMalformedJson_non_lazy")
  // The tests read testData through the VFS, outside the roots a fresh checkout allows
  systemProperty("NO_FS_ROOTS_ACCESS_CHECK", "true")
}

// The IDE the tests run in still bundles IntelliJ's JSON, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("com.intellij.modules.json")
}
