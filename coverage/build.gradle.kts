import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension

plugins {
  id("phantom-plugin")
}

version = "0.1.4"

// The IntelliJ sources keep Java next to Kotlin. src/rt is java-coverage-rt.jar: the JUnit and TestNG listeners that
// run inside the user's test JVM, so Java 8 bytecode in a jar of its own (the IDE's run configurations add that jar)
sourceSets {
  test { java.srcDir("src/test/kotlin") }
  create("rt")
}

// The rt listeners compile only against the Java 8 runtime jars of the IDE, never its Java 25 classes
val rtLibraries = listOf(
  "lib/idea_rt.jar",
  "lib/intellij.platform.coverage.agent.jar",
  "plugins/testng/lib/testng-rt.jar",
  "plugins/testng/lib/testng.jar",
)

val rtJar by tasks.registering(Jar::class) {
  archiveFileName = "java-coverage-rt.jar"
  // classes only: the platform plugin adds the patched plugin.xml to every source set's resources, and a second
  // plugin.xml in lib/ makes the descriptor invalid
  from(sourceSets["rt"].output.classesDirs)
}

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    bundledPlugin("JUnit")
    bundledPlugin("TestNG-J")
    bundledModule("intellij.platform.coverage")
    bundledModule("intellij.platform.coverage.agent")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
  "rtCompileOnly"(files(provider { val ide = project.extensions.getByType<IntelliJPlatformExtension>().platformPath; rtLibraries.map { ide.resolve(it).toFile() } }))
  implementation(files(rtJar))
  // The bundled plugin's jacoco.jar and coverage-report.jar, from Maven
  implementation("org.jacoco:org.jacoco.ant:0.8.15")
  implementation("org.jetbrains.intellij.deps:coverage-report:1.0.25") {
    exclude(group = "org.jetbrains", module = "annotations") // the IDE has it
  }
  // FileComparisonFailedError is an opentest4j failure
  testImplementation("org.opentest4j:opentest4j:1.3.0")
}

tasks.named<JavaCompile>("compileRtJava") {
  options.release = 8
}

// The IDE the tests run in still bundles IntelliJ's Code Coverage for Java, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("Coverage")
}
