plugins {
  id("phantom-plugin")
}

version = "0.1.0"

dependencies {
  intellijPlatform {
    // Workspaces is a plugin dependency (declared in plugin.xml), not bundled: ai-integrations.zip carries none of its classes
    localPlugin(project(":workspaces"))
    bundledPlugin("org.jetbrains.plugins.terminal")
    bundledPlugin("Git4Idea")
    bundledPlugin("org.jetbrains.plugins.github")
  }
}

// Real-IDE probe: ./gradlew -PrealIde -PprobeScenario=ai-integrations :ai-integrations:runIdeProbe [-PprobeOut=dir]; not part of CI or the zip
if (providers.gradleProperty("realIde").isPresent) {
  val scenario = providers.gradleProperty("probeScenario").orElse("ai-integrations")
  val probeOut = providers.gradleProperty("probeOut").orElse(scenario.flatMap { layout.buildDirectory.dir("realide/$it").map { d -> d.asFile.absolutePath } })
  val fixtures = layout.buildDirectory.dir("realide-fixtures")
  val agentHomes = layout.buildDirectory.dir("realide-agent-homes")
  val probeDependency = project.dependencies.project(":realide-probe")

  intellijPlatformTesting {
    runIde.register("runIdeProbe") {
      plugins {
        localPlugin(probeDependency)
      }
      task {
        doFirst {
          sandboxLogDirectory.get().asFile.deleteRecursively()
          sandboxSystemDirectory.get().asFile.resolve("log").deleteRecursively()
          val root = fixtures.get().asFile
          val alpha = root.resolve("alpha")
          alpha.resolve(".idea").mkdirs()
          alpha.resolve(".idea/modules.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project version=\"4\"><component name=\"ProjectModuleManager\"><modules>" +
              "<module fileurl=\"file://\$PROJECT_DIR\$/alpha.iml\" filepath=\"\$PROJECT_DIR\$/alpha.iml\" /></modules></component></project>\n",
          )
          alpha.resolve("alpha.iml").writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<module type=\"EMPTY_MODULE\" version=\"4\"><component name=\"NewModuleRootManager\">" +
              "<content url=\"file://\$MODULE_DIR\$\" /><orderEntry type=\"sourceFolder\" forTests=\"false\" /></component></module>\n",
          )
          // a git repo the Projects tab and the board list, seeded into the sandbox's own phantom-ai.xml (never the user's)
          val repo = root.resolve("ai-repo")
          if (!repo.resolve(".git").exists()) {
            repo.mkdirs()
            repo.resolve("a.txt").writeText("a\n")
            fun git(vararg a: String) = ProcessBuilder(listOf("git", "-c", "user.name=probe", "-c", "user.email=probe@example.com") + a)
              .directory(repo).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor()
            git("init", "-q")
            git("add", ".")
            git("commit", "-q", "-m", "init")
          }
          val options = sandboxConfigDirectory.get().asFile.resolve("options")
          options.mkdirs()
          options.resolve("phantom-ai.xml").writeText(
            "<application><component name=\"PhantomAiIntegrations\"><option name=\"repos\"><list><RepoEntry>" +
              "<option name=\"path\" value=\"${repo.absolutePath}\" /></RepoEntry></list></option></component></application>\n",
          )
          // the System default dirs are redirected to these temp dirs (hooks, agent CLAUDE_CONFIG_DIR/CODEX_HOME, credentials, usage, sessions);
          // the agent CLIs are the stubs in bin/ (-Dphantom.ai.agentBinDir), the keychain is never read
          agentHomes.get().asFile.resolve("claude").mkdirs()
          agentHomes.get().asFile.resolve("codex").mkdirs()
          File(probeOut.get()).mkdirs()
          // stub agent CLIs: log one line per call (own path, argv, config-dir env) and sleep; no network, keychain or HOME config
          val bin = agentHomes.get().asFile.resolve("bin")
          bin.mkdirs()
          for (name in listOf("claude", "codex")) {
            val stub = bin.resolve(name)
            stub.writeText(
              "#!/bin/sh\n" +
                "echo \"\$0 \$* CLAUDE_CONFIG_DIR=\$CLAUDE_CONFIG_DIR CODEX_HOME=\$CODEX_HOME\" >> \"${bin.resolve("calls.log").absolutePath}\"\n" +
                "exec sleep 600\n",
            )
            stub.setExecutable(true)
          }
        }
        jvmArgs(
          "-Dphantom.ai.claudeDir=${agentHomes.get().asFile.resolve("claude").absolutePath}",
          "-Dphantom.ai.codexDir=${agentHomes.get().asFile.resolve("codex").absolutePath}",
          "-Dphantom.ai.agentBinDir=${agentHomes.get().asFile.resolve("bin").absolutePath}",
          "-Dphantom.ai.keychain=false",
          "-Dphantom.probe.scenario=${scenario.get()}",
          "-Dphantom.probe.out=${probeOut.get()}",
          "-Dphantom.probe.plugin=dev.phantom.ai",
          "-Dphantom.probe.watchdog.ms=600000",
          "-Dphantom.probe.fixtures=${fixtures.get().asFile.absolutePath}",
          "-Didea.trust.all.projects=true",
          "-Dide.trust.all.projects=true",
          "-Djb.consents.confirmation.enabled=false",
          "-Djb.privacy.policy.text=<!--999.999-->",
          "-Dide.show.tips.on.startup.default.value=false",
          "-Dide.no.platform.update=true",
          "-Dnosplash=true",
          "-Didea.log.path=${sandboxLogDirectory.get().asFile.absolutePath}",
        )
        args(fixtures.get().asFile.resolve("alpha").absolutePath)
      }
    }
  }
}
