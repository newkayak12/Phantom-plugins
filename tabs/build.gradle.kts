plugins {
  id("phantom-plugin")
}

version = "0.2.5"

// Real-IDE probe: ./gradlew -PrealIde -PprobeScenario=smoke :tabs:runIdeProbe [-PprobeOut=dir]; not part of CI or the zip
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
          // a scenario starts from fresh fixtures (no tab groups or editors saved by an earlier run); a *-restart
          // scenario keeps what the run before it saved
          if (!scenario.get().endsWith("-restart")) root.deleteRecursively()
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
          "-Dphantom.probe.plugin=dev.phantom.tabs",
          "-Dphantom.probe.watchdog.ms=480000",
          "-Dphantom.probe.fixtures=${fixtures.get().asFile.absolutePath}",
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
        )
        args(fixtures.get().asFile.resolve("alpha").absolutePath)
      }
    }
  }
}
