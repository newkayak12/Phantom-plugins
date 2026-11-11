import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
  id("org.jetbrains.kotlin.plugin.compose")
}

version = "0.1.0"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

dependencies {
  intellijPlatform {
    // What the optional modules build on (each switches off without its dependency)
    bundledPlugin("com.intellij.java")
    bundledPlugin("com.intellij.modules.json")
    bundledPlugin("com.intellij.platform.images")
    bundledPlugin("org.toml.lang")
    bundledPlugin("org.jetbrains.plugins.yaml")
    bundledPlugin("org.jetbrains.plugins.textmate")
    bundledModule("intellij.platform.structureView")
    bundledModule("intellij.platform.langInjection")
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.platform.kernel.backend")
    bundledModule("intellij.platform.frontend")
    bundledModule("intellij.platform.ui.jcef")
    bundledModule("intellij.libraries.jcef")
    bundledModule("intellij.platform.compose")
    bundledModule("intellij.platform.compose.markdown")
    bundledModule("intellij.libraries.coil")
    bundledModule("intellij.libraries.compose.foundation.desktop")
    bundledModule("intellij.libraries.compose.runtime.desktop")
    bundledModule("intellij.libraries.skiko")
    bundledModule("intellij.platform.jewel.foundation")
    bundledModule("intellij.platform.jewel.ui")
    bundledModule("intellij.platform.jewel.ideLafBridge")
    bundledModule("intellij.platform.jewel.markdown.core")
    bundledModule("intellij.platform.jewel.markdown.ideLafBridgeStyling")
    bundledModule("intellij.platform.jewel.markdown.extensions.autolink")
    bundledModule("intellij.platform.jewel.markdown.extensions.frontMatter")
    bundledModule("intellij.platform.jewel.markdown.extensions.gfmAlerts")
    bundledModule("intellij.platform.jewel.markdown.extensions.gfmStrikethrough")
    bundledModule("intellij.platform.jewel.markdown.extensions.gfmTables")
    bundledModule("intellij.platform.jewel.markdown.extensions.images")
    bundledModule("intellij.json.backend")
    bundledModule("intellij.spellchecker")
    bundledModule("intellij.toml.core")
    bundledModule("intellij.yaml")
    bundledModule("intellij.yaml.backend")
    bundledModule("intellij.textmate")
    // Grazie only for the spellchecker test
    bundledPlugin("tanvd.grazi")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.Plugin.XML, providers.gradleProperty("platformTestBuild"))
  }
  // Some tests are JUnit Jupiter; the rest are JUnit 4, run by the vintage engine
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("io.kotest:kotest-assertions-core-jvm:5.9.1")
  // AssertJ soft assertions proxy through Byte Buddy
  testRuntimeOnly("net.bytebuddy:byte-buddy:1.17.7")
}

tasks.test {
  useJUnitPlatform()
  // With every Phantom plugin loaded, TextMateFallbackLanguageProvider injects TextMate into any unknown fence, as its
  // source says; this upstream test expects no injection
  filter.excludeTest("org.intellij.plugins.markdown.highlighting.MarkdownTextMateHighlightingTests", "test unknown fence has no injection and keeps code fence content")
}

// The IDE the tests run in still bundles IntelliJ's Markdown, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("org.intellij.plugins.markdown")
}
