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
  // Ant's classes with its LICENSE.txt and NOTICE.txt (Apache 2.0 asks for the NOTICE to travel with it)
  from(provider { jpsAnt.map { zipTree(it) } }) { exclude("META-INF/MANIFEST.MF", "META-INF/INDEX.LIST", "META-INF/maven/**") }
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
    // What gradle-core and groovy depend on; a local plugin's own local plugins don't come with it
    localPlugin(project(":groovy-scripting"))
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
  testImplementation("org.mockito.kotlin:mockito-kotlin:6.1.0")
  testImplementation("org.junit-pioneer:junit-pioneer:2.3.0")
  // gradle-core's tests, which these build on, and what they compile against
  testImplementation(project(":gradle-core", "testClasses"))
  testImplementation(project(":gradle-core", "toolingJars"))
  testImplementation(gradleApi) { isTransitive = false }
  testImplementation("com.jetbrains.intellij.platform:external-system-test-framework:${providers.gradleProperty("platformTestBuild").get()}") {
    isTransitive = false
  }
}

// IntelliJ compiles intellij.gradle.jps for Java 11, the oldest JDK the build process runs on
tasks.named<JavaCompile>("compileJpsJava") { options.release = 11 }

// Upstream tests that are not compiled: they need IntelliJ test sources which are not published as artifacts (the
// external system and Groovy test bases, gradle-core's importing and code insight test bases, which are left out there
// for the same reason, the IDE metrics benchmark)
val testsNeedingUnpublishedTestSources = listOf<String>(
  "com/intellij/gradle/java/toml/tests/service/GradleTomlVersionCatalogEntrySearcherTest.kt",
  "org/jetbrains/jps/gradle/GradleJavaClasspathTest.java",
  "org/jetbrains/plugins/gradle/compiler/GradleCompilingTestCase.java",
  "org/jetbrains/plugins/gradle/compiler/GradleDelegatedBuildTest.java",
  "org/jetbrains/plugins/gradle/compiler/GradleDelegatedBuildTestCase.java",
  "org/jetbrains/plugins/gradle/compiler/GradleImprovedHotswapDetectionTest.java",
  "org/jetbrains/plugins/gradle/compiler/GradleJpsCompilingTestCase.java",
  "org/jetbrains/plugins/gradle/compiler/GradleJpsJavaCompilationTest.java",
  "org/jetbrains/plugins/gradle/compiler/GradleJpsResourceFilteringTest.java",
  "org/jetbrains/plugins/gradle/compiler/GradleJpsResourceProcessingTest.java",
  "org/jetbrains/plugins/gradle/compiler/GradleRelativeConfigCalculatingTest.kt",
  "org/jetbrains/plugins/gradle/compiler/GradleRelativeConfigCalculatingTestCase.kt",
  "org/jetbrains/plugins/gradle/dependencyAnalyzer/GradleDependencyNodeIndexTest.kt",
  "org/jetbrains/plugins/gradle/dependencyAnalyzer/GradleDependencyNodeIndexTestCase.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleActionTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleArtifactsTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleCompletionTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleConfigurationsTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleConventionsTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleDependenciesTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleDistributionsTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleDslPropertiesFileTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleExtensionsTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleHighlightingPerformanceTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleIdeaPluginTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleJavaExtensionsTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradlePluginDslStructureTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleProjectTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradlePublishingTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleRepositoriesTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleResolveTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleSourceSetsTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleTaskHighlightingTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleTasksTest.kt",
  "org/jetbrains/plugins/gradle/dsl/GradleWithGroovyTest.kt",
  "org/jetbrains/plugins/gradle/dsl/inspections/GradleAvoidDependencyNamedArgumentsNotationInspectionTest.kt",
  "org/jetbrains/plugins/gradle/dsl/inspections/GradleConfigurationAvoidanceInspectionTest.kt",
  "org/jetbrains/plugins/gradle/dsl/inspections/GradleDeprecatedConfigurationInspectionTest.kt",
  "org/jetbrains/plugins/gradle/dsl/inspections/GradleForeignDelegateInspectionTest.kt",
  "org/jetbrains/plugins/gradle/dsl/inspections/GradleLatestMinorVersionInspectionTest.kt",
  "org/jetbrains/plugins/gradle/dsl/inspections/GradleRedundantKotlinStdLibInspectionTest.kt",
  "org/jetbrains/plugins/gradle/dsl/inspections/GradleUnusedVersionCatalogInspectionTest.kt",
  "org/jetbrains/plugins/gradle/dsl/notations/GradleDependencyNotationTest.kt",
  "org/jetbrains/plugins/gradle/dsl/versionCatalogs/GradleVersionCatalogFixtures.kt",
  "org/jetbrains/plugins/gradle/dsl/versionCatalogs/GradleVersionCatalogsCompletionTest.kt",
  "org/jetbrains/plugins/gradle/dsl/versionCatalogs/GradleVersionCatalogsFindUsagesTest.kt",
  "org/jetbrains/plugins/gradle/dsl/versionCatalogs/GradleVersionCatalogsRenameTest.kt",
  "org/jetbrains/plugins/gradle/dsl/versionCatalogs/GradleVersionCatalogsResolveTest.kt",
  "org/jetbrains/plugins/gradle/dsl/versionCatalogs/TomlVersionCatalogCompletionTest.kt",
  "org/jetbrains/plugins/gradle/editor/GradleAttachSourcesProviderFilterTest.kt",
  "org/jetbrains/plugins/gradle/execution/GradleDebuggingIntegrationTestCase.kt",
  "org/jetbrains/plugins/gradle/execution/GradleGroovyScriptRunConfigurationProducerTest.kt",
  "org/jetbrains/plugins/gradle/execution/GradleGroovyScriptRunConfigurationProducerTestCase.kt",
  "org/jetbrains/plugins/gradle/execution/GradleRunConfigurationProducerTestCase.kt",
  "org/jetbrains/plugins/gradle/execution/GradleTasksExecutionTest.kt",
  "org/jetbrains/plugins/gradle/execution/build/GradleApplicationEnvironmentProviderTestCase.kt",
  "org/jetbrains/plugins/gradle/execution/build/GradleBuildOutputTest.kt",
  "org/jetbrains/plugins/gradle/execution/build/GradleExecutionOutputTest.kt",
  "org/jetbrains/plugins/gradle/execution/build/GradleProjectTaskRunnerTest.kt",
  "org/jetbrains/plugins/gradle/execution/build/GradleProjectTaskRunnerTestCase.kt",
  "org/jetbrains/plugins/gradle/execution/build/GradleSyncOutputTest.kt",
  "org/jetbrains/plugins/gradle/execution/build/GroovyGradleApplicationEnvironmentProviderTest.kt",
  "org/jetbrains/plugins/gradle/execution/build/GroovyGradleProjectTaskRunnerTest.kt",
  "org/jetbrains/plugins/gradle/execution/inspections/GradleLatestMinorVersionBuildIssueImportTest.kt",
  "org/jetbrains/plugins/gradle/execution/inspections/GradleLatestMinorVersionBuildIssueTaskExecutionTest.kt",
  "org/jetbrains/plugins/gradle/execution/test/ExternalTestsModelCompatibilityTest.kt",
  "org/jetbrains/plugins/gradle/execution/test/events/GradleRerunFailedTestsTest.kt",
  "org/jetbrains/plugins/gradle/execution/test/events/GradleRerunFailedTestsTestCase.kt",
  "org/jetbrains/plugins/gradle/execution/test/events/GradleSuiteWallTimeTest.kt",
  "org/jetbrains/plugins/gradle/execution/test/events/GradleTestAssertionTest.kt",
  "org/jetbrains/plugins/gradle/execution/test/events/GradleTestEventTest.kt",
  "org/jetbrains/plugins/gradle/execution/test/events/GradleTestExecutionTest.kt",
  "org/jetbrains/plugins/gradle/execution/test/events/GradleTestNavigationTest.kt",
  "org/jetbrains/plugins/gradle/execution/test/events/RobolectricGradleTestFilterTest.kt",
  "org/jetbrains/plugins/gradle/execution/test/events/SpockGradleTestNavigationTest.kt",
  "org/jetbrains/plugins/gradle/execution/test/producer/GradleTestRunConfigurationProducerTest.kt",
  "org/jetbrains/plugins/gradle/execution/test/producer/GradleTestRunConfigurationProducerTestCase.kt",
  "org/jetbrains/plugins/gradle/execution/test/producer/TestMethodGradleConfigurationProducerTest.kt",
  "org/jetbrains/plugins/gradle/execution/test/producer/TestMethodGradleConfigurationProducerTestCase.kt",
  "org/jetbrains/plugins/gradle/importing/AnnotationProcessorConfigImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleBuildSrcImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleClassFinderTest.java",
  "org/jetbrains/plugins/gradle/importing/GradleExtensionsImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleExternalProjectImportingTest.java",
  "org/jetbrains/plugins/gradle/importing/GradleFindUsagesTest.java",
  "org/jetbrains/plugins/gradle/importing/GradleImportActionTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleJavaCompilerSettingsImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleJavaCompilerSettingsImportingTestCase.kt",
  "org/jetbrains/plugins/gradle/importing/GradleJavaImportingTestCase.kt",
  "org/jetbrains/plugins/gradle/importing/GradleJavaOutputParsersMessagesImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleJavaProjectResolverPerformanceTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleJavaProjectResolverTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleJavaSettingsImportingTest.java",
  "org/jetbrains/plugins/gradle/importing/GradleJavaSyncPerformanceTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleMavenRepositoriesImportTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleMiscImportingTest.java",
  "org/jetbrains/plugins/gradle/importing/GradleModulesInProjectViewTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleProjectRepositoriesImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleToolWindowTest.kt",
  "org/jetbrains/plugins/gradle/importing/TestGradleConfigurationProducerUtilTest.kt",
  "org/jetbrains/plugins/gradle/quarantine/dsl/GradleHighlightingTest.kt",
  "org/jetbrains/plugins/gradle/quarantine/dsl/GradleManagedPropertyTest.kt",
  "org/jetbrains/plugins/gradle/quarantine/dsl/GradleProjectReferenceTest.kt",
  "org/jetbrains/plugins/gradle/quarantine/execution/GradleDebuggingIntegrationTest.kt",
  "org/jetbrains/plugins/gradle/quarantine/execution/build/GradleApplicationEnvironmentProviderTest.java",
  "org/jetbrains/plugins/gradle/service/project/GradleTasksIndicesTest.kt",
  "org/jetbrains/plugins/gradle/service/project/GradleTasksIndicesTestCase.kt",
  "org/jetbrains/plugins/gradle/service/project/workspace/ExternalProjectsWorkspaceIntegrationTestCase.kt",
  "org/jetbrains/plugins/gradle/service/project/workspace/GradleMavenProjectsWorkspaceIntegrationTest.kt",
  "org/jetbrains/plugins/gradle/service/project/workspace/GradleProjectsWorkspaceIntegrationTest.kt",
  "org/jetbrains/plugins/gradle/service/project/workspace/MavenProjectsWorkspaceIntegrationTest.kt",
  "org/jetbrains/plugins/gradle/service/resolve/GradleClosureResolveTest.kt",
  "org/jetbrains/plugins/gradle/service/resolve/GradlePluginReferenceResolveTest.kt",
  "org/jetbrains/plugins/gradle/setup/GradleJavaNewProjectWizardTest.kt",
  "org/jetbrains/plugins/gradle/setup/GradleJavaNewProjectWizardTestCase.kt",
  "org/jetbrains/plugins/gradle/setup/GradleNewProjectWizardTestCase.kt",
  "org/jetbrains/plugins/gradle/setup/GradleOpenProjectTest.kt",
  "org/jetbrains/plugins/gradle/setup/GradleOpenProjectTestCase.kt",
  "org/jetbrains/plugins/gradle/setup/GradleUntrustedProjectTest.kt",
  "org/jetbrains/plugins/gradle/setup/GradleUntrustedProjectTestCase.kt",
  "org/jetbrains/plugins/gradle/testFramework/GradleTestExecutionBaseTestCase.kt",
  "org/jetbrains/plugins/gradle/testFramework/GradleTestExecutionTestCase.kt",
  "org/jetbrains/plugins/gradle/testFramework/util/GradleTestConsoleUtil.kt",
  "org/jetbrains/plugins/gradle/testFramework/util/TestProxyUtil.kt",
)
sourceSets.test {
  java.exclude(testsNeedingUnpublishedTestSources)
  kotlin.exclude(testsNeedingUnpublishedTestSources)
}

tasks.test {
  useJUnitPlatform()
  filter {
    // They resolve JUnit from the IntelliJ project files of an IntelliJ checkout (IntelliJProjectConfiguration)
    excludeTest("org.jetbrains.plugins.gradle.util.GradleExecutionSettingsUtilTest", "test filter generation for the Junit 5 parametrized tests")
    excludeTest("org.jetbrains.plugins.gradle.util.GradleExecutionSettingsUtilTest", "test filter generation for the Junit 4 parametrized tests")
  }
}

// The IDE the tests run in still bundles IntelliJ's Gradle, Gradle DSL API, Groovy and Groovy Scripting, which claim the
// ids this plugin, gradle-core, gradle-dsl, groovy and groovy-scripting keep as aliases
tasks.prepareTestSandbox {
  disabledPlugins.addAll(
    "com.intellij.gradle",
    "org.jetbrains.plugins.gradle",
    "org.jetbrains.idea.gradle.dsl",
    "org.intellij.groovy",
    "com.intellij.groovy.scripting",
  )
}
