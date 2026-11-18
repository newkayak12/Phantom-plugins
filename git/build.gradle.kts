import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
  // The IntelliJ sources use @Serializable
  id("org.jetbrains.kotlin.plugin.serialization")
}

version = "0.1.0"

// As the IntelliJ module is compiled: interfaces annotated @JvmDefaultWithCompatibility need this mode
kotlin {
  compilerOptions.freeCompilerArgs.add("-jvm-default=no-compatibility")
  compilerOptions.optIn.add("kotlin.RequiresOptIn")
}

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

dependencies {
  intellijPlatform {
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.platform.kernel.backend")
    bundledModule("intellij.platform.rpc.backend")
    bundledModule("intellij.platform.frontend")
    bundledModule("intellij.platform.searchEverywhere")
    bundledModule("intellij.platform.searchEverywhere.frontend")
    bundledModule("intellij.platform.ide.impl.wsl")
    bundledModule("intellij.platform.lvcs.impl")
    bundledModule("intellij.platform.collaborationTools")
    bundledModule("intellij.platform.vcs.dvcs")
    bundledModule("intellij.platform.vcs.dvcs.impl")
    bundledModule("intellij.platform.vcs.dvcs.impl.shared")
    bundledModule("intellij.platform.vcs.impl")
    bundledModule("intellij.platform.vcs.impl.shared")
    bundledModule("intellij.platform.vcs.log")
    bundledModule("intellij.platform.vcs.log.impl")
    bundledModule("intellij.platform.vcs.log.graph")
    bundledModule("intellij.platform.vcs.log.graph.impl")
    // What intellij.vcs.git.coverage and intellij.vcs.git.terminal build on (each switches off without it)
    bundledModule("intellij.platform.coverage")
    bundledPlugin("org.jetbrains.plugins.terminal")
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
}

// The IDE the tests run in still bundles IntelliJ's Git, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("Git4Idea")
}
