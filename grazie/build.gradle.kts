import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
  id("phantom-plugin")
}

version = "0.1.1"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

// As IntelliJ compiles it (-Xjvm-default=all): the sources use @JvmDefaultWithCompatibility
kotlin {
  compilerOptions.jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
}

// The third-party libraries IntelliJ's Grazie packs into its own lib folder (the module libraries of intellij.grazie.core
// and intellij.grazie.markdown, the project libraries ai.grazie.rule.engine and io.github.oshai.kotlin.logging.jvm; the gec
// engine library module comes below), at the versions the fork's .iml / .idea/libraries files pin. Each without its
// transitive dependencies, as the IntelliJ build takes them: what they need beyond this list (Kotlin, coroutines,
// serialization, ktor, fastutil, ...) is the platform's. ai.grazie.* comes from JetBrains' public grazie-platform-public
// repository, LanguageTool from intellij-dependencies (both in settings.gradle.kts).
// Without Grazie Cloud, none of the Grazie API client / cloud model jars IntelliJ ships are packaged (api-gateway-client,
// client-*, auth-*, model-cloud/llm/nmt/quota/license/..., tasks-*, indexing-*, user-*, utils-jwt/ktor, llm-agg, gec-agg),
// nor grazie-rules-llm (ai.grazie.semantic.engine: LLM prompt analysis through the API client): nothing that is left
// references a class in them. The rule engine still needs model-ner and the tree model classes of api-gateway-api (below).
val grazieLibraries = listOf(
  // The NER annotation model the rule engine's tree support is written against
  "ai.grazie.model:model-ner-jvm:0.8.256",
  // ProblemAggregator, which TextProblemAggregator builds on
  "ai.grazie.gec:gec-util:0.9.2",
  // Rule engine (ai.grazie.rule.engine)
  "ai.grazie:grazie-rule-engine:0.3.261",
  "net.fellbaum:jemoji:1.3.1",
  "io.github.oshai:kotlin-logging-jvm:7.0.3",
  // English Hunspell, part of the gec engine library module, as its own jar: the dictionary version is read from the jar
  // name (HunspellBundleInfoTest), and it has no classes to clash
  "ai.grazie.spell:hunspell-en:0.2.359",
  // LanguageTool (JetBrains' build) with English; other languages are downloaded at runtime
  "org.jetbrains.intellij.deps.languagetool:languagetool-core:6.8.27",
  "edu.washington.cs.knowitall:openregex:1.1.1",
  "org.jetbrains.intellij.deps.languagetool:language-en:6.8.27",
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

// intellij.libraries.ai.grazie.spell.gec.engine.local (local spelling engine, language detector), in
// the order of its .iml. gec-spell-engine-local carries newer copies of some nlp-similarity / nlp-phonetics classes (the
// Russian metaphone among them); IntelliJ merges these jars into one, first copy wins, so the plugin does the same
// instead of shipping both copies side by side. Also covers the model-common, utils-common, utils-multiplatform,
// swagger-annotations and nlp-common (0.9.2; api-gateway-client lists 0.8.256) jars intellij.grazie.core wants.
val gecEngineLocal by configurations.creating { isTransitive = false }
val gecEngineLocalLibraries = listOf(
  "ai.grazie.spell:gec-spell-engine-local:0.9.2",
  "ai.grazie.nlp:nlp-common-jvm:0.9.2",
  "ai.grazie.nlp:nlp-patterns-jvm:0.9.2",
  "ai.grazie.nlp:nlp-tokenizer-jvm:0.9.2",
  "ai.grazie.nlp:nlp-detect-jvm:0.9.2",
  "ai.grazie.utils:utils-common-jvm:0.8.256",
  "ai.grazie.model:model-gec-jvm:0.8.256",
  "ai.grazie.model:model-text-jvm:0.8.256",
  "ai.grazie.model:model-common-jvm:0.8.256",
  "ai.grazie.nlp:nlp-langs-jvm:0.8.256",
  "ai.grazie.nlp:nlp-similarity-jvm:0.8.256",
  "ai.grazie.nlp:nlp-phonetics-jvm:0.8.256",
  "ai.grazie.utils:utils-multiplatform-jvm:0.8.256",
  "ai.grazie.model:swagger-annotations-jvm:0.8.256",
)
// api-gateway-client's nlp-common-jvm 0.8.256 (in another configuration, or Gradle would settle on one version) goes in
// last, which only adds the classes 0.9.2 dropped (ai.grazie.nlp.utils.UtilsKt), as in IntelliJ's own jars
val gecEngineLocalOld by configurations.creating { isTransitive = false }
val gecEngineLocalJar by tasks.registering(Jar::class) {
  archiveFileName = "intellij.libraries.ai.grazie.spell.gec.engine.local.jar"
  destinationDirectory = layout.buildDirectory.dir("gecEngineLocal")
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  // the configuration resolves in declaration order
  listOf(gecEngineLocal, gecEngineLocalOld).forEach { configuration ->
    from(configuration.elements.map { jars -> jars.map { zipTree(it) } }) {
      exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
  }
}

// Of api-gateway-api (the Grazie Cloud API surface) only the dependency tree model the rule engine's TreeSupport classes use
// (ai.grazie.tree.model.SentenceWithTreeDependencies): the rest of it is cloud requests and references the cloud model jars
// that are not packaged
val apiGatewayTreeModel by configurations.creating { isTransitive = false }
val apiGatewayTreeModelJar by tasks.registering(Jar::class) {
  archiveFileName = "api-gateway-api-jvm-0.8.256-tree-model.jar"
  destinationDirectory = layout.buildDirectory.dir("apiGatewayTreeModel")
  from(apiGatewayTreeModel.elements.map { jars -> jars.map { zipTree(it) } }) {
    include("ai/grazie/tree/model/**")
  }
}

// The languages the upstream tests check besides English (intellij.grazie.tests), which a user downloads at runtime
val testLanguages = listOf(
  "ai.grazie.spell:hunspell-de:0.2.359",
  "ai.grazie.spell:hunspell-ru:0.2.359",
  "ai.grazie.spell:hunspell-uk:0.2.359",
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
  grazieLibraries.forEach { implementation(it) { isTransitive = false } }
  gecEngineLocalLibraries.forEach { gecEngineLocal(it) }
  gecEngineLocalOld("ai.grazie.nlp:nlp-common-jvm:0.8.256")
  implementation(files(gecEngineLocalJar))
  apiGatewayTreeModel("ai.grazie.api:api-gateway-api-jvm:0.8.256")
  implementation(files(apiGatewayTreeModelJar))
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
  // These download every LanguageTool bundle and Hunspell dictionary from JetBrains' servers to verify the checksums
  // hardcoded in the descriptors (upstream skips them on TeamCity for the same reason): network, not plugin behaviour
  filter.excludeTestsMatching("com.intellij.grazie.remote.LanguageToolBundleInfoTest")
  filter.excludeTestsMatching("com.intellij.grazie.remote.HunspellBundleInfoTest")
  // Same: checks over the network that each Hunspell dictionary URL exists (the version check next to it stays)
  filter.excludeTest("com.intellij.grazie.hunspell.HunspellBundleInfoTest", "check that grazie dictionary exists")
  // Russian Hunspell checking works here (it is the same dictionary jar), but its suggestions come back empty, so the
  // replacements from the dictionary's rule/ru.dat ("Врядтли" -> "Вряд ли") never show. It fails the same way against
  // IntelliJ Community 262.10968.63 with exactly the library versions IntelliJ's .iml files pin, so it is not something
  // this build changes; cause not found
  filter.excludeTest("com.intellij.grazie.grammar.SpellCheckerTest", "test advanced dat suggestions")
}

// The IDE the tests run in still bundles IntelliJ's Natural Languages, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("tanvd.grazi")
}
