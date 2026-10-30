plugins {
  id("phantom-plugin")
}

version = "0.1.1"

dependencies {
  intellijPlatform {
    // the platform's ignore language (com.intellij.openapi.vcs.changes.ignore), which .gitignore also uses
    bundledModule("intellij.platform.vcs.impl")
  }
}
