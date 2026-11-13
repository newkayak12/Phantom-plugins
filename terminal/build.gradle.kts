import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
  id("phantom-plugin")
  id("org.jetbrains.kotlin.plugin.serialization")
}

version = "0.1.0"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

// Upstream tests that need IntelliJ test sources which are not published as artifacts: the parameterized Eel test API
// (TestApplicationWithEel, EelHolder, DockerTest from platform/testFramework/junit5/eel/test) and the configuration
// store's testSerializer (platform/configuration-store-impl/testSrc)
val testsNeedingUnpublishedTestSources = listOf(
  "com/intellij/terminal/tests/reworked/backend/TerminalSessionHasRunningCommandsTest.kt",
  "com/intellij/terminal/tests/runner/TerminalCustomizerLocalPathTranslatorTest.kt",
  "com/intellij/terminal/tests/startup/ShellExecOptionsCustomizerTest.kt",
  "com/intellij/terminal/tests/startup/TerminalStartupConfigurationTest.kt",
  "com/intellij/terminal/tests/TerminalStateSerializationTest.kt",
  // A disabled suite for hunting project leaks locally (runs nothing itself); it lists TerminalStateSerializationTest
  "com/intellij/terminal/tests/ProjectLeakHunterTerminalSuite.kt",
)
sourceSets.test {
  java.exclude(testsNeedingUnpublishedTestSources)
  kotlin.exclude(testsNeedingUnpublishedTestSources)
}

// The IntelliJ module name: Java code calls a Kotlin internal member by its mangled name (getStartupMoment$intellij_terminal)
tasks.compileKotlin {
  compilerOptions.moduleName = "intellij.terminal"
}

dependencies {
  intellijPlatform {
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.platform.kernel.backend")
    bundledModule("intellij.platform.frontend")
    bundledModule("intellij.platform.inline.completion")
    bundledModule("intellij.platform.ide.impl.wsl")
    bundledModule("intellij.platform.rpc.topics")
    bundledModule("intellij.platform.remoteServers.impl")
    bundledModule("intellij.platform.feedback")
    // What intellij.terminal.sh builds on (it switches off without Shell Script)
    bundledPlugin("com.jetbrains.sh")
    bundledModule("intellij.sh.core")
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
  // The libraries the bundled plugin packs into its lib folder: the command specs and the completion ranking model.
  // Not transitive: the Kotlin stdlib and kotlinx.serialization they ask for come with the platform.
  implementation("org.jetbrains.terminal:completion-spec:0.7.0") { isTransitive = false }
  implementation("org.jetbrains.terminal:completion-db-with-extensions:0.7.0") { isTransitive = false }
  implementation("org.jetbrains.intellij.deps.completion:completion-ranking-sh:0.0.2") { isTransitive = false }
  // Some tests are JUnit Jupiter; the rest are JUnit 4, run by the vintage engine
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("com.jetbrains.intellij.platform:test-framework-junit5-eel:${providers.gradleProperty("platformTestBuild").get()}") { isTransitive = false }
  testImplementation("org.mockito:mockito-core:5.20.0")
  testImplementation("org.mockito.kotlin:mockito-kotlin:6.1.0")
}

tasks.test {
  useJUnitPlatform()
  // Flaky on macOS: bash's job control intermittently loses a race and prints "child setpgid ... Operation not permitted"
  // into the generator output, which the test then can't parse as the shell environment. Gradle filters a parameterized
  // JUnit 4 test by its method, so the zsh run of it goes too
  filter.excludeTest("com.intellij.terminal.tests.block.BlockTerminalTest", "concurrent command and generator execution")
}

// The shell integration scripts sit in the plugin folder (shell-integrations), where LocalShellIntegrationInjector finds
// them next to the jar holding its class, as in the bundled plugin; for the IDE, the test IDE and the distribution zip
tasks.withType<PrepareSandboxTask>().configureEach {
  from(layout.projectDirectory.dir("src/main/resources/shell-integrations")) {
    into(pluginName.map { "$it/shell-integrations" })
  }
}

// The IDE the tests run in still bundles IntelliJ's Terminal, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("org.jetbrains.plugins.terminal")
}
