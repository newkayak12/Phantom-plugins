// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
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
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile

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

  /**
   * The JVM options are added in [ProfileProgramRunner.patch], which only sees the profile; the file moves to the
   * run's execution id as soon as its process starts, so two runs of the same configuration keep their own recordings.
   */
  private val byExecution = ConcurrentHashMap<Long, Path>()

  fun started(profile: RunProfile, executionId: Long) {
    pending.remove(profile)?.let { byExecution[executionId] = it }
  }

  fun take(executionId: Long): Path? = byExecution.remove(executionId)

  private val pendingDirs = ConcurrentHashMap<Long, Path>()

  /** A folder for a run that forks several JVMs (Gradle tests): each writes its own recording into it. */
  fun newDirectory(name: String): Path {
    val file = newFile(name)
    return file.resolveSibling(file.fileName.toString().removeSuffix(".jfr")).createDirectories()
  }

  fun expectDirectory(executionId: Long, dir: Path) {
    pendingDirs[executionId] = dir
  }

  fun takeDirectory(executionId: Long): Path? = pendingDirs.remove(executionId)

  /** The non-empty recordings in [dir], largest (usually the busiest JVM) first. */
  fun recordingsIn(dir: Path): List<Path> =
    if (!Files.isDirectory(dir)) emptyList()
    else Files.list(dir).use { s -> s.filter { it.toString().endsWith(".jfr") && sizeOf(it) > 0 }.toList() }.sortedByDescending { sizeOf(it) }

  /** Saved recordings, newest first, with their size and time read here so the tool window does not read them as it paints. */
  fun list(): List<SavedRecording> = list(directory)

  /** Gradle runs write into a subfolder per run ([newDirectory]), so their recordings are listed one level down too. */
  internal fun list(root: Path): List<SavedRecording> =
    if (!root.exists()) emptyList()
    else Files.walk(root, 2).use { s -> s.filter { it.toString().endsWith(".jfr") && it.isRegularFile() }.toList() }
      .mapNotNull { file -> runCatching { SavedRecording(file, Files.size(file), file.getLastModifiedTime().toMillis()) }.getOrNull() }
      .sortedByDescending { it.modified }

  fun open(project: Project, file: Path) {
    val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return
    FileEditorManager.getInstance(project).openFile(vFile, true)
  }

  fun notify(project: Project, message: String, type: NotificationType = NotificationType.WARNING) {
    NotificationGroupManager.getInstance().getNotificationGroup("Phantom Profiler").createNotification(message, type).notify(project)
  }

  fun delete(file: Path) = delete(directory, file)

  /** Deletes [file] and the Gradle run folder it was in once that folder is empty, so no empty folders are left behind. */
  internal fun delete(root: Path, file: Path) {
    Files.deleteIfExists(file)
    val parent = file.parent ?: return
    if (parent != root && parent.parent == root && Files.list(parent).use { it.findAny().isEmpty }) Files.deleteIfExists(parent)
  }
}

class SavedRecording(val path: Path, val size: Long, val modified: Long)

/** Opens a profiled run's recording once the program has ended and the JVM has written it. */
class ProfileExecutionListener(private val project: Project) : ExecutionListener {
  override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
    if (executorId == ProfileExecutor.ID) Recordings.started(env.runProfile, env.executionId)
  }

  override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
    if (executorId != ProfileExecutor.ID) return
    Recordings.takeDirectory(env.executionId)?.let { dir -> openFrom(dir, env); return }
    val file = Recordings.take(env.executionId) ?: return
    // the JVM writes the file in a shutdown hook; it is complete by the time the process is gone, give the FS a moment
    whenReady(20, { sizeOf(file) > 0 }) {
      ApplicationManager.getApplication().invokeLater({
        if (sizeOf(file) > 0) Recordings.open(project, file)
        else Recordings.notify(project, PhantomProfilerBundle.message("run.no.recording", env.runProfile.name))
      }, { project.isDisposed })
    }
  }
}

/** A Gradle run's recordings: the three largest are opened, the folder keeps all of them. */
private fun ProfileExecutionListener.openFrom(dir: Path, env: ExecutionEnvironment) {
  val project = env.project
  whenReady(30, { Recordings.recordingsIn(dir).isNotEmpty() }) {
    val files = Recordings.recordingsIn(dir)
    ApplicationManager.getApplication().invokeLater({
      if (files.isEmpty()) Recordings.notify(project, PhantomProfilerBundle.message("run.no.recording", env.runProfile.name))
      else files.take(3).forEach { Recordings.open(project, it) }
    }, { project.isDisposed })
  }
}

/**
 * Runs [then] once [ready] holds or after [attempts] checks 100 ms apart, on the scheduled executor instead of a pooled
 * thread blocked in Thread.sleep.
 */
internal fun whenReady(attempts: Int, ready: () -> Boolean, then: () -> Unit) {
  if (attempts <= 0 || ready()) then()
  else AppExecutorUtil.getAppScheduledExecutorService().schedule({ whenReady(attempts - 1, ready, then) }, 100, TimeUnit.MILLISECONDS)
}

private fun sizeOf(file: Path): Long = runCatching { Files.size(file) }.getOrDefault(0L)
