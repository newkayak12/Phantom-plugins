import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
  // The IntelliJ sources use @Serializable
  id("org.jetbrains.kotlin.plugin.serialization")
}

version = "0.1.5"

// As the IntelliJ module is compiled: interfaces annotated @JvmDefaultWithCompatibility need this mode
kotlin {
  compilerOptions.freeCompilerArgs.add("-jvm-default=no-compatibility")
}

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test {
    java.srcDir("src/test/kotlin")
    // Not compiled: these talk to a real GitHub server with the test accounts' tokens (idea_test_github_token1/2), and
    // their base GitPlatformTest comes from Git4Idea's test sources, which aren't published
    val needGithubServer = listOf(
      "org/jetbrains/plugins/github/test/GithubTest.kt",
      "org/jetbrains/plugins/github/GithubCreateGist*.kt",
      "org/jetbrains/plugins/github/GithubShareProject*.kt",
      "org/jetbrains/plugins/github/GithubIssuesTest.kt",
      "org/jetbrains/plugins/github/GithubRequestQueryingTest.kt",
    )
    // (src/test/kotlin is both a Kotlin and a Java source dir)
    kotlin.exclude(needGithubServer)
    java.exclude(needGithubServer)
  }
}

dependencies {
  intellijPlatform {
    bundledPlugin("Git4Idea")
    // What the json module builds on (it switches off without it)
    bundledPlugin("com.intellij.modules.json")
    bundledModule("intellij.json.backend")
    bundledModule("intellij.vcs.git.shared")
    bundledModule("intellij.platform.collaborationTools")
    bundledModule("intellij.platform.collaborationTools.auth")
    bundledModule("intellij.platform.collaborationTools.auth.base")
    bundledModule("intellij.platform.vcs.dvcs")
    bundledModule("intellij.platform.vcs.dvcs.impl")
    bundledModule("intellij.platform.vcs.dvcs.impl.shared")
    bundledModule("intellij.platform.vcs.impl")
    bundledModule("intellij.platform.vcs.impl.shared")
    bundledModule("intellij.platform.vcs.log")
    bundledModule("intellij.platform.vcs.log.impl")
    bundledModule("intellij.platform.vcs.log.graph")
    bundledModule("intellij.platform.markdown.utils")
    bundledModule("intellij.platform.builtInServer.impl")
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.JUnit5CodeInsight, providers.gradleProperty("platformTestBuild"))
  }
  // Some tests are JUnit Jupiter; the rest are JUnit 4, run by the vintage engine
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("org.jetbrains.kotlin:kotlin-test")
  testImplementation("io.mockk:mockk:1.14.5")
  testImplementation("app.cash.turbine:turbine-jvm:1.0.0")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
  testImplementation("org.assertj:assertj-core:3.27.6")
  // The GraphQL tests parse the plugin's queries against the GitHub schemas; the plugin itself doesn't use it
  testImplementation("com.apollographql.apollo:apollo-ast-jvm:4.0.0") {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "org.jetbrains", module = "annotations")
  }
}

tasks.test {
  useJUnitPlatform()
}

// The IDE the tests run in still bundles IntelliJ's GitHub, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("org.jetbrains.plugins.github")
}
