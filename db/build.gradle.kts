import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.25.15"

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

// The tests load H2 the way users' drivers are loaded: from the local caches, in its own class loader. They run
// against a throwaway home whose ~/.m2 holds only this H2 jar and its POM (a user-defined driver resolves its
// dependencies from the POM), so the result does not depend on what the machine's Gradle cache holds; on CI that cache
// is restored from a shared entry, and the two H2 tests failed there with it.
val testDrivers by configurations.creating
dependencies {
  testDrivers("com.h2database:h2:2.3.232")
  testDrivers("com.h2database:h2:2.3.232@pom")
}
tasks.named<Test>("test") {
  inputs.files(testDrivers)
  val testHome = layout.buildDirectory.dir("test-home").map { it.asFile.absolutePath }
  val drivers: FileCollection = files(testDrivers)
  doFirst {
    val h2 = File(testHome.get(), ".m2/repository/com/h2database/h2/2.3.232")
    h2.mkdirs()
    drivers.files.filter { it.name.startsWith("h2-2.3.232.") }.forEach { it.copyTo(File(h2, it.name), overwrite = true) }
  }
  systemProperty("user.home", testHome.get())
  // these mix Java and Kotlin sources in one fixture
  if (!kotlinBundled) filter {
    excludeTestsMatching("dev.phantom.db.jvm.JpaSchemaTest")
    excludeTestsMatching("dev.phantom.db.jvm.SqlInjectionTest")
    excludeTestsMatching("dev.phantom.db.jvm.SqlDbUsagesTest")
  }
}
