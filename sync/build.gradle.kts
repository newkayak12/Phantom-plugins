// Phantom Settings Repository: Backup and Sync to a git repository the user owns, through the platform's Settings Sync
plugins {
  id("phantom-plugin")
}

version = "0.1.2"

dependencies {
  intellijPlatform {
    // the platform's Backup and Sync engine; this plugin adds a communicator for it
    bundledModule("intellij.settingsSync.core")
  }
}
