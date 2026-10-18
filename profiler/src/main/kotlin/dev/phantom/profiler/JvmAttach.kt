// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.profiler

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/** A JVM running on this machine, as `jcmd -l` lists it. */
data class LocalJvm(val pid: Long, val mainClass: String) {
  val displayName: String
    get() = mainClass.substringAfterLast('/').substringAfterLast('.').ifEmpty { mainClass }
}

/**
 * Starts and stops JFR in running JVMs with `jcmd` from the IDE's own runtime, which works for any HotSpot JVM of
 * the same user (JDK 11+, 8u262+). Nothing is loaded into the target besides JFR, which ships with it.
 */
object JvmAttach {
  private const val RECORDING = "phantom"
  private val recording = ConcurrentHashMap.newKeySet<Long>()

  private val jcmd: String
    get() = Path.of(System.getProperty("java.home"), "bin", "jcmd").takeIf { it.exists() }?.toString() ?: "jcmd"

  fun isRecording(pid: Long): Boolean = pid in recording

  /** Other JVMs of this user; the IDE itself and jcmd are left out. */
  fun list(): List<LocalJvm> {
    val self = ProcessHandle.current().pid()
    return run(listOf(jcmd, "-l")).lineSequence().mapNotNull { line ->
      val pid = line.substringBefore(' ').toLongOrNull() ?: return@mapNotNull null
      val main = line.substringAfter(' ', "").substringBefore(' ').trim()
      LocalJvm(pid, main.ifEmpty { "pid $pid" }).takeIf { pid != self && "sun.tools.jcmd" !in main }
    }.toList()
  }

  @Throws(IOException::class)
  fun start(pid: Long) {
    run(listOf(jcmd, pid.toString(), "JFR.start", "name=$RECORDING", "settings=profile"))
    recording.add(pid)
  }

  /** Writes what was recorded to a new file, ends the recording and returns the file. */
  @Throws(IOException::class)
  fun stop(jvm: LocalJvm): Path {
    val file = Recordings.newFile(jvm.displayName)
    try {
      run(listOf(jcmd, jvm.pid.toString(), "JFR.dump", "name=$RECORDING", "filename=$file"))
    }
    finally {
      runCatching { run(listOf(jcmd, jvm.pid.toString(), "JFR.stop", "name=$RECORDING")) }
      recording.remove(jvm.pid)
    }
    return file
  }

  private fun run(command: List<String>): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = CompletableFuture.supplyAsync({ process.inputStream.readBytes().toString(Charsets.UTF_8) }, AppExecutorUtil.getAppExecutorService())
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw IOException(PhantomProfilerBundle.message("attach.timeout", command.drop(1).joinToString(" ")))
    }
    val text = output.get(5, TimeUnit.SECONDS)
    // jcmd reports most failures on stdout with a zero exit code
    if (process.exitValue() != 0 || text.lineSequence().any { it.startsWith("Could not") || "Exception" in it }) {
      throw IOException(text.trim().lines().lastOrNull { it.isNotBlank() } ?: "jcmd exited with ${process.exitValue()}")
    }
    return text
  }
}

/** Opens the source of a sampled frame: the line when the class has sources, else the method. */
object FrameNavigation {
  fun navigate(project: Project, frame: Frame) {
    ReadAction.nonBlocking<OpenFileDescriptor?> {
      val facade = JavaPsiFacade.getInstance(project)
      val cls = facade.findClass(frame.className.replace('$', '.'), GlobalSearchScope.allScope(project))
                ?: return@nonBlocking null
      val source = cls.navigationElement
      val file = source.containingFile?.virtualFile ?: return@nonBlocking null
      if (frame.line > 0 && !file.fileType.isBinary) OpenFileDescriptor(project, file, frame.line - 1, 0)
      else {
        val method = cls.findMethodsByName(frame.method, false).firstOrNull()?.navigationElement ?: source
        OpenFileDescriptor(project, method.containingFile.virtualFile, method.textOffset)
      }
    }.finishOnUiThread(com.intellij.openapi.application.ModalityState.nonModal()) { it?.navigate(true) }
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  /** Which of [classNames] are the project's own, to color them apart from libraries. Needs a read action. */
  fun projectClasses(project: Project, classNames: Collection<String>): Set<String> {
    val facade = JavaPsiFacade.getInstance(project)
    val scope = ProjectScope.getContentScope(project)
    return classNames.asSequence().take(5000).filter { facade.findClass(it.replace('$', '.'), scope) != null }.toSet()
  }
}
