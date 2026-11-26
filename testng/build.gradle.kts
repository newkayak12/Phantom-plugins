import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension

plugins {
  id("phantom-plugin")
}

version = "0.1.4"

// The IntelliJ sources keep Java next to Kotlin. src/rt is testng-rt.jar (intellij.testng.rt): the starter and listeners
// that run inside the user's test JVM, so Java 8 bytecode in a jar of its own (the run configuration puts that jar on the
// test classpath, found through RemoteTestNGStarter's class)
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
  create("rt")
}

// testng.jar, as in the bundled plugin: TestNG with JCommander and jQuery (its HTML reports) in one jar. "Add testng.jar
// to classpath" and the TestNG library it adds point at this jar, so the user's module gets JCommander too
val testngLibrary by configurations.creating { isTransitive = false }
// Listed in THIRD-PARTY-NOTICES as the libraries testng.jar is made of
configurations.named("packedLibraries") {
  isTransitive = false
  extendsFrom(testngLibrary)
}

val testngJar by tasks.registering(Jar::class) {
  archiveFileName = "testng.jar"
  destinationDirectory = layout.buildDirectory.dir("testngJar")
  from(testngLibrary.elements.map { jars -> jars.map { zipTree(it) } }) {
    exclude("META-INF/MANIFEST.MF")
  }
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val rtJar by tasks.registering(Jar::class) {
  archiveFileName = "testng-rt.jar"
  destinationDirectory = layout.buildDirectory.dir("rtJar")
  // classes only: the platform plugin adds the patched plugin.xml to every source set's resources
  from(sourceSets["rt"].output.classesDirs)
}

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    bundledPlugin("intellij.testRunner.plugin") // SMRunnerConsolePropertiesProvider (intellij.platform.smRunner)
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
  testngLibrary("org.testng:testng:7.8.0")
  testngLibrary("com.beust:jcommander:1.82")
  testngLibrary("org.webjars:jquery:3.6.1")
  implementation(files(testngJar))
  implementation(files(rtJar))
  // The rt classes compile against TestNG and idea_rt.jar (intellij.java.rt, in the IDE's lib/) only
  "rtCompileOnly"(files(testngJar))
  "rtCompileOnly"(files(provider { project.extensions.getByType<IntelliJPlatformExtension>().platformPath.resolve("lib/idea_rt.jar").toFile() }))
  // The tests run as JUnit 3/4 (vintage engine) and JUnit Jupiter. The TestNG-annotated tests (ConfigurationsTest,
  // AddTestNGJarFixTest, the data-provider methods of UndeclaredTestsInspectionTest and TestNGDataProviderTest; listed in
  // src/test/resources/testng.xml) are left out: tried under the JUnit Platform TestNG engine, their setup breaks this
  // platform's threading rules (write-unsafe EDT, model reads outside a read action), and the classes that are JUnit
  // TestCases too fail TestNG's injection of TestCase's own methods.
  // Not copied: testng_rt's TestNGIntegrationTest and TestNGModuleInfoIntegrationTest. They build on
  // AbstractTestFrameworkCompilingIntegrationTest from intellij.java.tests, which no published test framework has, and
  // download TestNG versions from Maven Central into a compiled test project.
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<JavaCompile>("compileRtJava") {
  options.release = 8
}

tasks.test {
  useJUnitPlatform()
  // The tests read testData (and the mock JDK in it) through the VFS, outside the roots a fresh checkout allows
  systemProperty("NO_FS_ROOTS_ACCESS_CHECK", "true")
}

// The IDE the tests run in still bundles IntelliJ's TestNG, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("TestNG-J")
}

// IntelliJ's mock JDKs for the light Java test projects (see MockJdks.kt)
useMockJdks()
