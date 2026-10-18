plugins {
  id("phantom-plugin")
}

version = "0.2.0"

dependencies {
  intellijPlatform {
    bundledPlugin("intellij.grid.core.plugin")
    bundledModule("intellij.grid")
    bundledModule("intellij.grid.core.impl")
    bundledModule("intellij.grid.impl")
  }
}
