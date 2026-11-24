import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
  id("phantom-plugin")
}

version = "0.1.0"

// The IntelliJ sources keep Java next to Kotlin. src/main is intellij.gradle.java and the content modules (coverage,
// groovy, groovy.toml, performance, properties, toml, analysis, dependencyUpdater, java.maven). src/jps is
// lib/gradle-jps.jar, the JPS builder, which the build process loads (compileServer.plugin in intellij.gradle.java.xml
// names it; BuildProcessClasspathManager finds it in <plugin dir>/lib), as IntelliJ packs it.
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  // testData is a test resource root, as in IntelliJ (intellij.gradle.java.tests)
  test {
    java.srcDir("src/test/kotlin")
    resources.srcDir("testData")
  }
  create("jps")
}

val platformPath = provider { project.extensions.getByType<IntelliJPlatformExtension>().platformPath }
fun platformJars(vararg dirs: String) = files(provider {
  dirs.flatMap { dir -> platformPath.get().resolve(dir).toFile().walkTopDown().filter { it.isFile && it.name.endsWith(".jar") }.toList() }
})

// Ant, which the JPS builder's resource filtering uses (ChainingFilterTransformer, GradleResourceFileProcessor). The
// build process has no Ant of its own, so IntelliJ packs it into gradle-jps.jar (CommunityStandaloneJpsBuilder); so
// does this build, with the same version.
val ant = "org.apache.ant:ant:1.10.15"
val jpsAnt by configurations.creating { isTransitive = false }

val jpsJar = tasks.register<Jar>("jpsJar") {
  archiveFileName = "gradle-jps.jar"
  destinationDirectory = layout.buildDirectory.dir("jpsJar")
  // classes and own resources only: the platform plugin adds the patched plugin.xml to every source set's resources
  from(sourceSets["jps"].output.classesDirs)
  from(layout.projectDirectory.dir("src/jps/resources"))
  from(provider { jpsAnt.map { zipTree(it) } }) { exclude("META-INF/**") }
}

val gradleApi = "org.jetbrains.intellij.deps:gradle-api:9.6.0"

