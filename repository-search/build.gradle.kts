import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.2"

dependencies {
  intellijPlatform {
    // common builds on the Maven plugin's model module
    // phantom-compat.yml: Phantom publishes Maven as a plugin of its own; -PmavenPlugin=<Maven plugin zip or dir> takes it from there
    providers.gradleProperty("mavenPlugin").orNull?.let { localPlugin(it) } ?: bundledPlugin("org.jetbrains.idea.maven")
    bundledModule("intellij.platform.externalSystem")
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
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
