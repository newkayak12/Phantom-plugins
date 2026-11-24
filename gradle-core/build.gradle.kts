import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

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
// The test classes for gradle-java's tests, which build on them (intellij.gradle.java.tests depends on intellij.gradle.tests)
val testClasses by configurations.creating {
  isCanBeConsumed = true
  isCanBeResolved = false
}
artifacts {
  sourceSets.test.get().output.classesDirs.forEach { add("testClasses", it) { builtBy(tasks.named("testClasses")) } }
  add("testClasses", tasks.named<ProcessResources>("processTestResources").map { it.destinationDir }) {
    builtBy(tasks.named("processTestResources"))
  }
  add("toolingJars", toolingExtensionJar)
  add("toolingJars", toolingExtensionImplJar)
  add("toolingJars", toolingProxyJar)
}

// The Gradle API and Tooling API IntelliJ builds against (the project library "Gradle"), published by JetBrains in the
// IntelliJ dependencies repository. Packed into lib/ as gradle-api-<version>.jar, as in IntelliJ.
val gradleApi = "org.jetbrains.intellij.deps:gradle-api:9.6.0"

// The content modules with a class loader of their own: IntelliJ packs each into lib/modules/<module>.jar, and a module
// without a package attribute can only tell its classes apart by jar. Each jar holds the module's packages (classes
// directly in them; subpackages may belong to another module) and its descriptor, out of the plugin jar.
val separateModules = mapOf(
  "intellij.gradle.completion" to listOf(
    "com.intellij.gradle.completion",
    "com.intellij.gradle.completion.indexer",
    "com.intellij.gradle.completion.lookup",
  ),
  "intellij.gradle.completion.toml" to listOf("com.intellij.gradle.completion.toml"),
  "intellij.gradle.properties" to listOf("com.intellij.gradle.properties"),
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
    // The upstream tests import Java projects (intellij.gradle.tests depends on the Java plugin); the plugin itself doesn't
    testBundledPlugin("com.intellij.java")
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
  testImplementation("org.mockito.kotlin:mockito-kotlin:6.1.0")
  testImplementation("org.junit-pioneer:junit-pioneer:2.3.0")
  // ExternalSystemTestCase and the external system test fixtures the Gradle tests build on
  testImplementation("com.jetbrains.intellij.platform:external-system-test-framework:${providers.gradleProperty("platformTestBuild").get()}") {
    isTransitive = false
  }
}

// Java 8 bytecode for the jars that run inside the Gradle daemon (IntelliJ: jvmTarget 1.8 for these modules)
listOf("toolingExtension", "toolingExtensionImpl", "toolingProxy").forEach { set ->
  tasks.named<JavaCompile>("compile${set.replaceFirstChar { it.uppercase() }}Java") { options.release = 8 }
  tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compile${set.replaceFirstChar { it.uppercase() }}Kotlin") {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
  }
}

