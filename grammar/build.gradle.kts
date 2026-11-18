import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
  id("phantom-plugin")
}

version = "0.2.0"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

// As IntelliJ compiles it (-Xjvm-default=all): the sources use @JvmDefaultWithCompatibility
kotlin {
  compilerOptions.jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
}

// The checking engine is LanguageTool (LGPL 2.1, JetBrains' build from intellij-dependencies) with English; other languages
// are downloaded at runtime. Each without its transitive dependencies, as the IntelliJ build takes them: what they need
// beyond this list (Kotlin, coroutines, fastutil, Lucene, ...) is the platform's. No JetBrains library that is not open
// source is packaged: what the IntelliJ plugin takes from JetBrains' closed Grazie libraries (rule engine, language
// detector, tokenizers, spelling engine) is replaced by code in com.intellij.grazie.nlp and com.intellij.grazie.spellcheck.
val languageToolLibraries = listOf(
  "org.jetbrains.intellij.deps.languagetool:languagetool-core:6.8.27",
  "edu.washington.cs.knowitall:openregex:1.1.1",
  "org.jetbrains.intellij.deps.languagetool:language-en:6.8.27",
  // English POS tagging and the English spelling dictionaries (en_US.dict, en_GB.dict, ...) the spellchecker uses
  "org.languagetool:english-pos-dict:0.6",
  // What LanguageTool needs that the platform doesn't ship
  "net.loomchild:segment:2.0.3",
  "org.carrot2:morfologik-stemming:2.1.9",
  "org.carrot2:morfologik-speller:2.1.9",
  "org.carrot2:morfologik-fsa-builders:2.1.9",
  "org.carrot2:morfologik-fsa:2.1.9",
  "com.carrotsearch:hppc:0.9.1",
  "tech.uom.lib:uom-lib-common:1.1",
  "tech.units:indriya:1.3",
  "javax.measure:unit-api:1.0",
  "com.hankcs:aho-corasick-double-array-trie:1.2.3",
  "com.vdurmont:emoji-java:5.1.1",
  "org.json:json:20240205",
)

// The languages the upstream tests check besides English, which a user downloads at runtime
val testLanguages = listOf(
  "org.jetbrains.intellij.deps.languagetool:language-ru:6.8.27",
  "org.jetbrains.intellij.deps.languagetool:language-zh:6.8.27",
  "com.hankcs:hanlp:portable-1.8.2",
  "org.jetbrains.intellij.deps.languagetool:language-de:6.8.27",
  "de.danielnaber:german-pos-dict:1.2.4",
  "de.danielnaber:jwordsplitter:4.7",
  "org.jetbrains.intellij.deps.languagetool:language-it:6.8.27",
  "org.jetbrains.intellij.deps.languagetool:language-ja:6.8.27",
  "com.github.lucene-gosen:lucene-gosen:6.2.1:ipadic",
  "org.jetbrains.intellij.deps.languagetool:language-uk:6.8.27",
  "ua.net.nlp:morfologik-ukrainian-lt:6.8.0",
  "org.jetbrains.intellij.deps.languagetool:language-be:6.8.27",
  "io.github.belarus:linguistics.grammardb.spell.languagetool:1.0.2",
  "org.jetbrains.intellij.deps.languagetool:language-pt:6.8.27",
  "org.languagetool:portuguese-pos-dict:1.2.0",
)

dependencies {
  intellijPlatform {
    // What the language modules build on (each switches off without its dependency)
    bundledPlugin("com.intellij.java")
    bundledPlugin("com.intellij.properties")
    bundledPlugin("com.intellij.modules.json")
    bundledPlugin("org.jetbrains.plugins.yaml")
    bundledPlugin("org.intellij.plugins.markdown")
    bundledModule("intellij.spellchecker")
    bundledModule("intellij.spellchecker.xml")
    bundledModule("intellij.platform.vcs.impl")
    bundledModule("intellij.json.backend")
    bundledModule("intellij.yaml")
    bundledModule("intellij.yaml.backend")
    bundledModule("intellij.markdown")
    bundledModule("intellij.properties.backend")
    bundledModule("intellij.properties.backend.psi")
    // Libraries the IDE ships as modules of its own (intellij.grazie.core depends on them)
    bundledModule("intellij.libraries.lucene.common")
    bundledModule("intellij.libraries.commons.text")
    bundledModule("intellij.libraries.javax.activation")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
    // Benchmark, for the performance tests
    testFramework(TestFrameworkType.Metrics, providers.gradleProperty("platformTestBuild"))
  }
  languageToolLibraries.forEach { implementation(it) { isTransitive = false } }
  testLanguages.forEach { testRuntimeOnly(it) { isTransitive = false } }
  // Some tests are JUnit Jupiter; the rest are JUnit 4, run by the vintage engine
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
  // Property-based testing, for TextContentTest
  testImplementation("org.jetbrains:jetCheck:0.2.2")
}

tasks.test {
  useJUnitPlatform()
  // The quick fix tests read testData through the VFS, which outside the IntelliJ monorepo isn't an allowed root (a fresh
  // checkout elsewhere fails with VfsRootAccessNotAllowedError)
  systemProperty("NO_FS_ROOTS_ACCESS_CHECK", "true")
  // CheckingBenchmarkTest prints its timings only when asked: -PgrazieBenchmark=true
  systemProperty("grazie.benchmark", providers.gradleProperty("grazieBenchmark").getOrElse("false"))
  // Downloads every LanguageTool bundle from JetBrains' servers to verify the checksums hardcoded in the descriptors
  // (upstream skips it on TeamCity for the same reason): network, not plugin behaviour
  filter.excludeTestsMatching("com.intellij.grazie.remote.LanguageToolBundleInfoTest")
}

// The IDE the tests run in still bundles IntelliJ's Natural Languages, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("tanvd.grazi")
}
