import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension

plugins {
  id("phantom-plugin")
}

version = "0.1.0"

// The IntelliJ sources keep Java next to Kotlin. src/main is intellij.gradle, intellij.gradle.settings and the content
// modules completion, completion.toml and properties. The other source sets are jars of their own,
// named as IntelliJ names them (CommunityRepositoryModules, intellij.gradle.plugin):
// - toolingExtension, toolingExtensionImpl, toolingProxy: lib/gradle-tooling-extension-api.jar,
//   gradle-tooling-extension-impl.jar and gradle-tooling-proxy.jar. The IDE puts them on the Gradle daemon's class path
//   and in its init scripts (GradleDaemonServices, GradleInitScriptUtil find them by class), and the daemon may run on
//   Java 8, so they are compiled for Java 8 and hold none of the IDE side.
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  // testData is a test resource root, as in IntelliJ (intellij.gradle.tests reads files from it as resources)
  test {
    java.srcDir("src/test/kotlin")
    resources.srcDir("testData")
  }
  create("toolingExtension") { java.srcDir("src/toolingExtension/kotlin") }
  create("toolingExtensionImpl") { java.srcDir("src/toolingExtensionImpl/kotlin") }
  create("toolingProxy") { java.srcDir("src/toolingProxy/kotlin") }
}

val platformPath = provider { project.extensions.getByType<IntelliJPlatformExtension>().platformPath }
fun platformJars(vararg dirs: String) = files(provider {
  dirs.flatMap { dir -> platformPath.get().resolve(dir).toFile().listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".jar") } }
})

fun toolingJar(name: String, sourceSet: String) = tasks.register<Jar>("${sourceSet}Jar") {
  archiveFileName = name
  destinationDirectory = layout.buildDirectory.dir("toolingJars")
  // classes and own resources only: the platform plugin adds the patched plugin.xml to every source set's resources
  from(sourceSets[sourceSet].output.classesDirs)
  from(layout.projectDirectory.dir("src/$sourceSet/resources"))
}
val toolingExtensionJar = toolingJar("gradle-tooling-extension-api.jar", "toolingExtension")
val toolingExtensionImplJar = toolingJar("gradle-tooling-extension-impl.jar", "toolingExtensionImpl")
val toolingProxyJar = toolingJar("gradle-tooling-proxy.jar", "toolingProxy")

// The tooling jars for gradle-java to compile against: localPlugin puts only a local plugin's own jar on the compile
// classpath, not the other jars in its lib/
val toolingJars by configurations.creating {
  isCanBeConsumed = true
  isCanBeResolved = false
}
artifacts {
  add("toolingJars", toolingExtensionJar)
  add("toolingJars", toolingExtensionImplJar)
  add("toolingJars", toolingProxyJar)
}

// The Gradle API and Tooling API IntelliJ builds against (the project library "Gradle"), published by JetBrains in the
// IntelliJ dependencies repository. Packed into lib/ as gradle-api-<version>.jar, as in IntelliJ.
val gradleApi = "org.jetbrains.intellij.deps:gradle-api:9.6.0"

dependencies {
  intellijPlatform {
    bundledModule("intellij.platform.externalSystem.impl")
    bundledModule("intellij.platform.smRunner")
    bundledModule("intellij.platform.testRunner")
    bundledModule("intellij.libraries.jspecify")
    // Groovy (intellij.libraries.groovy, which intellij.gradle.xml depends on) comes from Groovy Scripting, as in Phantom:
    // the tooling proxy's class path takes it from there instead of a groovy.jar of this plugin's own
    localPlugin(project(":groovy-scripting"))
    // What the optional content modules build on (each switches off without it)
    bundledPlugin("com.intellij.properties")
    bundledPlugin("org.toml.lang")
    bundledPlugin("org.jetbrains.idea.reposearch")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
  implementation(gradleApi) { isTransitive = false }
  // localPlugin doesn't put the Groovy Scripting libraries on the compile classpath: the same Groovy, compile only
  compileOnly("org.codehaus.groovy:groovy:3.0.25") { isTransitive = false }
  implementation(files(toolingExtensionJar, toolingExtensionImplJar, toolingProxyJar))
  compileOnly(sourceSets["toolingExtension"].output)
  compileOnly(sourceSets["toolingExtensionImpl"].output)
  compileOnly(sourceSets["toolingProxy"].output)

  // The tooling jars compile against the Gradle API and the platform's Java 8 runtime jars (lib/), which the IDE also
  // hands to the daemon
  listOf("toolingExtension", "toolingExtensionImpl", "toolingProxy").forEach { set ->
    "${set}CompileOnly"(gradleApi) { isTransitive = false }
    "${set}CompileOnly"(platformJars("lib"))
  }
  // Groovy and groovy-ant are provided by Gradle inside the daemon (IntelliJ: intellij.libraries.groovy and
  // intellij.libraries.groovy.ant, scope PROVIDED); groovy-xml holds MarkupBuilder
  listOf("toolingExtension", "toolingExtensionImpl", "toolingProxy").forEach { set ->
    listOf("groovy", "groovy-xml", "groovy-ant").forEach { "${set}CompileOnly"("org.codehaus.groovy:$it:3.0.25") { isTransitive = false } }
  }
  "toolingExtensionImplCompileOnly"(sourceSets["toolingExtension"].output)
  "toolingProxyCompileOnly"(sourceSets["toolingExtension"].output)
  "toolingProxyCompileOnly"(sourceSets["toolingExtensionImpl"].output)

  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("org.assertj:assertj-core:3.27.6")
  testImplementation("org.mockito:mockito-core:5.20.0")
}

// Java 8 bytecode for the jars that run inside the Gradle daemon (IntelliJ: jvmTarget 1.8 for these modules)
listOf("toolingExtension", "toolingExtensionImpl", "toolingProxy").forEach { set ->
  tasks.named<JavaCompile>("compile${set.replaceFirstChar { it.uppercase() }}Java") { options.release = 8 }
  tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compile${set.replaceFirstChar { it.uppercase() }}Kotlin") {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
  }
}

tasks.test {
  useJUnitPlatform()
}

// The IDE the tests run in still bundles IntelliJ's Gradle, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("com.intellij.gradle")
  disabledPlugins.add("org.jetbrains.plugins.gradle")
}
