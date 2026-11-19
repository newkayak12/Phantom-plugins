import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension

plugins {
  id("phantom-plugin")
}

version = "0.1.0"

// The IntelliJ sources keep Java next to Kotlin. src/rt, src/rt5 and src/rt6 are junit-rt.jar (intellij.junit.rt),
// junit5-rt.jar (intellij.junit.v5.rt) and junit6-rt.jar (intellij.junit.v6.rt): the starter, runners and listeners that
// run inside the user's test JVM, each a jar of its own in lib/ under the name IntelliJ gives it. The run configuration
// puts them on the test classpath, found through their JUnit4/5/6IdeaTestRunner classes (TestObject.getJUnitRtFile), so
// they don't depend on the plugin's folder name.
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
  create("rt")
  create("rt5")
  create("rt6")
}

val ideaRtJar = files(provider {
  project.extensions.getByType<IntelliJPlatformExtension>().platformPath.resolve("lib/idea_rt.jar").toFile()
})

val rtJar by tasks.registering(Jar::class) {
  archiveFileName = "junit-rt.jar"
  destinationDirectory = layout.buildDirectory.dir("rtJar")
  // classes only: the platform plugin adds the patched plugin.xml to every source set's resources
  from(sourceSets["rt"].output.classesDirs)
}

val rt5Jar by tasks.registering(Jar::class) {
  archiveFileName = "junit5-rt.jar"
  destinationDirectory = layout.buildDirectory.dir("rtJar")
  from(sourceSets["rt5"].output.classesDirs)
}

val rt6Jar by tasks.registering(Jar::class) {
  archiveFileName = "junit6-rt.jar"
  destinationDirectory = layout.buildDirectory.dir("rtJar")
  from(sourceSets["rt6"].output.classesDirs)
}

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    bundledPlugin("com.intellij.properties") // junit-properties.xml, optional
    bundledPlugin("intellij.testRunner.plugin") // SMRunnerConsolePropertiesProvider (intellij.platform.smRunner)
    testBundledPlugin("org.intellij.groovy") // groovy-tests (GroovyJUnitDetectionTest, GroovyJUnit3NamingConventionInspectionTest)
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
  implementation(files(rtJar))
  implementation(files(rt5Jar))
  implementation(files(rt6Jar))

  // The rt classes compile against idea_rt.jar (intellij.java.rt, in the IDE's lib/) and the JUnit version they drive;
  // the user's project brings JUnit itself, so none of it is packaged
  "rtCompileOnly"(ideaRtJar)
  "rtCompileOnly"("junit:junit:4.13.2")
  "rt5CompileOnly"(ideaRtJar)
  "rt5CompileOnly"(files(rtJar))
  "rt5CompileOnly"("org.junit.platform:junit-platform-launcher:1.14.4")
  "rt5CompileOnly"("org.junit.jupiter:junit-jupiter-api:5.14.4")
  "rt6CompileOnly"(ideaRtJar)
  "rt6CompileOnly"(files(rtJar, rt5Jar))
  "rt6CompileOnly"("org.junit.platform:junit-platform-launcher:6.0.0")
  "rt6CompileOnly"("org.junit.jupiter:junit-jupiter-api:6.0.0")

  // Tests: plugins/junit's java-tests and groovy-tests, its testFramework and jvm/jvm-analysis-testFramework (test base
  // classes no published test framework has, copied into src/test), and the code-insight tests of junit5_rt_tests and
  // junit6_rt_tests. They run as JUnit 3/4 (vintage engine) and JUnit Jupiter.
  // Not copied:
  // - kotlin-tests-shared and kotlin-tests-k2: they build on the Kotlin plugin's own test framework
  //   (org.jetbrains.kotlin.idea.test, intellij.kotlin.base.testFramework), which isn't published.
  // - java-tests' JUnitConfigurationTest, JUnitContextConfigurationTest, JUnitConfigurationRefactoringsTest,
  //   JUnitModulePathTest (with their testData/configuration) and JUnitPackageConfigurationProducerTest: they extend
  //   BaseConfigurationTestCase from intellij.java.tests, which no published test framework has, and take JUnit from the
  //   IntelliJ monorepo's library setup (IntelliJProjectConfiguration reads its .idea/libraries).
  // - java-tests' JUnitTestRunLineMarkerTest: it extends LineMarkerTestCase from intellij.java.tests.
  // - junit5_rt_tests' JUnit5EventsTest, JUnit5NavigationTest and their JUnit5TestRunnerBuilder: they drive junit5-rt
  //   through JUnit Jupiter 5.14's engine internals (DefaultJupiterConfiguration, ClassTestDescriptor), while the
  //   platform's test framework runs on JUnit 6, whose engine changed those.
  // - the *IntegrationTest classes of junit5_rt_tests and junit6_rt_tests (and JUnit6EventsTest, JUnit6NamingTest,
  //   JUnit6NavigationTest): they build on AbstractTestFrameworkCompilingIntegrationTest from intellij.java.tests and
  //   compile and run test projects with JUnit versions downloaded from Maven.
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testImplementation("org.junit.platform:junit-platform-launcher")
  testImplementation("org.hamcrest:hamcrest:2.2")
  testImplementation("org.assertj:assertj-core:3.27.6")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
}

tasks.named<JavaCompile>("compileRtJava") {
  options.release = 8
}

tasks.named<JavaCompile>("compileRt5Java") {
  options.release = 8
}

tasks.named<JavaCompile>("compileRt6Java") {
  options.release = 17
}

tasks.test {
  useJUnitPlatform()
}

// The IDE the tests run in still bundles IntelliJ's JUnit, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("JUnit")
}

// The test runners (src/rt*) run in the user's test JVM, where JUnit comes from the project, not from the IDE
intellijPlatform.pluginVerification.externalPrefixes.addAll("junit", "org.junit")
