plugins {
  id("phantom-plugin")
  id("org.jetbrains.kotlin.plugin.serialization")
}

version = "0.1.0"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
}

dependencies {
  intellijPlatform {
    // What the grid modules build on that is not part of the core platform jars
    bundledModule("intellij.libraries.automaton")
    bundledModule("intellij.libraries.fastutil")
    bundledModule("intellij.libraries.kotlinx.collections.immutable")
    bundledModule("intellij.libraries.kotlinx.html")
    bundledModule("intellij.libraries.kotlinx.serialization.core")
    bundledModule("intellij.libraries.microba")
    // TableResultPanelUIDataRule points the Structure tool window at the grid's file (StructureViewWrapperImpl)
    bundledModule("intellij.platform.structureView")
  }
}

// The IDE the tests run in still bundles IntelliJ's Grid Core, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("intellij.grid.core.plugin")
}

// GridRowHeader draws its grid lines with sun.swing.SwingUtilities2, as IntelliJ compiles it; the IDE opens the package
// at run time (--add-opens=java.desktop/sun.swing=ALL-UNNAMED in its JVM options)
tasks.compileJava {
  options.compilerArgs.add("--add-exports=java.desktop/sun.swing=ALL-UNNAMED")
}
