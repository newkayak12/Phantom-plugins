import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
  id("org.jetbrains.kotlin.plugin.serialization")
}

version = "0.1.3"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

// The IntelliJ build opts these sources into the experimental atomics
kotlin {
  compilerOptions.optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
}

dependencies {
  intellijPlatform {
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.spellchecker")
    // Grazie only for the spellchecker test
    bundledPlugin("tanvd.grazi")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
  // Oniguruma regular expressions for the grammars (the bundled plugin packs them into its joni module)
  implementation("org.jruby.joni:joni:2.2.7")
  // The core tests are kotlin.test on JUnit Jupiter; the IDE tests are JUnit 4, run by the vintage engine
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
  useJUnitPlatform()
}

// The grammars the plugin ships (lib/bundles in the plugin folder, where TextMateServiceImpl reads them). The IDE reads
// every shipped grammar when a project opens, so only these go in: languages Phantom has no plugin for that a Java or
// Kotlin backend project still holds. bundles/ keeps the rest because the tests read some of them.
val shippedBundles = listOf(
  // Kotlin until the Kotlin plugin is installed; config, build and repository files
  "kotlin", "sql", "docker", "dotenv", "ini", "log", "make", "bat", "powershell", "diff", "git-base",
  // JSP views and AsciiDoc (Spring REST Docs)
  "jsp", "adoc",
  // infrastructure
  "hcl", "terraform", "bazel", "cmake",
  // front end kept next to a backend
  "javascript", "typescript-basics", "css", "scss", "less",
)

tasks.prepareSandbox {
  from(layout.projectDirectory.dir("bundles")) {
    include(shippedBundles.map { "$it/**" })
    into(pluginName.map { "$it/lib/bundles" })
  }
}

// The IDE the tests run in still bundles IntelliJ's TextMate Bundles, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("org.jetbrains.plugins.textmate")
}
