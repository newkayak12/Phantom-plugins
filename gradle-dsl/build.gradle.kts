import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.1"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

// Left out of the upstream tests:
// - the two GradleDslTestSuite classes: Bazel suite runners (JarTestSuiteRunner, IdeaTestSuiteBase from Android
//   Studio's test infrastructure); Gradle finds the test classes itself
// - com/android/tools/idea/gradle/GradleFileModelTestCase.kt: a base class only Android plugin tests outside this
//   plugin use, built on Android Studio's AndroidProjectRule
// - parser/declarative/*: tests of the Declarative module, which is left out (see plugin.xml)
// The few Android Studio helpers the remaining tests call (SdkConstants, TestUtils, FileUtils, Projects, IdeSdks,
// DeclarativeIdeSupport, DeclarativeLiteral) are small stand-ins under src/test/kotlin/com/android/;
// ExpectedModuleDependency (android-test-framework) and IdeTestOptions (project-system-gradle-models) are copied as is.
// The tests' Declarative (.gradle.dcl) variants are skipped: the Declarative module is left out (see plugin.xml).
val testsNeedingAndroidStudio = listOf(
  "com/android/tools/idea/gradle/dsl/GradleDslTestSuite.java",
  "com/android/tools/idea/gradle/dsl/android/GradleDslTestSuite.kt",
  "com/android/tools/idea/gradle/GradleFileModelTestCase.kt",
  "com/android/tools/idea/gradle/dsl/parser/declarative/**",
)
sourceSets.test {
  java.exclude(testsNeedingAndroidStudio)
  kotlin.exclude(testsNeedingAndroidStudio)
}

// Built against IntelliJ Community instead of the installed Phantom (CI, or -PplatformFromRepositoryFor=gradle-dsl)
val fromRepository = providers.gradleProperty("platformFromRepository").isPresent ||
  project.name in providers.gradleProperty("platformFromRepositoryFor").orNull.orEmpty().split(',').map { it.trim() }

// The Kotlin script parser (intellij.android.gradle.dsl.kotlin) compiles against the Kotlin plugin's own jars, taken
// from the IntelliJ Community build Phantom is forked from: Phantom no longer bundles Kotlin, so the installed Phantom
// has none, and bundledPlugin("org.jetbrains.kotlin") doesn't resolve against IntelliJ Community 262.10968.63 either
// (the Gradle plugin's IDE layout index leaves the Kotlin plugin out). Compile only: at runtime the module uses whatever
// Kotlin plugin is installed, and switches off without one. Against IntelliJ Community the tests get that Kotlin plugin
// (lib and kotlinc) as a test plugin, so the Kotlin script variants run; against the installed Phantom (no Kotlin)
// they are skipped.
// Extracted when first asked for (the test plugin has to exist when Gradle resolves the test classpath, before any task)
val intellijCommunity by configurations.creating
val kotlinPluginDir: Provider<File> = provider {
  val dir = layout.buildDirectory.dir("kotlinPlugin").get().asFile
  if (!dir.resolve("lib/kotlin-plugin.jar").isFile) {
    sync {
      from(zipTree(intellijCommunity.singleFile)) {
        include("plugins/Kotlin/lib/*.jar", "plugins/Kotlin/lib/modules/*.jar", "plugins/Kotlin/kotlinc/**")
        eachFile { path = path.removePrefix("plugins/Kotlin/") }
        includeEmptyDirs = false
      }
      into(dir)
    }
  }
  dir
}
val kotlinCompileJars = files(kotlinPluginDir.map { listOf(it.resolve("lib/kotlin-plugin.jar"), it.resolve("lib/kotlinc.kotlin-compiler-common.jar")) })

dependencies {
  intellijCommunity("com.jetbrains.intellij.idea:ideaIC:${providers.gradleProperty("platformTestBuild").get()}@zip")
  compileOnly(kotlinCompileJars)
  testCompileOnly(kotlinCompileJars)
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    bundledPlugin("com.intellij.gradle")
    // What the optional parser modules build on (each switches off without its dependency)
    bundledPlugin("org.intellij.groovy")
    bundledModule("intellij.toml.core")
    // The installed Phantom's TOML is Phantom TOML, which its module alone brings in (bundledPlugin("org.toml.lang")
    // resolves the alias to an artifact the Gradle plugin doesn't create); IntelliJ Community's needs the plugin
    if (fromRepository) bundledPlugin("org.toml.lang")
    // Phantom's Gradle and Groovy plugins depend on the alias com.intellij.groovy.scripting (Phantom Groovy Scripting), whose
    // artifact the Gradle plugin creates only when it is asked for directly
    if (!fromRepository) bundledPlugin("com.intellij.groovy.scripting")
    if (fromRepository) testLocalPlugin(kotlinPluginDir)
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
  // The Truth version the upstream tests are written for (Subject.named); its old Guava would shadow the platform's
  testImplementation("com.google.truth:truth:0.42") { exclude(group = "com.google.guava") }
  testImplementation("org.mockito:mockito-core:5.20.0")
  testImplementation("org.mockito.kotlin:mockito-kotlin:6.1.0")
}

tasks.test {
  // The tests read testData through the VFS, outside the roots a fresh checkout allows
  systemProperty("NO_FS_ROOTS_ACCESS_CHECK", "true")
  // Tests the KMP androidLibrary block, whose KmpAndroidLibraryBlockModelProvider only Android Studio's descriptor
  // (META-INF/gradle-dsl-android.xml) registers; IntelliJ's intellij.android.gradle.dsl.android.xml leaves it out
  filter.excludeTestsMatching("com.android.tools.idea.gradle.dsl.android.model.android.KmpAndroidLibraryTest")
  // These parse Kotlin script (GradleNameTest directly, the parity tests in their Kotlin variant), so need the Kotlin
  // plugin, which the installed Phantom doesn't have (see above)
  if (!fromRepository) {
    filter.excludeTestsMatching("com.android.tools.idea.gradle.dsl.parser.kotlin.GradleNameTest")
    filter.excludeTestsMatching("com.android.tools.idea.gradle.dsl.parser.GroovyKotlinDsl*ParityTest.*[Kotlin]")
  }
}

// The IDE the tests run in still bundles IntelliJ's Gradle DSL API, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("org.jetbrains.idea.gradle.dsl")
}