// Upstream tests that are not compiled: they need IntelliJ test sources which are not published as artifacts (the
// external system data index and environment test bases, the Groovy and code insight test bases, the multi-project
// fixtures, the IDE metrics benchmark), or classes of Gradle for Java (gradle-java), which builds on this plugin
val testsNeedingUnpublishedTestSources = listOf<String>(
  "com/intellij/gradle/completion/toml/tests/GradleTomlCompletionFusPositionTest.kt",
  "com/intellij/gradle/completion/toml/tests/GradleTomlDependenciesAutoPopupHandlerTest.kt",
  "com/intellij/gradle/completion/toml/tests/GradleTomlInsertionTest.kt",
  "org/jetbrains/plugins/gradle/daemon/GradleDaemonActionTest.kt",
  "org/jetbrains/plugins/gradle/execution/GradleOperationDescriptorPropagationTest.kt",
  "org/jetbrains/plugins/gradle/execution/GradleRunAnythingProviderTestCase.kt",
  "org/jetbrains/plugins/gradle/importing/BuildViewMessagesImportingTestCase.kt",
  "org/jetbrains/plugins/gradle/importing/GradleActionWithImportTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleActionWithImportTestCase.kt",
  "org/jetbrains/plugins/gradle/importing/GradleAutoImportAwareTest.java",
  "org/jetbrains/plugins/gradle/importing/GradleAuxiliaryDependencyImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleAuxiliaryDependencyImportingTestCase.kt",
  "org/jetbrains/plugins/gradle/importing/GradleBuildIssueImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleBuildIssuesMiscImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleCompositeImportingTest.java",
  "org/jetbrains/plugins/gradle/importing/GradleConnectorServiceIntegrationTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleDCLImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleDaemonJvmCriteriaTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleDependenciesImportingTest.java",
  "org/jetbrains/plugins/gradle/importing/GradleEnvironmentTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleFoldersImportingTest.java",
  "org/jetbrains/plugins/gradle/importing/GradleImportingTestCase.kt",
  "org/jetbrains/plugins/gradle/importing/GradleOutputParsersMessagesImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleOutputParsersMessagesImportingTestCase.kt",
  "org/jetbrains/plugins/gradle/importing/GradleProjectSdkResolverTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleProjectSdkResolverTestCase.kt",
  "org/jetbrains/plugins/gradle/importing/GradleSettingsImportingTest.java",
  "org/jetbrains/plugins/gradle/importing/GradleSettingsImportingTestCase.java",
  "org/jetbrains/plugins/gradle/importing/GradleSyncOutputFailureTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleSystemPropertiesTest.kt",
  "org/jetbrains/plugins/gradle/importing/GradleTasksImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/LegacyGradleAuxiliaryDependencyImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/UnsupportedGradleImportingTest.kt",
  "org/jetbrains/plugins/gradle/importing/syncAction/GradleContentRootSyncContributorTest.kt",
  "org/jetbrains/plugins/gradle/importing/syncAction/GradlePartialSyncTest.kt",
  "org/jetbrains/plugins/gradle/importing/syncAction/GradlePartialSyncTestCase.kt",
  "org/jetbrains/plugins/gradle/importing/syncAction/GradlePhasedSyncTestCase.kt",
  "org/jetbrains/plugins/gradle/importing/syncAction/GradleProjectResolverTestCase.kt",
  "org/jetbrains/plugins/gradle/importing/syncAction/GradleProjectRootSyncContributorTest.kt",
  "org/jetbrains/plugins/gradle/importing/syncAction/GradleResilientSyncTest.kt",
  "org/jetbrains/plugins/gradle/importing/syncAction/GradleSourceRootSyncContributorTest.kt",
  "org/jetbrains/plugins/gradle/importing/syncAction/GradleVersionCatalogSyncContributorTest.kt",
  "org/jetbrains/plugins/gradle/issue/GradleBuildCancelledIssueCheckerTest.kt",
  "org/jetbrains/plugins/gradle/issue/GradleDaemonStartupIssueCheckerTest.kt",
  "org/jetbrains/plugins/gradle/issue/GradleInvalidDaemonJvmCriteriaIssueCheckerTest.kt",
  "org/jetbrains/plugins/gradle/issue/GradleOutOfMemoryIssueCheckerTest.kt",
  "org/jetbrains/plugins/gradle/issue/GradleToolchainDownloadedMismatchCriteriaIssueCheckerTest.kt",
  "org/jetbrains/plugins/gradle/issue/GradleToolchainDownloadingErrorIssueCheckerTest.kt",
  "org/jetbrains/plugins/gradle/issue/GradleToolchainInstalledNotFoundMatchingCriteriaIssueCheckerTest.kt",
  "org/jetbrains/plugins/gradle/quarantine/execution/GradleRunAnythingProviderTest.kt",
  "org/jetbrains/plugins/gradle/quarantine/importing/syncAction/GradlePhasedSyncTest.kt",
  "org/jetbrains/plugins/gradle/service/GradleInstallationManagerIoTest.kt",
  "org/jetbrains/plugins/gradle/service/GradleInstallationManagerTest.kt",
  "org/jetbrains/plugins/gradle/service/GradleInstallationManagerTestCase.kt",
  "org/jetbrains/plugins/gradle/service/cache/GradleLocalCacheTest.kt",
  "org/jetbrains/plugins/gradle/service/execution/GradleExecutionReporterTest.kt",
  "org/jetbrains/plugins/gradle/service/execution/helper/GradleExecutionHelperJvmArgumentsTest.kt",
  "org/jetbrains/plugins/gradle/service/execution/helper/GradleExecutionHelperJvmArgumentsTestCase.kt",
  "org/jetbrains/plugins/gradle/service/project/GradleModuleDataIndexPerformanceTest.kt",
  "org/jetbrains/plugins/gradle/service/project/GradleModuleDataIndexTest.kt",
  "org/jetbrains/plugins/gradle/service/project/GradleModuleDataIndexTestCase.kt",
  "org/jetbrains/plugins/gradle/service/task/GradleTaskManagerTest.kt",
  "org/jetbrains/plugins/gradle/testFramework/GradleCodeInsightBaseTestCase.kt",
  "org/jetbrains/plugins/gradle/testFramework/GradleCodeInsightTestCase.kt",
  "org/jetbrains/plugins/gradle/testFramework/GradleExecutionBaseTestCase.kt",
  "org/jetbrains/plugins/gradle/testFramework/GradleExecutionTestCase.kt",
  "org/jetbrains/plugins/gradle/testFramework/GradleProjectBaseTestCase.kt",
  "org/jetbrains/plugins/gradle/testFramework/GradleProjectTestCase.kt",
  "org/jetbrains/plugins/gradle/testFramework/GradleTestCase.kt",
  "org/jetbrains/plugins/gradle/testFramework/GradleTestFixtureBuilder.kt",
  "org/jetbrains/plugins/gradle/testFramework/fixtures/fixtures.kt",
  "org/jetbrains/plugins/gradle/testFramework/fixtures/impl/FileTestFixtureImpl.kt",
  "org/jetbrains/plugins/gradle/testFramework/fixtures/impl/GradleProjectTestFixtureImpl.kt",
  "org/jetbrains/plugins/gradle/testFramework/fixtures/impl/GradleTestFixtureImpl.kt",
  "org/jetbrains/plugins/gradle/testFramework/projectInfo/GradleProjectInfoUtil.kt",
  "org/jetbrains/plugins/gradle/testFramework/util/GradleFileTestUtil.kt",
  "org/jetbrains/plugins/gradle/testFramework/util/GradleImportingFileTestUtil.kt",
  "org/jetbrains/plugins/gradle/tooling/serialization/ToolingSerializerTest.kt",
  "org/jetbrains/plugins/gradle/util/GradleBuildScriptFileTest.kt",
  "org/jetbrains/plugins/gradle/util/GradleContentRootContributorTest.java",
  "org/jetbrains/plugins/gradle/util/GradleDirectoryCompletionContributorTest.java",
  "org/jetbrains/plugins/gradle/util/GradleJdkComboBoxUtilTest.kt",
  "org/jetbrains/plugins/gradle/util/GradleJdkComboBoxUtilTestCase.kt",
  "org/jetbrains/plugins/gradle/util/GradleJdkNonblockingUtilTest.kt",
  "org/jetbrains/plugins/gradle/util/GradleJdkNonblockingUtilTestCase.kt",
  "org/jetbrains/plugins/gradle/util/GradleJdkResolutionTest.kt",
  "org/jetbrains/plugins/gradle/util/GradleJdkResolutionTestCase.kt",
  "org/jetbrains/plugins/gradle/util/RichTextControlBuilderTest.java",
)
sourceSets.test {
  java.exclude(testsNeedingUnpublishedTestSources)
  kotlin.exclude(testsNeedingUnpublishedTestSources)
}

tasks.test {
  useJUnitPlatform()
  filter {
    // The Gradle/JVM compatibility feed update is off by default in Phantom (gradle.compatibility.update.interval 0, see
    // intellij.gradle.xml), so these tests of the update find no request made
    excludeTest("org.jetbrains.plugins.gradle.util.GradleJvmUpdateMatricesTest", "test update configuration")
    excludeTest("org.jetbrains.plugins.gradle.util.GradleJvmUpdateMatricesTest", "test should not update configuration twice")
    excludeTest("org.jetbrains.plugins.gradle.util.GradleJvmUpdateMatricesTest", "test update configuration for appropriate idea version")
  }
}

// The IDE the tests run in still bundles IntelliJ's Gradle and Groovy Scripting, which claim the ids this plugin and
// groovy-scripting keep as aliases
tasks.prepareTestSandbox {
  disabledPlugins.add("com.intellij.gradle")
  disabledPlugins.add("org.jetbrains.plugins.gradle")
  disabledPlugins.add("com.intellij.groovy.scripting")
}
