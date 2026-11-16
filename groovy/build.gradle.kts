import java.util.Properties
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
  id("phantom-plugin")
}

version = "0.1.0"

// The IntelliJ sources keep Java next to Kotlin. src/main is intellij.groovy, intellij.groovy.psi (with its generated lexers
// and parser), intellij.groovy.structuralSearch and the three content modules (git, ant, copyright). The
// other source sets are jars of their own, named and placed as IntelliJ does (CommunityRepositoryModules.groovyPlugin):
// - jps: lib/groovy-jps.jar, the JPS builder, which the build process loads (compileServer.plugin in plugin.xml names it,
//   BuildProcessClasspathManager finds it in <plugin dir>/lib); it puts groovy-rt.jar, groovy-constants-rt.jar and
//   groovy-rt-class-loader.jar next to itself on the Groovy compiler's classpath (GroovyRtJarPaths).
// - rt, constantsRt, rtClassLoader: lib/groovy-rt.jar, groovy-constants-rt.jar, groovy-rt-class-loader.jar, which run in the
//   Groovy compiler process and the user's Groovy scripts (Java 8).
// - spockRt: lib/groovy-spock-rt.jar, added to Spock test runs (SpockRunConfigurationExtension finds it by class).
// All of them are found relative to a jar of this plugin, never by the plugin's folder name.
// Tests left out (not copied would hide them; they stay in src/test, excluded here with the reason):
// - the slicer tests but GroovySliceTreeGroupingTest: they build on SliceTestUtil from intellij.java.tests, which no
//   published test framework has
val excludedTests = listOf(
  "org/jetbrains/plugins/groovy/lang/slicer/GroovySliceTestCase.kt",
  "org/jetbrains/plugins/groovy/lang/slicer/GroovySliceForwardTest.kt",
  "org/jetbrains/plugins/groovy/lang/slicer/GroovySliceBackwardTest.kt",
)

sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
  create("jps")
  create("rt")
  create("constantsRt")
  create("rtClassLoader")
  create("spockRt")
}
sourceSets.test {
  java.exclude(excludedTests)
  kotlin.exclude(excludedTests)
}

val platformPath = provider { project.extensions.getByType<IntelliJPlatformExtension>().platformPath }
fun platformJars(vararg dirs: String) = files(provider {
  dirs.flatMap { dir -> platformPath.get().resolve(dir).toFile().walkTopDown().filter { it.isFile && it.name.endsWith(".jar") }.toList() }
})
fun platformFile(path: String) = files(provider { platformPath.get().resolve(path).toFile() })

fun rtJar(name: String, sourceSet: String) = tasks.register<Jar>("${sourceSet}Jar") {
  archiveFileName = name
  destinationDirectory = layout.buildDirectory.dir("rtJar")
  // classes and own resources only: the platform plugin adds the patched plugin.xml to every source set's resources
  from(sourceSets[sourceSet].output.classesDirs)
  from(layout.projectDirectory.dir("src/$sourceSet/resources"))
}

val constantsRtJar = rtJar("groovy-constants-rt.jar", "constantsRt")
val rtJar = rtJar("groovy-rt.jar", "rt")
val rtClassLoaderJar = rtJar("groovy-rt-class-loader.jar", "rtClassLoader")
val spockRtJar = rtJar("groovy-spock-rt.jar", "spockRt")
val jpsJar = rtJar("groovy-jps.jar", "jps")

// The content modules with a jar of their own in lib/modules (their classes and descriptor, out of the plugin jar)
val separateModules = mapOf(
  "intellij.groovy.ant" to "com/intellij/groovy/ant",
  "intellij.groovy.copyright" to "org/intellij/groovy/copyright",
)
val moduleJars = separateModules.map { (module, pkg) ->
  tasks.register<Jar>("${module.substringAfterLast('.')}ModuleJar") {
    archiveFileName = "$module.jar"
    destinationDirectory = layout.buildDirectory.dir("moduleJars")
    from(sourceSets.main.get().output.classesDirs) { include("$pkg/**") }
    from(layout.projectDirectory.file("src/main/resources/$module.xml"))
  }
}

