import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.5"

// The IntelliJ sources (and the Grammar-Kit parser) keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.modules.json")
    bundledPlugin("org.jetbrains.plugins.yaml")
    bundledPlugin("org.intellij.plugins.markdown")
    bundledModule("intellij.json.backend")
    bundledModule("intellij.yaml")
    bundledModule("intellij.spellchecker")
    bundledModule("intellij.platform.ui.jcef")
    bundledModule("intellij.libraries.jcef")
    bundledModule("intellij.platform.builtInServer.impl")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
  // The preview tests are JUnit Jupiter; the rest are JUnit 4, run by the vintage engine
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
  // JCEF in the headless test IDE for the preview tests: as registry keys (@WithJcef) they come too late when a
  // preview test is the first to start the IDE
  systemProperty("ide.browser.jcef.headless.enabled", "true")
  systemProperty("ide.browser.jcef.testMode.enabled", "true")
  // the expected files are read from testData, which the test IDE's VFS does not allow on a CI checkout
  systemProperty("NO_FS_ROOTS_ACCESS_CHECK", "true")
  // reads the Mermaid documentation examples, which the upstream build extracts first (examples/extractor)
  filter.excludeTestsMatching("com.phantom.mermaid.lang.preview.OfficialExamplesParsingTest")
}

// Every IDE this is tested on still bundles the JetBrains copy, which shares this plugin's language and file type and
// keeps it from loading (incompatible-with): switch it off in the test sandbox
tasks.prepareTestSandbox {
  doLast {
    sandboxConfigDirectory.get().file("disabled_plugins.txt").asFile.apply {
      parentFile.mkdirs()
      writeText("com.intellij.mermaid\n")
    }
  }
}
