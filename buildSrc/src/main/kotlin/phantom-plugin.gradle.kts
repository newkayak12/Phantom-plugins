// Shared setup for every plugin in this repository: Kotlin, and the installed Phantom IDEA as the platform.
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.intellij.platform")
}

group = "dev.phantom"

kotlin {
  jvmToolchain(25)
  compilerOptions.jvmTarget = JvmTarget.JVM_25
}

java {
  targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
  intellijPlatform {
    // Plugins compile against an installed Phantom IDEA, not a downloaded IntelliJ. CI (-PplatformFromRepository) has no
    // Phantom installed and takes the IntelliJ Community build Phantom is forked from instead.
    // Also for the plugins listed in platformFromRepositoryFor: their id is still bundled in the installed Phantom, which
    // would shadow the plugin under test (a bundled copy with a higher version wins).
    val fromRepositoryFor = providers.gradleProperty("platformFromRepositoryFor").orNull.orEmpty().split(',').map { it.trim() }
    if (providers.gradleProperty("platformFromRepository").isPresent || project.name in fromRepositoryFor) {
      intellijIdeaCommunity(providers.gradleProperty("platformTestBuild")) { useInstaller = false }
    }
    else {
      local(providers.gradleProperty("phantomIde"))
      resolveBundledPhantomPluginsByAlias()
    }
    // Phantom is a fork of this IntelliJ build, whose test framework is published
    testFramework(TestFrameworkType.Platform, providers.gradleProperty("platformTestBuild"))
  }
  testImplementation("junit:junit:4.13.2")
  testRuntimeOnly("org.opentest4j:opentest4j:1.3.0")
}

// The installed Phantom bundles plugins of this repository (dev.phantom.properties, ...) in place of IntelliJ's, with
// IntelliJ's ids as aliases. The Gradle plugin describes an alias (bundledPlugin("com.intellij.properties")) as
// depending on the plugin's own id, which it never describes ("Could not find bundledPlugin:dev.phantom.properties").
// Both name the same jars, so the plugin's own id is resolved by its alias: the alias then depends on itself, as
// bundledPlugin("com.intellij.java") does. So build scripts ask for these plugins by their IntelliJ ids, never dev.phantom.*.
fun resolveBundledPhantomPluginsByAlias() {
  val aliasOf = rootDir.listFiles().orEmpty().mapNotNull { dir ->
    val descriptor = dir.resolve("src/main/resources/META-INF/plugin.xml").takeIf { it.isFile }?.readText() ?: return@mapNotNull null
    val id = Regex("<id>\\s*([^<\\s]+)\\s*</id>").find(descriptor)?.groupValues?.get(1)
    val alias = Regex("<module\\s+value=\"([^\"]+)\"").find(descriptor)?.groupValues?.get(1)
    if (id != null && alias != null) id to alias else null
  }.toMap()
  configurations.configureEach {
    resolutionStrategy.eachDependency {
      if (requested.group == "bundledPlugin") aliasOf[requested.name]?.let { useTarget("bundledPlugin:$it:${requested.version}") }
    }
  }
}

// Every plugin zip carries the licenses with it: the repository's LICENSE and NOTICE, and a plugin folder's own LICENSE
// (TOML's MIT license, for one) as META-INF/LICENSE-<plugin>
tasks.named<Jar>("jar") {
  metaInf {
    from(rootDir.resolve("LICENSE"), rootDir.resolve("NOTICE"))
    from(projectDir.resolve("LICENSE").takeIf { it.isFile } ?: files()) { rename { "LICENSE-${project.name}" } }
  }
}

intellijPlatform {
  buildSearchableOptions = false
  instrumentCode = false
  pluginConfiguration {
    ideaVersion {
      sinceBuild = providers.gradleProperty("sinceBuild")
      untilBuild = providers.gradleProperty("untilBuild")
    }
  }
}