tasks.jar {
  separateModules.forEach { (module, pkg) ->
    exclude("$pkg/**")
    exclude("$module.xml")
  }
}

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    bundledPlugin("com.intellij.properties")
    bundledPlugin("com.intellij.copyright")
    bundledPlugin("JUnit")
    bundledPlugin("Git4Idea")
    bundledPlugin("intellij.structuralSearch.plugin")
    bundledModule("intellij.platform.langInjection")
    bundledModule("intellij.spellchecker")
    bundledModule("intellij.regexp")
    // Ant isn't bundled in IntelliJ IDEA Community 262 or in Phantom; JetBrains publishes it on the Marketplace, and the
    // Ant integration (intellij.groovy.ant) compiles against that
    plugin("AntSupport", "262.8665.176")
    // Groovy itself (intellij.libraries.groovy) comes from Phantom Groovy Scripting, which this plugin depends on
    localPlugin(project(":groovy-scripting"))
    // GroovyScriptMacroTest (in IntelliJ's Groovy tests) tests Groovy Live Templates
    testLocalPlugin(project(":groovy-live-templates"))
    // Grazie for the spellchecker tests, the benchmark framework for the performance tests
    testBundledPlugin("tanvd.grazi")
    testFramework(TestFrameworkType.Metrics, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
  // localPlugin doesn't put the Groovy Scripting libraries on the compile classpath: the same Groovy, compile only
  compileOnly("org.codehaus.groovy:groovy:3.0.25") { isTransitive = false }
  compileOnly(platformFile("plugins/java/lib/ecj/eclipse.jar"))
  implementation(files(jpsJar, rtJar, constantsRtJar, rtClassLoaderJar, spockRtJar))

  "constantsRtCompileOnly"(platformFile("lib/annotations.jar"))
  "rtCompileOnly"(platformFile("lib/annotations.jar"))
  "rtCompileOnly"(files(constantsRtJar))
  "rtCompileOnly"("org.codehaus.groovy:groovy:3.0.25") { isTransitive = false }
  "rtClassLoaderCompileOnly"(platformFile("lib/annotations.jar"))
  "rtClassLoaderCompileOnly"(platformFile("lib/util_rt.jar"))
  "spockRtCompileOnly"(platformFile("plugins/junit/lib/junit-rt.jar"))
  "spockRtCompileOnly"("org.spockframework:spock-core:2.1-groovy-3.0") { isTransitive = false }
  "spockRtCompileOnly"("org.junit.platform:junit-platform-engine:1.8.1") { isTransitive = false }
  "spockRtCompileOnly"("junit:junit:4.13.2")
  "spockRtCompileOnly"("org.opentest4j:opentest4j:1.3.0")
  // The JPS builder compiles against the platform's and the Java plugin's JPS classes (and ECJ, for Greclipse), which the
  // build process has
  "jpsCompileOnly"(files(constantsRtJar))
  "jpsCompileOnly"(platformJars("lib", "plugins/java/lib"))

  // groovy-uast-tests build on the UAST test framework, published on its own
  testImplementation("com.jetbrains.intellij.platform:uast-test-framework:262.10968.63") { isTransitive = false }
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testImplementation("org.junit.platform:junit-platform-launcher")
  testImplementation("org.assertj:assertj-core:3.27.6")
  testImplementation("org.jetbrains:jetCheck:0.2.2")
  testImplementation("org.codehaus.groovy:groovy:3.0.25") { isTransitive = false }
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("net.bytebuddy:byte-buddy:1.17.7")
}

listOf("compileRtJava", "compileConstantsRtJava", "compileRtClassLoaderJava", "compileSpockRtJava").forEach {
  tasks.named<JavaCompile>(it) { options.release = 8 }
}
tasks.named<JavaCompile>("compileJpsJava") { options.release = 11 }

// The Groovy plugin jar is lib/Groovy.jar, as in IntelliJ
tasks.composedJar {
  archiveFileName = "Groovy.jar"
}

// lib/standardDsls (GroovyDslFileIndex reads them next to the jar holding GdslScriptProvider), lib/console.groovy (the
// Groovy console's script, next to the jar holding GroovyLanguage: GroovyScriptRunner.getPathInConf), lib/agent/gragent.jar
// (the debugger's hot-swap agent, under the plugin's own folder: GroovyHotSwapper) and lib/modules; for the IDE, the test
// IDE and the distribution zip
tasks.withType<PrepareSandboxTask>().configureEach {
  from(layout.projectDirectory.dir("dist")) {
    into(pluginName.map { "$it/lib" })
  }
  from(moduleJars) {
    into(pluginName.map { "$it/lib/modules" })
  }
}

