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
    // What the VCS commit hook (intellij.copyright.vcs) builds on
    bundledModule("intellij.platform.vcs.impl")
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  // IntelliJ's AssertJ extensions (com.intellij.testFramework.assertions), which the test framework only has at runtime
  testImplementation("com.jetbrains.intellij.platform:test-extensions:${providers.gradleProperty("platformTestBuild").get()}") { isTransitive = false }
  testImplementation("org.assertj:assertj-core:3.27.6")
  // IntelliJ's jimFsFixture (test-framework-junit5-jimfs) isn't published; its one file is copied into src/test/kotlin
  testImplementation("com.google.jimfs:jimfs:1.3.0")
}

tasks.test {
  useJUnitPlatform()
}

// The IDE the tests run in still bundles IntelliJ's Copyright, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("com.intellij.copyright")
}