// The content modules with a class loader of their own: IntelliJ packs each into lib/modules/<module>.jar, and a module
// without a package attribute can only tell its classes apart by jar. Each jar holds the module's packages (classes
// directly in them; subpackages may belong to another module) and its descriptor, out of the plugin jar.
val separateModules = mapOf(
  "intellij.gradle.java.maven" to listOf(
    "org.jetbrains.plugins.gradle.integrations.maven",
    "org.jetbrains.plugins.gradle.integrations.maven.codeInsight.actions",
    "org.jetbrains.plugins.gradle.integrations.maven.codeInsight.completion",
  ),
  "intellij.gradle.analysis" to listOf("org.jetbrains.plugins.gradle.service.resolve.static"),
  "intellij.gradle.dependencyUpdater" to listOf("org.jetbrains.plugins.gradle.dsl"),
  "intellij.gradle.java.performance" to listOf(
    "com.intellij.gradle.java.performance",
    "com.intellij.gradle.java.performance.dto",
    "com.intellij.gradle.java.performance.util",
  ),
  "intellij.gradle.java.toml" to listOf(
    "com.intellij.gradle.java.toml",
    "com.intellij.gradle.java.toml.codeInspection",
    "com.intellij.gradle.java.toml.completion",
    "com.intellij.gradle.java.toml.findUsages",
    "com.intellij.gradle.java.toml.navigation",
    "com.intellij.gradle.java.toml.service",
  ),
  "intellij.gradle.java.groovy" to listOf(
    "com.intellij.gradle.java.groovy",
    "com.intellij.gradle.java.groovy.codeInsight",
    "com.intellij.gradle.java.groovy.codeInsight.actions",
    "com.intellij.gradle.java.groovy.codeInspection",
    "com.intellij.gradle.java.groovy.codeInspection.fix",
    "com.intellij.gradle.java.groovy.codeInspection.groovy",
    "com.intellij.gradle.java.groovy.codeInspection.suppression",
    "com.intellij.gradle.java.groovy.compiler",
    "com.intellij.gradle.java.groovy.config",
    "com.intellij.gradle.java.groovy.documentation",
    "com.intellij.gradle.java.groovy.dsl",
    "com.intellij.gradle.java.groovy.execution",
    "com.intellij.gradle.java.groovy.execution.build",
    "com.intellij.gradle.java.groovy.frameworkSupport",
    "com.intellij.gradle.java.groovy.jps",
    "com.intellij.gradle.java.groovy.service.completion",
    "com.intellij.gradle.java.groovy.service.navigation",
    "com.intellij.gradle.java.groovy.service.notification",
    "com.intellij.gradle.java.groovy.service.project.wizard",
    "com.intellij.gradle.java.groovy.service.resolve",
    "com.intellij.gradle.java.groovy.service.resolve.dsl",
  ),
  "intellij.gradle.java.groovy.toml" to listOf("com.intellij.gradle.java.groovy.toml.service"),
  "intellij.gradle.java.coverage" to listOf("com.intellij.gradle.java.coverage"),
  "intellij.gradle.java.properties" to listOf(
    "com.intellij.gradle.java.properties.findUsages",
    "com.intellij.gradle.java.properties.util",
  ),
)
val moduleJars = separateModules.map { (module, packages) ->
  tasks.register<Jar>("${module.removePrefix("intellij.").replace('.', '_')}ModuleJar") {
    archiveFileName = "$module.jar"
    destinationDirectory = layout.buildDirectory.dir("moduleJars")
    from(sourceSets.main.get().output.classesDirs) { packages.forEach { include("${it.replace('.', '/')}/*") } }
    from(layout.projectDirectory.file("src/main/resources/$module.xml"))
  }
}
tasks.jar {
  separateModules.forEach { (module, packages) ->
    packages.forEach { exclude("${it.replace('.', '/')}/*.class") }
    exclude("$module.xml")
  }
}
tasks.withType<PrepareSandboxTask>().configureEach {
  from(moduleJars) {
    into(pluginName.map { "$it/lib/modules" })
  }
}

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    bundledModule("intellij.platform.jps.build")
    bundledModule("intellij.platform.smRunner")
    bundledModule("intellij.platform.testRunner")
    bundledModule("intellij.libraries.xstream")
    localPlugin(project(":gradle-core"))
    // What the optional content modules build on (each switches off without it)
    bundledPlugin("com.intellij.properties")
    bundledPlugin("org.toml.lang")
    bundledPlugin("Coverage")
    bundledPlugin("org.jetbrains.idea.maven")
    bundledPlugin("org.jetbrains.idea.reposearch")
    bundledPlugin("com.jetbrains.performancePlugin")
    localPlugin(project(":groovy"))
    localPlugin(project(":gradle-dsl"))
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
  // localPlugin doesn't put a local plugin's libraries on the compile classpath: the Gradle API gradle-core packs
  compileOnly(gradleApi) { isTransitive = false }
  compileOnly(project(":gradle-core", "toolingJars"))
  // intellij.gradle.java.groovy's JPS extension builds on Groovy's JPS builder (groovy-jps.jar)
  compileOnly(project(":groovy", "jpsJars"))
  // Groovy (intellij.libraries.groovy) comes from Groovy Scripting at runtime; the same Groovy, compile only
  compileOnly("org.codehaus.groovy:groovy:3.0.25") { isTransitive = false }
  implementation(files(jpsJar))

  // The JPS builder compiles against the platform's and the Java plugin's JPS classes, which the build process has, and
  // the Gradle API it reads build output models with
  "jpsCompileOnly"(platformJars("lib", "plugins/java/lib"))
  "jpsCompileOnly"(gradleApi) { isTransitive = false }
  "jpsCompileOnly"(ant) { isTransitive = false }
  jpsAnt(ant)
  // In THIRD-PARTY-NOTICES with the libraries in lib/
  packedLibraries(ant) { isTransitive = false }

  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("org.assertj:assertj-core:3.27.6")
  testImplementation("org.mockito:mockito-core:5.20.0")
}

// IntelliJ compiles intellij.gradle.jps for Java 11, the oldest JDK the build process runs on
tasks.named<JavaCompile>("compileJpsJava") { options.release = 11 }

tasks.test {
  useJUnitPlatform()
}

// The IDE the tests run in still bundles IntelliJ's Gradle plugins, which claim the ids these plugins keep as aliases
tasks.prepareTestSandbox {
  disabledPlugins.add("com.intellij.gradle")
  disabledPlugins.add("org.jetbrains.plugins.gradle")
}