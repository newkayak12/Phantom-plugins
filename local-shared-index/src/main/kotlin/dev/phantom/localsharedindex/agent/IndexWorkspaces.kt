// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex.agent

import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopes
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The worktree [root] in the open [project] that holds it. */
class Located(val project: Project, val root: Path) {
  fun absolute(path: String): Path {
    val given = Path.of(path.removePrefix("file://"))
    val absolute = (if (given.isAbsolute) given else root.resolve(given)).normalize()
    if (!absolute.startsWith(root)) throw IndexToolError("$path is outside this workspace")
    return absolute
  }

  fun shown(file: VirtualFile): String = runCatching { root.relativize(file.toNioPath()).toString() }.getOrDefault(file.path)

  fun contains(file: VirtualFile): Boolean = runCatching { file.toNioPath().normalize().startsWith(root) }.getOrDefault(false)

  /** Needs a read action. */
  fun workspace(): Workspace {
    if (DumbService.isDumb(project)) throw IndexToolError(IndexWorkspaces.INDEXING)
    val dir = LocalFileSystem.getInstance().findFileByNioFile(root) ?: throw IndexToolError(IndexWorkspaces.NOT_OPEN)
    val scope = GlobalSearchScope.projectScope(project).intersectWith(GlobalSearchScopes.directoryScope(project, dir, true))
    return Workspace(this, CodeQueries(project, scope, ::shown))
  }
}

/** The worktree's files and the queries over them, inside a read action. */
class Workspace(val at: Located, val queries: CodeQueries) {
  fun file(path: String): VirtualFile =
    LocalFileSystem.getInstance().findFileByNioFile(at.absolute(path))?.takeUnless { it.isDirectory } ?: throw IndexToolError("No such file: $path")
}

/**
 * The open project holding an agent's worktree, only that worktree's own files. It never waits for indexing: an agent
 * told "not now" goes back to its own search, one kept waiting burns its turn. Called off the EDT.
 */
object IndexWorkspaces {
  /** Runs [block] in a non-blocking read action over the [worktree]'s files, giving up after a while. */
  fun read(worktree: String?, block: (Workspace) -> String): String {
    val at = locate(worktree)
    val project = at.project
    val promise = ReadAction.nonBlocking<Result<String>> {
      try {
        Result.success(block(at.workspace()))
      } catch (e: IndexToolError) {
        Result.failure(e)
      }
    }.expireWith(project).submit(AppExecutorUtil.getAppExecutorService())
    return try {
      promise.get(TIMEOUT_S, TimeUnit.SECONDS).getOrThrow()
    } catch (e: TimeoutException) {
      promise.cancel()
      throw IndexToolError(TOO_SLOW)
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    }
  }

  fun locate(worktree: String?): Located {
    val root = Path.of(worktree ?: throw IndexToolError(NOT_AVAILABLE)).normalize()
    val project = ProjectManager.getInstance().openProjects
      .filter { p -> p.basePath?.let { root.startsWith(Path.of(it)) } == true }
      .maxByOrNull { it.basePath!!.length }
      ?: throw IndexToolError(NOT_OPEN)
    return Located(project, root)
  }

  /** The file as it is on disk now: the agent may have just written it. */
  fun fresh(at: Located, path: String): VirtualFile {
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(at.absolute(path))?.takeUnless { it.isDirectory }
      ?: throw IndexToolError("No such file: $path")
    VfsUtil.markDirtyAndRefresh(false, false, false, file)
    return file
  }

  /** The file's errors (and warnings when [warnings]) from the IDE's analysis, one `path:line  SEVERITY  message` each. */
  fun problems(worktree: String?, path: String, warnings: Boolean): String {
    val at = locate(worktree)
    val file = fresh(at, path)
    // the reloaded text into PSI; behind an open dialog this waits a little, then analyzes what is committed
    val committed = CompletableFuture<Unit>()
    ApplicationManager.getApplication().invokeLater(
      { try { PsiDocumentManager.getInstance(at.project).commitAllDocuments() } finally { committed.complete(Unit) } },
      ModalityState.nonModal(), at.project.disposed,
    )
    runCatching { committed.get(COMMIT_WAIT_S, TimeUnit.SECONDS) }
    ReadAction.compute<Unit, Exception> {
      at.workspace()
      val psi = PsiManager.getInstance(at.project).findFile(file) ?: throw IndexToolError("Cannot read $path")
      if (!ProblemHighlightFilter.shouldProcessFileInBatch(psi)) throw IndexToolError("Phantom does not analyze $path: it is not in the project's sources")
    }
    val indicator = ProgressIndicatorBase()
    val analysis = AppExecutorUtil.getAppExecutorService().submit(Callable {
      ProgressManager.getInstance().runProcess(Computable { CodeSmellDetector.getInstance(at.project).findCodeSmells(listOf(file)) }, indicator)
    })
    val found = try {
      analysis.get(PROBLEMS_TIMEOUT_S, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
      indicator.cancel()
      throw IndexToolError("The IDE's analysis of $path took too long; build instead")
    } catch (e: ExecutionException) {
      if (e.cause is ProcessCanceledException) throw IndexToolError("The analysis was interrupted by a change; call again")
      throw e.cause ?: e
    }
    val shown = at.shown(file)
    val lines = found
      .filter { warnings || it.severity == HighlightSeverity.ERROR }
      .sortedBy { it.startLine }
      .map { "$shown:${it.startLine + 1}  ${if (it.severity == HighlightSeverity.ERROR) "ERROR" else "WARNING"}  ${it.description}" }
      .distinct()
    return when {
      lines.isEmpty() -> if (warnings) "No errors or warnings in $shown." else "No errors in $shown."
      lines.size > MAX_PROBLEMS -> (lines.take(MAX_PROBLEMS) + "... ${lines.size - MAX_PROBLEMS} more").joinToString("\n")
      else -> lines.joinToString("\n")
    }
  }

  private const val TIMEOUT_S = 20L
  private const val PROBLEMS_TIMEOUT_S = 60L
  private const val COMMIT_WAIT_S = 5L
  private const val MAX_PROBLEMS = 100
  const val NOT_AVAILABLE = "The IDE index is not available for this terminal; use your own search."
  const val NOT_OPEN = "This workspace is not open in Phantom, so it has no index; use your own search."
  const val INDEXING = "Phantom is still indexing this workspace; use your own search for now."
  const val TOO_SLOW = "The IDE index took too long; narrow the query or use your own search."
}
