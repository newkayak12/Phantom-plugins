import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension

plugins {
  id("phantom-plugin")
}

version = "0.1.0"

// The IntelliJ sources keep Java next to Kotlin. src/main is intellij.gradle.java and the content modules (coverage,
// groovy, groovy.ml, groovy.toml, performance, properties, toml, analysis, dependencyUpdater, java.maven). src/jps is
// lib/gradle-jps.jar, the JPS builder, which the build process loads (compileServer.plugin in intellij.gradle.java.xml
// names it; BuildProcessClasspathManager finds it in <plugin dir>/lib), as IntelliJ packs it.
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
  create("jps")
}

val platformPath = provider { project.extensions.getByType<IntelliJPlatformExtension>().platformPath }
fun platformJars(vararg dirs: String) = files(provider {
  dirs.flatMap { dir -> platformPath.get().resolve(dir).toFile().walkTopDown().filter { it.isFile && it.name.endsWith(".jar") }.toList() }
})

val jpsJar = tasks.register<Jar>("jpsJar") {
  archiveFileName = "gradle-jps.jar"
  destinationDirectory = layout.buildDirectory.dir("jpsJar")
  // classes and own resources only: the platform plugin adds the patched plugin.xml to every source set's resources
  from(sourceSets["jps"].output.classesDirs)
  from(layout.projectDirectory.dir("src/jps/resources"))
}

val gradleApi = "org.jetbrains.intellij.deps:gradle-api:9.6.0"

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
    bundledPlugin("com.intellij.completion.ml.ranking")
    localPlugin(project(":groovy"))
    localPlugin(project(":gradle-dsl"))
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
  // localPlugin doesn't put a local plugin's libraries on the compile classpath: the Gradle API gradle-core packs
  compileOnly(gradleApi) { isTransitive = false }
  implementation(files(jpsJar))

  // The JPS builder compiles against the platform's and the Java plugin's JPS classes, which the build process has, and
  // the Gradle API it reads build output models with
  "jpsCompileOnly"(platformJars("lib", "plugins/java/lib"))
  "jpsCompileOnly"(gradleApi) { isTransitive = false }

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
