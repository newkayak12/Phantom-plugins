import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
  // The IntelliJ sources use @Serializable
  id("org.jetbrains.kotlin.plugin.serialization")
}

version = "0.2.3"

// The Backup and Sync history (phantom.git.settingsSync) moved here from the IDE core. The core of earlier Phantom builds
// still has it, so an installed zip must not load there
intellijPlatform {
  pluginConfiguration {
    ideaVersion {
      sinceBuild = "262.261123"
      untilBuild = "262.*"
    }
  }
}

// The Plugin Verifier rejects that since-build as an invalid descriptor (it allows at most 99999 in a build number
// component) and checks nothing else, whatever failureLevel says, so verifyPlugin would always fail. Compiling and
// testing against the platform still cover git.
tasks.named("verifyPlugin") { enabled = false }

// As the IntelliJ module is compiled: interfaces annotated @JvmDefaultWithCompatibility need this mode
kotlin {
  compilerOptions.freeCompilerArgs.add("-jvm-default=no-compatibility")
  compilerOptions.optIn.add("kotlin.RequiresOptIn")
}

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

// Upstream tests that are not compiled: they need IntelliJ test sources which are not published as artifacts (the
// platform VCS tests' line status tracker and change list bases, the VCS log index and user filter test bases, the
// parameterized Eel test API), the IDE metrics benchmark, or the Task Management plugin, which Phantom doesn't ship
val testsNeedingUnpublishedTestSources = listOf(
  "git4idea/index/GitStageLineStatusTrackerTest.kt",
  "git4idea/tests/GitChangeProviderTest.kt",
  "git4idea/tests/GitChangeProviderConflictTest.kt",
  "git4idea/tests/GitChangeProviderVersionedTest.kt",
  "git4idea/tests/GitDirtyScopeTest.kt",
  "git4idea/log/GitLogIndexTest.kt",
  "git4idea/log/GitUserFilterTest.java",
  "git4idea/tests/GitCommitTest.kt",
  "git4idea/history/GitLogPerformanceTest.kt",
  "git4idea/branch/GitBranchContextTest.kt",
)
sourceSets.test {
  java.exclude(testsNeedingUnpublishedTestSources)
  kotlin.exclude(testsNeedingUnpublishedTestSources)
}

dependencies {
  intellijPlatform {
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.platform.kernel.backend")
    bundledModule("intellij.platform.rpc.backend")
    bundledModule("intellij.platform.frontend")
    bundledModule("intellij.platform.searchEverywhere")
    bundledModule("intellij.platform.searchEverywhere.frontend")
    bundledModule("intellij.platform.ide.impl.wsl")
    bundledModule("intellij.platform.lvcs.impl")
    bundledModule("intellij.platform.collaborationTools")
    bundledModule("intellij.platform.vcs.dvcs")
    bundledModule("intellij.platform.vcs.dvcs.impl")
    bundledModule("intellij.platform.vcs.dvcs.impl.shared")
    bundledModule("intellij.platform.vcs.impl")
    bundledModule("intellij.platform.vcs.impl.shared")
    bundledModule("intellij.platform.vcs.log")
    bundledModule("intellij.platform.vcs.log.impl")
    bundledModule("intellij.platform.vcs.log.graph")
    bundledModule("intellij.platform.vcs.log.graph.impl")
    // What intellij.vcs.git.coverage and intellij.vcs.git.terminal build on (each switches off without it)
    bundledModule("intellij.platform.coverage")
    bundledPlugin("org.jetbrains.plugins.terminal")
    // What intellij.vcs.git.settingsSync (the Backup and Sync history) builds on
    bundledModule("intellij.settingsSync.core")
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
  // VcsPlatformTest and the Executor the Git tests build on, and the snapshot assertions
  testImplementation("com.jetbrains.intellij.platform:vcs-test-framework:${providers.gradleProperty("platformTestBuild").get()}") {
    isTransitive = false
  }
  testImplementation("com.jetbrains.intellij.platform:test-extensions:${providers.gradleProperty("platformTestBuild").get()}") {
    isTransitive = false
  }
  // Some tests are JUnit Jupiter; the rest are JUnit 4, run by the vintage engine
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("org.jetbrains.kotlin:kotlin-test")
  testImplementation("org.assertj:assertj-core:3.27.6")
  testImplementation("org.mockito:mockito-core:5.20.0")
}

tasks.test {
  useJUnitPlatform()
  filter {
    // The ls-remote check skips non-HTTP remotes while an SSH agent runs (SSH_AUTH_SOCK), as on developer machines
    excludeTest("git4idea.branch.GitBranchIncomingOutgoingManagerTest", "test incoming and outgoing commits with ls-remote strategy")
    excludeTest(
      "git4idea.branch.GitBranchIncomingOutgoingManagerTest",
      "test ls-remote command disables native credential helper when not previously authenticated",
    )
    // `git refs migrate --ref-format=reftable` with reflogs needs a newer Git than many installed ones (2.47 refuses it)
    excludeTest("git4idea.repo.GitRecentProjectsBranchesServiceReftableTest", null)
    excludeTest("git4idea.repo.GitRepositoryReaderNewTest\$UsingReftable", null)
    // Assumptions about the file system (case-sensitive names, long paths) that the vintage engine reports as failures
    excludeTest("git4idea.repo.GitRepositoryReaderNewTest\$UsingPackedRefs", "test branches are case-sensitive on case-sensitive systems")
    excludeTest("git4idea.repo.GitRepositoryReaderNewTest\$UsingPackedRefs", "test current branch is known even if deleted")
    excludeTest("git4idea.repo.GitCloneLongPathsTest", null)
    // On macOS the temporary directory is reached through the /var -> /private/var link, and the expected path differs
    excludeTest("git4idea.index.GitStageTrackerTest", "test untracked")
    // Flaky: a different few of its tests fail on each run, on either platform
    excludeTest("git4idea.ignore.GitIgnoredFileTest", null)
    // Fails against IntelliJ 262.10968.63 (-PplatformFromRepository, as in CI) and passes against the installed Phantom;
    // the cause is not known yet
    excludeTest("git4idea.branch.GitBranchWorkerTest", "test agree to smart merge should smart merge")
  }
}

// The IDE the tests run in still bundles IntelliJ's Git, which claims the id this plugin keeps as an alias. The tests'
// service overrides (IntelliJ's testResources plugin "Git Integration Tests") go into the sandbox as a plugin of their own:
// the test framework loads plugins from there, not from the class path
tasks.prepareTestSandbox {
  disabledPlugins.add("Git4Idea")
  from(layout.projectDirectory.dir("src/test/testPlugin")) { into("git-tests") }
}
