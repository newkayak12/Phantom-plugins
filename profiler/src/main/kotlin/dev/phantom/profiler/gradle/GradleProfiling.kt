// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.profiler.gradle

import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunnableState
import dev.phantom.profiler.ProfileExecutor
import dev.phantom.profiler.Recordings
import org.jetbrains.concurrency.resolvedPromise
import org.jetbrains.plugins.gradle.service.task.GradleTaskManagerExtension
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.gradle.util.GradleVersion
import java.nio.file.Files
import java.nio.file.Path

/**
 * Profile on a Gradle run configuration: tests run with `./gradlew test` and Spring Boot apps delegated to Gradle.
 * The run goes out on a copy of the configuration carrying [ENV]; [GradleProfileInitScript] sees it and starts JFR in
 * every `Test` and `JavaExec` JVM the build forks, writing one recording per JVM into the run's directory.
 */
class ProfileGradleRunner : ProgramRunner<RunnerSettings> {
  override fun getRunnerId(): String = "PhantomProfileGradleRunner"

  override fun canRun(executorId: String, profile: RunProfile): Boolean =
    executorId == ProfileExecutor.ID && profile is ExternalSystemRunConfiguration && profile.settings.externalSystemId == GradleConstants.SYSTEM_ID

  override fun execute(environment: ExecutionEnvironment) {
    val original = environment.runProfile as ExternalSystemRunConfiguration
    // a copy, so the user's configuration keeps its environment variables as they were
    val copy = original.clone() ?: return
    val dir = Recordings.newDirectory(original.name)
    copy.settings.env = copy.settings.env + (ENV to dir.toString())
    val profiled = ExecutionEnvironmentBuilder(environment).runProfile(copy).build()
    Recordings.expectDirectory(copy, dir)
    val state = profiled.state as? ExternalSystemRunnableState ?: return
    ExecutionManager.getInstance(environment.project).startRunProfile(profiled) {
      val result = state.execute(profiled.executor, this) ?: return@startRunProfile resolvedPromise(null)
      val descriptor = RunContentBuilder(result, profiled).showRunContent(profiled.contentToReuse)
      descriptor?.let(state::setContentDescriptor)
      resolvedPromise(descriptor)
    }
  }

  companion object {
    const val ENV: String = "PHANTOM_PROFILE_DIR"
  }
}

/** Adds the JFR options to forked JVMs when the run asked for profiling; does nothing for every other Gradle run. */
class GradleProfileInitScript : GradleTaskManagerExtension {
  override fun configureTasks(projectPath: String, id: ExternalSystemTaskId, settings: GradleExecutionSettings, gradleVersion: GradleVersion?) {
    val dir = settings.env[ProfileGradleRunner.ENV] ?: return
    settings.addInitScript("phantomProfile", script(Path.of(dir)))
  }

  companion object {
    /** Groovy, so it works for Groovy and Kotlin DSL builds alike. Tests always rerun: an up-to-date test task records nothing. */
    fun script(dir: Path): String {
      val path = dir.toString().replace("\\", "\\\\").replace("'", "\\'")
      return """
        def phantomJfr = { String kind -> ['-XX:StartFlightRecording=settings=profile,dumponexit=true,filename=' + '$path' + '/' + kind + '-%p.jfr',
                                           '-XX:FlightRecorderOptions=stackdepth=256'] }
        allprojects {
          tasks.withType(Test).configureEach {
            jvmArgs(phantomJfr('test'))
            outputs.upToDateWhen { false }
          }
          tasks.withType(JavaExec).configureEach { jvmArgs(phantomJfr('run')) }
        }
      """.trimIndent()
    }
  }
}