// The Maven libraries the tests put in their test projects (RepositoryTestLibrary, GroovyProjectDescriptors and the like).
// IntelliJ resolves them at test time from its monorepo's repositories; here Gradle resolves them, with their transitive
// dependencies as there, and writes their jars to a file the tests read (groovy.test.libraries).
val testLibraryCoordinates = listOf(
  "org.codehaus.groovy:groovy:1.6.9",
  "org.codehaus.groovy:groovy:1.7.11",
  "org.codehaus.groovy:groovy-all:2.1.0",
  "org.codehaus.groovy:groovy:2.2.0",
  "org.codehaus.groovy:groovy:2.3.0",
  "org.codehaus.groovy:groovy-all:2.4.17",
  "org.codehaus.groovy:groovy:2.5.23",
  "org.codehaus.groovy:groovy:3.0.20",
  "org.apache.groovy:groovy:4.0.18",
  "org.apache.groovy:groovy:5.0.0-beta-1",
  "org.apache.groovy:groovy-ginq:4.0.0",
  "org.codehaus.gant:gant_groovy1.7:1.9.7",
  "ch.qos.logback:logback-classic:1.2.3",
  "org.spockframework:spock-core:2.0-groovy-2.5",
  "org.spockframework:spock-core:2.0-groovy-3.0",
  "org.spockframework:spock-core:2.4-groovy-4.0",
)
val testLibraries: Map<String, FileCollection> = testLibraryCoordinates.associateWith {
  configurations.detachedConfiguration(dependencies.create(it))
}
val testLibrariesFile = layout.buildDirectory.file("testLibraries.properties")
val writeTestLibraries by tasks.registering {
  testLibraries.values.forEach { inputs.files(it) }
  outputs.file(testLibrariesFile)
  doLast {
    val properties = Properties()
    testLibraries.forEach { (coordinates, jars) -> properties[coordinates] = jars.files.joinToString(File.pathSeparator) }
    testLibrariesFile.get().asFile.writer().use { properties.store(it, null) }
  }
}

tasks.test {
  useJUnitPlatform()
  dependsOn(writeTestLibraries)
  systemProperty("groovy.test.libraries", testLibrariesFile.get().asFile.absolutePath)
  // Not run:
  // - the suites (AllGroovyTestSuite, FastGroovyTestSuite, SlowGroovyTestSuite, GroovyParserTestSuite,
  //   GroovyGinqTestSuite): they gather test classes Gradle runs anyway, two of them through the IntelliJ monorepo's
  //   TestCaseLoader and test groups.
  // - the compiler tests' testExtendGroovyClassesWithAdditionalDependencies: it takes JUnit 4 from the IntelliJ monorepo's
  //   project libraries (IntelliJProjectConfiguration reads its .idea).
  // - GroovySanityTest: it edits random .groovy files found under the IDE home, which in the IntelliJ monorepo are its
  //   sources; an installed IDE has none.
  // - DsldTest.testMeta: it reads standardDsls/metaDsl.gdsl as a class path resource, which it is only in IntelliJ's
  //   development layout; the plugin ships standardDsls next to its jar (lib/standardDsls), as IntelliJ's distribution does.
  exclude("org/jetbrains/plugins/groovy/*TestSuite*", "org/jetbrains/plugins/groovy/GroovySanityTest*")
  filter {
    excludeTestsMatching("org.jetbrains.plugins.groovy.compiler.*.testExtendGroovyClassesWithAdditionalDependencies")
    excludeTestsMatching("org.jetbrains.plugins.groovy.dsl.DsldTest.testMeta")
  }
  // The mock JDKs 1.7 and 1.8 the light test projects use, in testData/ideaHome (see PhantomTestExecutionPolicy)
  systemProperty("idea.test.execution.policy", "org.jetbrains.plugins.groovy.util.PhantomTestExecutionPolicy")
  // The tests read testData through the VFS, outside the roots a fresh checkout allows
  systemProperty("NO_FS_ROOTS_ACCESS_CHECK", "true")
}

// The IDE the tests run in still bundles IntelliJ's Groovy, Groovy Scripting and Groovy Live Templates, which claim the ids
// these plugins keep as aliases
tasks.prepareTestSandbox {
  disabledPlugins.addAll("org.intellij.groovy", "com.intellij.groovy.scripting", "org.intellij.groovy.live.templates")
}
