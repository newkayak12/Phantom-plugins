plugins {
  id("phantom-plugin")
  // The IntelliJ welcome screen sources use @Serializable and @Composable
  id("org.jetbrains.kotlin.plugin.serialization")
  id("org.jetbrains.kotlin.plugin.compose")
}

version = "0.7.2"

dependencies {
  // Generates the RemoteApiDescriptor of the @Rpc interface WelcomeScreenFeatureApi and passes it to remoteApiDescriptor<T>()
  kotlinCompilerPluginClasspathMain(project(":compiler-plugins:fleet-rpc"))
  intellijPlatform {
    // What the IntelliJ module intellij.platform.ide.nonModalWelcomeScreen and its frontend and backend modules depend on
    bundledModule("intellij.platform.compose")
    bundledModule("intellij.libraries.compose.runtime.desktop")
    bundledModule("intellij.libraries.compose.foundation.desktop")
    bundledModule("intellij.platform.jewel.foundation")
    bundledModule("intellij.platform.jewel.ui")
    bundledModule("intellij.platform.jewel.ideLafBridge")
    bundledModule("intellij.platform.jewel.markdown.ideLafBridgeStyling")
    bundledModule("intellij.libraries.kotlinx.serialization.core")
    bundledModule("fleet.rhizomedb")
    bundledModule("fleet.rpc")
    bundledModule("intellij.platform.kernel")
    bundledModule("intellij.platform.projectFrame")
    bundledModule("intellij.platform.welcomeScreen")
    bundledModule("intellij.platform.backend")
    bundledModule("intellij.platform.kernel.backend")
    bundledModule("intellij.platform.rpc.backend")
    bundledModule("intellij.platform.frontend")
  }
  // The welcome screen's IntelliJ test is JUnit Jupiter; the workspaces tests are JUnit 4, run by the vintage engine
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}

// Real-IDE probe: ./gradlew -PrealIde -PprobeScenario=smoke :workspaces:runIdeProbe [-PprobeOut=dir]; not part of CI or the zip
if (providers.gradleProperty("realIde").isPresent) {
  val scenario = providers.gradleProperty("probeScenario").orElse("smoke")
  val probeOut = providers.gradleProperty("probeOut").orElse(scenario.flatMap { layout.buildDirectory.dir("realide/$it").map { d -> d.asFile.absolutePath } })
  val fixtures = layout.buildDirectory.dir("realide-fixtures")
  val probeDependency = project.dependencies.project(":realide-probe")

  intellijPlatformTesting {
    runIde.register("runIdeProbe") {
      plugins {
        localPlugin(probeDependency)
      }
      task {
        // a fresh idea.log per run; config and system stay, so a restart scenario sees what the first run saved
        doFirst {
          sandboxLogDirectory.get().asFile.deleteRecursively()
          sandboxSystemDirectory.get().asFile.resolve("log").deleteRecursively()
          val root = fixtures.get().asFile
          for (name in listOf("alpha", "beta", "gamma")) {
            val dir = root.resolve(name)
            dir.resolve(".idea").mkdirs()
            dir.resolve(".idea/modules.xml").writeText(
              "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project version=\"4\"><component name=\"ProjectModuleManager\"><modules>" +
                "<module fileurl=\"file://\$PROJECT_DIR\$/$name.iml\" filepath=\"\$PROJECT_DIR\$/$name.iml\" /></modules></component></project>\n",
            )
            dir.resolve("$name.iml").writeText(
              "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<module type=\"EMPTY_MODULE\" version=\"4\"><component name=\"NewModuleRootManager\">" +
                "<content url=\"file://\$MODULE_DIR\$\" /><orderEntry type=\"sourceFolder\" forTests=\"false\" /></component></module>\n",
            )
            for (f in 'a'..'f') dir.resolve("$f.txt").writeText("$name $f\n")
          }
          File(probeOut.get()).mkdirs()
        }
        jvmArgs(
          "-Dphantom.probe.scenario=${scenario.get()}",
          "-Dphantom.probe.out=${probeOut.get()}",
          "-Dphantom.probe.plugin=dev.phantom.workspaces",
          "-Dphantom.probe.watchdog.ms=600000",
          "-Dphantom.probe.fixtures=${fixtures.get().asFile.absolutePath}",
          "-Dphantom.probe.keepLayouts=${providers.gradleProperty("probeKeepLayouts").isPresent}",
          "-Didea.trust.all.projects=true",
          "-Dide.trust.all.projects=true",
          "-Djb.consents.confirmation.enabled=false",
          "-Djb.privacy.policy.text=<!--999.999-->",
          "-Dide.show.tips.on.startup.default.value=false",
          "-Dide.no.platform.update=true",
          "-Dide.mac.file.chooser.native=false",
          "-Dnosplash=true",
          // keeps the log in the sandbox: without it the IDE warns about idea.system.path in a balloon over the frame
          "-Didea.log.path=${sandboxLogDirectory.get().asFile.absolutePath}",
          // tool window weights and frame events in idea.log: where ToolWindowPane.setWeight sized the tool windows from
          "-Didea.log.debug.categories=#com.intellij.toolWindow.ToolWindowPane,#com.intellij.openapi.wm.impl.ToolWindowManagerImpl,ide.frame.events",
        )
        args(fixtures.get().asFile.resolve("alpha").absolutePath)
      }
    }
  }
}
