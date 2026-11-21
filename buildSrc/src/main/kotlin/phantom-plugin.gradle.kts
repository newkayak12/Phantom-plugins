// Shared setup for every plugin in this repository: Kotlin, and the installed Phantom as the platform.
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
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

// Phantom was "Phantom IDEA.app" before the rename; until Phantom.app is installed, the old app is used
val installedPhantom: Provider<String> = providers.gradleProperty("phantomIde").map { path ->
  val legacy = "/Applications/Phantom IDEA.app"
  if (!file(path).exists() && path == "/Applications/Phantom.app" && file(legacy).exists()) legacy else path
}

dependencies {
  intellijPlatform {
    // Plugins compile against an installed Phantom, not a downloaded IntelliJ. CI (-PplatformFromRepository) has no
    // Phantom installed and takes the IntelliJ Community build Phantom is forked from instead.
    // Also for the plugins listed in platformFromRepositoryFor: their id is still bundled in the installed Phantom, which
    // would shadow the plugin under test (a bundled copy with a higher version wins).
    val fromRepositoryFor = providers.gradleProperty("platformFromRepositoryFor").orNull.orEmpty().split(',').map { it.trim() }
    if (providers.gradleProperty("platformFromRepository").isPresent || project.name in fromRepositoryFor) {
      intellijIdeaCommunity(providers.gradleProperty("platformTestBuild")) { useInstaller = false }
    }
    else {
      local(installedPhantom)
      resolveBundledPhantomPluginsByAlias()
    }
    // Phantom is a fork of this IntelliJ build, whose test framework is published
    testFramework(TestFrameworkType.Platform, providers.gradleProperty("platformTestBuild"))
    pluginVerifier()
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

// Libraries from Maven that a plugin packs into lib/ by other means than runtimeClasspath (repackaged into a jar of its
// own, as kafka's stripped codecs and testng's merged testng.jar), so that THIRD-PARTY-NOTICES lists them too
val packedLibraries = configurations.create("packedLibraries") {
  isCanBeConsumed = false
}

// After the Gradle plugin's own arguments, which carry Phantom's class data sharing options (see NoClassDataSharing)
tasks.withType<Test>().configureEach { doFirst { (this as Test).jvmArgumentProviders.add(NoClassDataSharing()) } }
tasks.withType<JavaExec>().configureEach { doFirst { (this as JavaExec).jvmArgumentProviders.add(NoClassDataSharing()) } }

// META-INF/THIRD-PARTY-NOTICES.txt and META-INF/licenses/: every library packed into the plugin's lib/, with its license
// and the license texts. Computed from the resolved POMs when the task's input is read.
val thirdPartyNotices = tasks.register<ThirdPartyNoticesTask>("thirdPartyNotices") {
  val built by lazy {
    ThirdPartyNotices(project, listOf(configurations["runtimeClasspath"], packedLibraries), rootDir.resolve("licenses")).build()
  }
  notices = providers.provider { built.first }
  licenseTexts = providers.provider { built.second }
  outputDirectory = layout.buildDirectory.dir("thirdPartyNotices")
}

// Every plugin zip carries the licenses with it: the repository's LICENSE and NOTICE, a plugin folder's own LICENSE
// (TOML's MIT license, for one) as META-INF/LICENSE-<plugin>, and the notices of its third-party libraries
tasks.named<Jar>("jar") {
  metaInf {
    from(rootDir.resolve("LICENSE"), rootDir.resolve("NOTICE"))
    from(projectDir.resolve("LICENSE").takeIf { it.isFile } ?: files()) { rename { "LICENSE-${project.name}" } }
    from(thirdPartyNotices)
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
  // ./gradlew :<plugin>:verifyPlugin checks the built plugin's bytecode against the IDE it will run in, which compiling
  // does not fully cover (APIs that another plugin or a newer IntelliJ has, but this Phantom lacks). The IDE is the
  // Phantom at -PverifyIde=<dir> (CI unpacks a Phantom release there), else the installed Phantom, else in CI
  // (-PplatformFromRepository) the IntelliJ Community build Phantom is forked from.
  pluginVerification {
    failureLevel = listOf(FailureLevel.COMPATIBILITY_PROBLEMS, FailureLevel.INVALID_PLUGIN)
    val verifyIde = providers.gradleProperty("verifyIde")
    // Phantom does not bundle Kotlin (it is published as a plugin of its own), and the verifier only sees the plugins an
    // IDE lists in product-info.json, so against a Phantom build Kotlin's classes count as external. The IntelliJ
    // Community check covers them.
    if (verifyIde.isPresent || !providers.gradleProperty("platformFromRepository").isPresent) {
      externalPrefixes.add("org.jetbrains.kotlin")
    }
    ides {
      when {
        verifyIde.isPresent -> local(verifyIde)
        providers.gradleProperty("platformFromRepository").isPresent ->
          create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformTestBuild")) { useInstaller = false }
        else -> local(installedPhantom)
      }
    }
  }
}
