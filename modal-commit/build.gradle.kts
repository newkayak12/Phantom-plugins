plugins {
  id("phantom-plugin")
}

version = "0.1.2"

dependencies {
  intellijPlatform {
    // CommitModeManager, VcsStartupActivity (vcs.impl) and DvcsCommitModeProvider (vcs.dvcs.impl)
    bundledModule("intellij.platform.vcs.impl")
    bundledModule("intellij.platform.vcs.dvcs.impl")
  }
}
