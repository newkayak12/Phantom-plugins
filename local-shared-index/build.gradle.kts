import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.0"

// Whether the IDE the plugin builds against still bundles the Kotlin plugin (IntelliJ Community from the repository does)
val kotlinBundled = providers.gradleProperty("platformFromRepository").isPresent ||
  project.name in providers.gradleProperty("platformFromRepositoryFor").orNull.orEmpty().split(',').map { it.trim() } ||
  file("${providers.gradleProperty("phantomIde").get()}/Contents/plugins/Kotlin").isDirectory

// Uses @Internal indexing API, so an installed zip must not load on another Phantom build than the one it is tested against
intellijPlatform {
  pluginConfiguration {
    ideaVersion {
      sinceBuild = "262.261116"
      untilBuild = "262.*"
    }
  }
}

dependencies {
  intellijPlatform {
    testBundledPlugin("com.intellij.java")
    if (kotlinBundled) testBundledPlugin("org.jetbrains.kotlin")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
}

tasks.named<Test>("test") {
  if (!kotlinBundled) {
    filter {
      excludeTestsMatching("dev.phantom.localsharedindex.LocalSharedIndexAllowlistKotlinTest")
      excludeTestsMatching("dev.phantom.localsharedindex.LocalSharedIndexStubsKotlinTest")
      excludeTestsMatching("dev.phantom.localsharedindex.LocalSharedIndexKotlinTest")
    }
  }
}
