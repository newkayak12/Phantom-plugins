plugins {
  id("phantom-plugin")
  // The IntelliJ sources use @Serializable
  id("org.jetbrains.kotlin.plugin.serialization")
}

version = "0.1.0"

// As the IntelliJ module is compiled: interfaces annotated @JvmDefaultWithCompatibility need this mode
kotlin {
  compilerOptions.freeCompilerArgs.add("-jvm-default=no-compatibility")
  // RunDashboardServiceViewContributor.java calls an internal member by its mangled name, which carries the IntelliJ module name
  compilerOptions.moduleName = "intellij.platform.execution.dashboard"
}

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
}

dependencies {
  intellijPlatform {
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.platform.kernel.backend")
    bundledModule("intellij.platform.rpc.backend")
    bundledModule("intellij.platform.frontend")
    bundledModule("intellij.platform.statistics")
    // The Services tool window's navigation bar (ServiceViewNavBarPanel) builds on the Navbar plugin's frontend
    bundledPlugin("intellij.navbar.plugin")
  }
}

// The IDE the tests run in still bundles the IntelliJ plugin, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("intellij.execution.serviceView.plugin")
}
