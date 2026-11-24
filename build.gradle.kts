// Each plugin lives in its own folder; the shared setup is buildSrc/src/main/kotlin/phantom-plugin.gradle.kts.
// Code quality, as in CLAUDE.md: Spotless (ktlint) and detekt check only what changed, so the IntelliJ code the
// plugins are built from keeps its form until someone edits it.
plugins {
  id("com.diffplug.spotless") version "8.11.0"
  id("dev.detekt") version "2.0.0-alpha.6"
}

spotless {
  // only files that differ from origin/main; CI fetches it (fetch-depth: 0)
  // -PspotlessRatchetFrom=none checks every file
  providers.gradleProperty("spotlessRatchetFrom").getOrElse("origin/main").takeIf { it != "none" }?.let { ratchetFrom(it) }
  kotlin {
    target("*/src/**/*.kt")
    // gradle-core and gradle-java are forks of IntelliJ's Gradle plugins, copied whole; their code keeps IntelliJ's form
    targetExclude("**/build/**", "*/testData/**", "gradle-core/**", "gradle-java/**")
    ktlint("1.8.0")
  }
  kotlinGradle {
    target("*.gradle.kts", "*/*.gradle.kts", "buildSrc/**/*.gradle.kts")
    ktlint("1.8.0")
  }
}

detekt {
  source.setFrom(
    files(
      rootDir.listFiles().orEmpty().filter { it.resolve("build.gradle.kts").isFile }.flatMap {
        listOf(it.resolve("src/main/kotlin"), it.resolve("src/test/kotlin"))
      }.filter { it.isDirectory },
    ),
  )
  config.setFrom(file("config/detekt/detekt.yml"))
  buildUponDefaultConfig = true
  // today's findings; new code must not add to them (./gradlew detektBaseline after a deliberate change)
  baseline = file("config/detekt/baseline.xml")
  parallel = true
}
