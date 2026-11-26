plugins {
  id("phantom-plugin")
}

version = "0.1.1"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
}

dependencies {
  intellijPlatform {
    bundledModule("intellij.platform.statistics")
  }
}

// The IDE the tests run in still bundles IntelliJ's Navbar, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("intellij.navbar.plugin")
}
