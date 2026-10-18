// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.profiler

import com.intellij.execution.ExecutionListener
import com.intellij.execution.Executor
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.ModuleRunProfile
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.impl.DefaultJavaProgramRunner
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowId
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.Icon
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** "Profile": runs a Java/Kotlin configuration with Java Flight Recorder and opens the recording when it ends. */
class ProfileExecutor : Executor() {
  override fun getToolWindowId(): String = ToolWindowId.RUN
  override fun getToolWindowIcon(): Icon = AllIcons.Toolwindows.ToolWindowRun
  override fun getIcon(): Icon = AllIcons.Actions.Profile
  override fun getDisabledIcon(): Icon = IconLoader.getDisabledIcon(icon)
  override fun getDescription(): String = PhantomProfilerBundle.message("executor.description")
  override fun getActionName(): String = PhantomProfilerBundle.message("executor.action")
  override fun getId(): String = ID
  override fun getStartActionText(): String = PhantomProfilerBundle.message("executor.action")
  override fun getStartActionText(configurationName: String): String = PhantomProfilerBundle.message("executor.start", configurationName)
  override fun getContextActionId(): String = "PhantomProfileClass"
  override fun getHelpId(): String? = null
  override fun isSupportedOnTarget(): Boolean = false

  companion object {
    const val ID: String = "PhantomProfile"
  }
}

/** The default Java runner with one more VM option: start a JFR recording that is written when the program exits. */
class ProfileProgramRunner : DefaultJavaProgramRunner() {
  override fun getRunnerId(): String = "PhantomProfileRunner"

  override fun canRun(executorId: String, profile: RunProfile): Boolean =
    executorId == ProfileExecutor.ID && profile is ModuleRunProfile && profile !is RunConfigurationWithSuppressedDefaultRunAction

  override fun patch(javaParameters: JavaParameters, settings: RunnerSettings?, runProfile: RunProfile, beforeExecution: Boolean) {
    super.patch(javaParameters, settings, runProfile, beforeExecution)
    if (beforeExecution) Recordings.prepare(runProfile, javaParameters)
  }
}

/** Where recordings go (the system folder) and which run is writing which file. */
object Recordings {
  private val pending: MutableMap<RunProfile, Path> = Collections.synchronizedMap(WeakHashMap())
  private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

  val directory: Path
    get() = Path.of(PathManager.getSystemPath(), "phantom-profiler")

  fun newFile(name: String): Path {
    directory.createDirectories()
    val safe = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60).ifEmpty { "recording" }
    return directory.resolve("$safe-${LocalDateTime.now().format(stamp)}.jfr")
  }

  fun prepare(profile: RunProfile, parameters: JavaParameters) {
    val file = newFile(profile.name)
    val vm = parameters.vmParametersList
    if (vm.parameters.none { it.startsWith("-XX:StartFlightRecording") }) {
      vm.add("-XX:StartFlightRecording=settings=profile,dumponexit=true,filename=$file")
      vm.add("-XX:FlightRecorderOptions=stackdepth=256")
      pending[profile] = file
    }
  }

  fun take(profile: RunProfile): Path? = pending.remove(profile)

  /** Saved recordings, newest first. */
  fun list(): List<Path> =
    if (!directory.exists()) emptyList()
    else directory.listDirectoryEntries("*.jfr").filter { it.isRegularFile() }.sortedByDescending { it.getLastModifiedTime() }

  fun open(project: Project, file: Path) {
    val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return
    FileEditorManager.getInstance(project).openFile(vFile, true)
  }

  fun notify(project: Project, message: String, type: NotificationType = NotificationType.WARNING) {
    NotificationGroupManager.getInstance().getNotificationGroup("Phantom Profiler").createNotification(message, type).notify(project)
  }
}

/** Opens a profiled run's recording once the program has ended and the JVM has written it. */
class ProfileExecutionListener(private val project: Project) : ExecutionListener {
  override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
    if (executorId != ProfileExecutor.ID) return
    val file = Recordings.take(env.runProfile) ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      // the JVM writes the file in a shutdown hook; it is complete by the time the process is gone, give the FS a moment
      var waited = 0
      while (sizeOf(file) == 0L && waited++ < 20) Thread.sleep(100)
      ApplicationManager.getApplication().invokeLater({
        if (sizeOf(file) > 0) Recordings.open(project, file)
        else Recordings.notify(project, PhantomProfilerBundle.message("run.no.recording", env.runProfile.name))
      }, { project.isDisposed })
    }
  }
}

private fun sizeOf(file: Path): Long = runCatching { Files.size(file) }.getOrDefault(0L)
