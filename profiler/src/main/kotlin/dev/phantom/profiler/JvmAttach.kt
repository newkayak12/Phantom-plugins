// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.profiler

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
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

  /**
   * Reads the real state from each JVM with JFR.check: the in-memory set misses recordings started before an IDE restart
   * and keeps ones that ended because the target stopped them or restarted under the same pid.
   */
  fun syncRecordingState(jvms: List<LocalJvm>) {
    for (jvm in jvms) {
      val output = runCatching { run(listOf(jcmd, jvm.pid.toString(), "JFR.check")) }.getOrNull() ?: continue
      if (isRunning(output)) recording.add(jvm.pid) else recording.remove(jvm.pid)
    }
    recording.retainAll(jvms.mapTo(HashSet()) { it.pid })
  }

  /** Whether JFR.check output lists our recording as running, e.g. `Recording 1: name=phantom maxsize=250.0MB (running)`. */
  internal fun isRunning(checkOutput: String): Boolean =
    checkOutput.lineSequence().any { Regex("""\bname=$RECORDING(\s|$)""").containsMatchIn(it) && "(running)" in it }

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
      val cls = findSourceClass(facade, frame.className, GlobalSearchScope.allScope(project)) ?: return@nonBlocking null
      val source = cls.navigationElement
      val file = source.containingFile?.virtualFile ?: return@nonBlocking null
      if (frame.line > 0 && !file.fileType.isBinary) OpenFileDescriptor(project, file, frame.line - 1, 0)
      else {
        val method = cls.findMethodsByName(sourceMethodName(frame.method), false).firstOrNull()?.navigationElement ?: source
        OpenFileDescriptor(project, method.containingFile.virtualFile, method.textOffset)
      }
    }.finishOnUiThread(com.intellij.openapi.application.ModalityState.nonModal()) { it?.navigate(true) }
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  /**
   * Which of [classNames] are the project's own, to color them apart from libraries. Needs a read action. JDK and Kotlin
   * runtime classes are dropped before any PSI lookup: they dominate most stacks and are never project sources.
   */
  fun projectClasses(project: Project, classNames: Collection<String>): Set<String> {
    val facade = JavaPsiFacade.getInstance(project)
    val scope = ProjectScope.getContentScope(project)
    return classNames.asSequence().filter(::mayBeProjectClass).take(5000)
      .filter { findSourceClass(facade, it, scope) != null }.toSet()
  }

  private val runtimePrefixes = listOf("java.", "javax.", "jdk.", "sun.", "com.sun.", "kotlin.", "kotlinx.coroutines.")

  internal fun mayBeProjectClass(className: String): Boolean = runtimePrefixes.none(className::startsWith)

  /**
   * Source classes to try for a JVM class name, most specific first: `Outer$Inner` as `Outer.Inner`, then each enclosing
   * class. Anonymous classes (`Outer$1`) and lambda classes (`Outer$$Lambda/0x...`) have no PSI class of their own, so
   * their frames land on the class that declares them.
   */
  internal fun sourceClassCandidates(className: String): List<String> {
    val name = className.substringBefore("$$").substringBefore('/')
    val parts = name.split('$').filter { it.isNotEmpty() }
    val named = parts.takeWhile { it.first().isJavaIdentifierStart() }
    return named.indices.reversed().map { named.subList(0, it + 1).joinToString(".") }
  }

  /** `lambda$foo$0` is javac's body of a lambda in `foo`; `foo$lambda$0` and `foo$default` are Kotlin's forms. */
  internal fun sourceMethodName(method: String): String =
    if (method.startsWith("lambda$")) method.removePrefix("lambda$").substringBefore('$').ifEmpty { method }
    else method.substringBefore('$').ifEmpty { method }

  private fun findSourceClass(facade: JavaPsiFacade, className: String, scope: GlobalSearchScope): PsiClass? =
    sourceClassCandidates(className).firstNotNullOfOrNull { facade.findClass(it, scope) }
}
