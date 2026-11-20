import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.25.9"

// Whether the IDE the plugin builds against still bundles the Kotlin plugin (IntelliJ Community from the repository does)
val kotlinBundled = providers.gradleProperty("platformFromRepository").isPresent ||
  project.name in providers.gradleProperty("platformFromRepositoryFor").orNull.orEmpty().split(',').map { it.trim() } ||
  file("${providers.gradleProperty("phantomIde").get()}/Contents/plugins/Kotlin").isDirectory

dependencies {
  intellijPlatform {
    bundledPlugin("intellij.grid.core.plugin")
    bundledPlugin("com.intellij.java")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    // Kotlin strings in tests; the plugin itself needs only Java. Only where the IDE bundles Kotlin: Phantom from
    // 26.11.13 publishes it in its plugin repository instead, and the tests that need it are left out then (below)
    if (kotlinBundled) bundledPlugin("org.jetbrains.kotlin")
    // PsiTreeElementBase (SqlStructure): the Kotlin plugin used to bring the Structure View plugin onto the classpath
    bundledPlugin("intellij.structureView.plugin")
    bundledModule("intellij.grid")
    bundledModule("intellij.grid.core.impl")
    bundledModule("intellij.grid.impl")
    bundledModule("intellij.libraries.sshj")
  }
}

// The tests load H2 the way users' drivers are loaded: from the local Gradle cache, in its own class loader.
// Resolving it here puts it in the cache on a fresh machine (CI) without adding it to the test classpath.
val testDrivers by configurations.creating
dependencies { testDrivers("com.h2database:h2:2.3.232") }
tasks.named<Test>("test") {
  inputs.files(testDrivers)
  // these mix Java and Kotlin sources in one fixture
  if (!kotlinBundled) filter {
    excludeTestsMatching("dev.phantom.db.jvm.JpaSchemaTest")
    excludeTestsMatching("dev.phantom.db.jvm.SqlInjectionTest")
    excludeTestsMatching("dev.phantom.db.jvm.SqlDbUsagesTest")
  }
}
