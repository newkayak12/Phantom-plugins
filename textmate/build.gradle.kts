import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
  id("org.jetbrains.kotlin.plugin.serialization")
}

// Above the bundled IntelliJ build (262.261027.0), so this copy replaces one a Phantom still bundles
version = "262.261111.1"

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

// The grammars (lib/bundles in the plugin folder, where TextMateServiceImpl reads them)
tasks.prepareSandbox {
  from(layout.projectDirectory.dir("bundles")) {
    into(pluginName.map { "$it/lib/bundles" })
  }
}
