import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.1"

dependencies {
  intellijPlatform {
    // common builds on the Maven plugin's model module
    bundledPlugin("org.jetbrains.idea.maven")
    bundledModule("intellij.libraries.ktor.client")
    bundledModule("intellij.libraries.kotlinx.io")
    bundledModule("intellij.libraries.kotlinx.coroutines.slf4j")
    bundledModule("intellij.libraries.mvstore")
    bundledModule("intellij.platform.externalSystem")
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
  // Packaged in the plugin, as IntelliJ's package-search-api-client library and kotlinx-document-store-mvstore module are.
  // Their own dependencies (Ktor, kotlinx.serialization, kotlinx-datetime, H2 MVStore) come with the platform.
  listOf(
    "org.jetbrains.packagesearch:packagesearch-api-client-jvm:3.4.0",
    "org.jetbrains.packagesearch:packagesearch-http-models-jvm:3.4.0",
    "org.jetbrains.packagesearch:packagesearch-api-models-jvm:3.4.0",
    "org.jetbrains.packagesearch:packagesearch-version-utils-jvm:3.4.0",
    "com.soywiz.korlibs.krypto:krypto-jvm:4.0.10",
    "com.github.lamba92:kotlinx-document-store-mvstore:0.0.4",
    "com.github.lamba92:kotlinx-document-store-core-jvm:0.0.4",
  ).forEach { implementation(it) { isTransitive = false } }

  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("org.assertj:assertj-core:3.27.6")
}

tasks.test {
  useJUnitPlatform()
}

// common and completion are jars of their own in lib/modules, as IntelliJ ships them: a content module found there gets
// a class loader over that jar alone. Their classes and descriptors stay out of the main jar.
val moduleContents = mapOf(
  "intellij.repository.search.common" to listOf("org/jetbrains/idea/**", "messages/dependencySearchBundle.properties"),
  "intellij.repository.search.completion" to listOf("com/intellij/repository/search/completion/**"),
)
val moduleJars = moduleContents.map { (module, paths) ->
  tasks.register<Jar>("${module.substringAfterLast('.')}ModuleJar") {
    archiveFileName = "$module.jar"
    destinationDirectory = layout.buildDirectory.dir("moduleJars")
    from(sourceSets.main.map { it.output }) {
      include(paths)
      include("$module.xml")
    }
  }
}
tasks.jar {
  moduleContents.forEach { (module, paths) ->
    exclude(paths)
    exclude("$module.xml")
  }
}
listOf(tasks.prepareSandbox, tasks.prepareTestSandbox).forEach { sandbox ->
  sandbox {
    moduleJars.forEach { jar ->
      from(jar) { into(pluginName.map { "$it/lib/modules" }) }
    }
  }
}

// The IDE the tests run in still bundles IntelliJ's Repository Search, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("org.jetbrains.idea.reposearch")
}
