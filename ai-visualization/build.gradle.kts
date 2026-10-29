plugins {
  id("phantom-plugin")
}

version = "0.3.0"

dependencies {
  intellijPlatform {
    // Phantom AI Integration (dev.phantom.ai) and what it needs are plugin dependencies, not bundled: ai-visualization.zip carries
    // none of their classes. The code uses only dev.phantom.ai.api (see plugin.xml); the rest is here so the plugin loads in tests and the probe.
    localPlugin(project(":ai-integrations"))
    localPlugin(project(":workspaces"))
    bundledPlugin("org.jetbrains.plugins.terminal")
    bundledPlugin("Git4Idea")
    bundledPlugin("org.jetbrains.plugins.github")
  }
}

// Tests run against a throwaway home: never the developer's real ~/.claude, ~/.codex or keychain
tasks.test {
  val testHome = layout.buildDirectory.dir("test-home").map { it.asFile.absolutePath }
  doFirst {
    File(testHome.get(), ".claude").mkdirs()
    File(testHome.get(), ".codex").mkdirs()
  }
  systemProperty("user.home", testHome.get())
  systemProperty("phantom.ai.keychain", "false")
  systemProperty("phantom.ai.tmux", "") // empty: never the real tmux server
  environment("HOME", testHome.get())
  environment("CLAUDE_CONFIG_DIR", testHome.get() + "/.claude")
  environment("CODEX_HOME", testHome.get() + "/.codex")
  // a sample render (render-zoom3.png) is written to this directory only when -PaivizRenderOut=<dir> is given
  providers.gradleProperty("aivizRenderOut").orNull?.let { systemProperty("phantom.aiviz.renderOut", it) }
}

// Real-IDE probe: ./gradlew -PrealIde -PprobeScenario=ai-visualization :ai-visualization:runIdeProbe [-PprobeOut=dir]; not part of CI or the zip
if (providers.gradleProperty("realIde").isPresent) {
  val scenario = providers.gradleProperty("probeScenario").orElse("ai-visualization")
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
          // each run starts from the same layout: no remembered floating bounds or hidden tree
          alpha.resolve(".idea/workspace.xml").delete()
          sandboxConfigDirectory.get().asFile.resolve("options/other.xml").delete()
          // fixture alpha is a git repo with linked worktrees next to it
          fun git(dir: File, vararg a: String) = ProcessBuilder(listOf("git", "-c", "user.name=probe", "-c", "user.email=probe@example.com") + a)
            .directory(dir).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor()
          if (!alpha.resolve(".git").exists()) {
            alpha.resolve("a.txt").writeText("a\n")
            git(alpha, "init", "-q")
            git(alpha, "add", ".")
            git(alpha, "commit", "-q", "-m", "init")
          }
          // linked worktrees: alpha-feature (branch feature), alpha-hotfix (hotfix), alpha-docs (docs): four desk areas with the main one
          for ((dir, branch) in listOf("alpha-feature" to "feature", "alpha-hotfix" to "hotfix", "alpha-docs" to "docs")) {
            val wt = root.resolve(dir)
            if (!wt.resolve(".git").exists()) {
              wt.deleteRecursively()
              git(alpha, "worktree", "prune")
              if (git(alpha, "worktree", "add", "-q", "-b", branch, "../$dir") != 0) git(alpha, "worktree", "add", "-q", "../$dir", branch)
            }
          }
          // the System default dirs are redirected to these temp dirs (hooks, agent CLAUDE_CONFIG_DIR/CODEX_HOME, credentials, usage, sessions);
          // the agent CLIs are the stubs in bin/ (-Dphantom.ai.agentBinDir), the keychain is never read
          agentHomes.get().asFile.resolve("claude").mkdirs()
          agentHomes.get().asFile.resolve("codex").mkdirs()
          File(probeOut.get()).mkdirs()
          agentHomes.get().asFile.resolve("bin/calls.log").delete()
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
          "-Dphantom.ai.tmux=", // empty: never the real tmux server
          "-Dphantom.probe.scenario=${scenario.get()}",
          "-Dphantom.probe.out=${probeOut.get()}",
          "-Dphantom.probe.plugin=dev.phantom.ai.visualization",
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
