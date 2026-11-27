// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiUtilCore
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import dev.phantom.localsharedindex.agent.IndexToolError
import dev.phantom.localsharedindex.agent.IndexWorkspaces
import dev.phantom.localsharedindex.agent.Located
import dev.phantom.localsharedindex.agent.Workspace
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Answers from the IDE's indexes for the agent's own workspace ([worktree]): short `path:line  code` lines instead of
 * whole files. Fakeable; the real one is [IdeCodeIndex]. Called off the EDT.
 */
interface CodeIndex {
  fun findSymbol(worktree: String?, query: String, limit: Int): String
  fun findFile(worktree: String?, query: String, limit: Int): String
  fun findUsages(worktree: String?, path: String, line: Int, name: String, limit: Int): String
  fun fileOutline(worktree: String?, path: String): String
  fun symbolInfo(worktree: String?, path: String, line: Int, name: String): String
  fun calls(worktree: String?, path: String, line: Int, name: String, limit: Int): String
  /** The file's errors (and warnings when [warnings]) from the IDE's analysis, one `path:line  SEVERITY  message` each. */
  fun fileProblems(worktree: String?, path: String, warnings: Boolean): String
  /** Renames the symbol and its references with the IDE's Rename and saves the files; refuses a conflict or a change outside [worktree]. */
  fun rename(worktree: String?, path: String, line: Int, name: String, newName: String): String

  /** The source of one declaration with line numbers, found by [name] (or `Outer.name`) and, when given, its [line]. */
  fun readSymbol(worktree: String?, path: String, line: Int?, name: String): String

  /** Replaces a whole declaration (doc comment and annotations too) with [code], re-indented to its place; saves. */
  fun replaceSymbol(worktree: String?, path: String, line: Int?, name: String, code: String): String

  /** Puts [code] before or after a declaration ([after]), a blank line between, re-indented to its place; saves. */
  fun insertCode(worktree: String?, path: String, line: Int?, name: String, after: Boolean, code: String): String
}


/**
 * The real [CodeIndex]: the open project holding the agent's worktree, only that worktree's own files. It never waits for
 * indexing: an agent told "not now" goes back to its own search, one kept waiting burns its turn.
 */
class IdeCodeIndex : CodeIndex {
  override fun findSymbol(worktree: String?, query: String, limit: Int) = inWorkspace(worktree) { it.queries.symbols(query, limit) }
  override fun findFile(worktree: String?, query: String, limit: Int) = inWorkspace(worktree) { it.queries.files(query, limit) }
  override fun findUsages(worktree: String?, path: String, line: Int, name: String, limit: Int) =
    inWorkspace(worktree) { it.queries.usages(it.file(path), line, name, limit) }
  override fun fileOutline(worktree: String?, path: String) = inWorkspace(worktree) { it.queries.outline(it.file(path)) }
  override fun symbolInfo(worktree: String?, path: String, line: Int, name: String) =
    inWorkspace(worktree) { it.queries.symbolInfo(it.file(path), line, name) }
  override fun calls(worktree: String?, path: String, line: Int, name: String, limit: Int) =
    inWorkspace(worktree) { it.queries.calls(it.file(path), line, name, limit) }

  override fun fileProblems(worktree: String?, path: String, warnings: Boolean): String = lsi { IndexWorkspaces.problems(worktree, path, warnings) }

  override fun rename(worktree: String?, path: String, line: Int, name: String, newName: String): String {
    val at = locate(worktree)
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(at.root)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
      ?: throw McpToolError(IndexWorkspaces.NOT_OPEN)
    return whenEdtFree(at, "nothing was renamed") { renameOnEdt(at, path, line, name, newName) }
  }

  override fun readSymbol(worktree: String?, path: String, line: Int?, name: String) =
    inWorkspace(worktree) { it.queries.read(it.queries.declaration(it.file(path), line, name)) }

  override fun replaceSymbol(worktree: String?, path: String, line: Int?, name: String, code: String) =
    edit(worktree, path, line, name) { document, declaration ->
      val range = declaration.textRange
      val text = CodeText.indented(code, CodeText.indentOf(document, range.startOffset), indentFirst = false)
      document.replaceString(range.startOffset, range.endOffset, text)
      "Replaced $name" to TextRange(range.startOffset, range.startOffset + text.length)
    }

  override fun insertCode(worktree: String?, path: String, line: Int?, name: String, after: Boolean, code: String) =
    edit(worktree, path, line, name) { document, declaration ->
      val range = declaration.textRange
      val indent = CodeText.indentOf(document, range.startOffset)
      val text = CodeText.indented(code, indent, indentFirst = true)
      if (after) {
        document.insertString(range.endOffset, "\n\n$text")
        "Inserted after $name" to TextRange(range.endOffset + 2, range.endOffset + 2 + text.length)
      } else {
        // at the declaration's line start, which keeps its own indentation after the inserted code
        val at = range.startOffset - indent.length
        document.insertString(at, "$text\n\n")
        "Inserted before $name" to TextRange(at, at + text.length)
      }
    }

  /**
   * Changes a declaration of the worktree in one undoable command and saves the file. [change] edits the document and
   * says what it did and which range it wrote.
   */
  private fun edit(worktree: String?, path: String, line: Int?, name: String, change: (Document, PsiElement) -> Pair<String, TextRange>): String {
    val at = locate(worktree)
    fresh(at, path)
    return whenEdtFree(at, "nothing was changed") {
      val documents = PsiDocumentManager.getInstance(at.project)
      documents.commitAllDocuments()
      val workspace = at.workspace()
      val declaration = workspace.queries.declaration(workspace.file(path), line, name)
      val file = PsiUtilCore.getVirtualFile(declaration)
      if (file == null || !at.contains(file)) throw McpToolError("$name is declared outside this workspace; nothing was changed")
      val document = documents.getDocument(declaration.containingFile) ?: throw McpToolError("Cannot edit $path")
      if (!FileDocumentManager.getInstance().requestWriting(document, at.project)) throw McpToolError("${at.shown(file)} is read-only; nothing was changed")
      var said: Pair<String, TextRange>? = null
      WriteCommandAction.writeCommandAction(at.project).withName("Agent: edit $name").run<Exception> {
        said = change(document, declaration)
        documents.commitDocument(document)
      }
      FileDocumentManager.getInstance().saveDocument(document)
      val (what, range) = said!!
      val first = document.getLineNumber(range.startOffset) + 1
      val last = document.getLineNumber(range.endOffset) + 1
      "$what in ${at.shown(file)}: now lines $first-$last. get_file_problems shows any errors."
    }
  }

  /** Runs [block] on the EDT when no dialog is up; one that has not started by the deadline never runs ([nothing] says so). */
  private fun whenEdtFree(at: Located, nothing: String, block: () -> String): String {
    val state = AtomicInteger(PENDING)
    val done = CompletableFuture<String>()
    ApplicationManager.getApplication().invokeLater({
      if (!state.compareAndSet(PENDING, STARTED)) return@invokeLater
      try {
        done.complete(lsi(block))
      } catch (e: Throwable) {
        done.completeExceptionally(e)
      }
    }, ModalityState.nonModal(), at.project.disposed)
    return try {
      try {
        done.get(RENAME_WAIT_S, TimeUnit.SECONDS)
      } catch (e: TimeoutException) {
        if (state.compareAndSet(PENDING, ABANDONED)) throw McpToolError("Phantom is busy (a dialog is open); $nothing, try again")
        done.get()
      }
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    }
  }

  private fun renameOnEdt(at: Located, path: String, line: Int, name: String, newName: String): String {
    PsiDocumentManager.getInstance(at.project).commitAllDocuments()
    val workspace = at.workspace()
    val target = workspace.queries.target(workspace.file(path), line, name) as? PsiNamedElement
      ?: throw McpToolError("$name at line $line of $path cannot be renamed")
    if (!workspace.queries.inWorkspace(target)) throw McpToolError("$name is declared outside this workspace; nothing was renamed")
    if (!RenameUtil.isValidName(at.project, target, newName)) throw McpToolError("$newName is not a valid name here; nothing was renamed")
    val rename = AgentRename(at, target, newName)
    rename.setPreviewUsages(false)
    rename.run()
    val documents = FileDocumentManager.getInstance()
    rename.touched.filter { it.isValid }.forEach { file -> documents.getCachedDocument(file)?.let(documents::saveDocument) }
    val files = rename.touched.filter { it.isValid }.map(at::shown).distinct().sorted()
    return "Renamed $name to $newName: ${rename.references} references, saved ${files.size} files:\n" + files.joinToString("\n")
  }

  /** The IDE's Rename with no dialogs: a conflict, or a change outside the worktree, stops it before anything changes. */
  private class AgentRename(private val at: Located, private val target: PsiElement, private val to: String) :
    RenameProcessor(at.project, target, to, false, false) {
    val touched = LinkedHashSet<VirtualFile>()
    var references = 0

    override fun isPreviewUsages(usages: Array<out UsageInfo?>): Boolean = false

    override fun showAutomaticRenamingDialog(automaticVariableRenamer: AutomaticRenamer?): Boolean = false

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo?>?>): Boolean {
      val usages = refUsages.get() ?: return false
      val conflicts = MultiMap<PsiElement?, String?>()
      RenameUtil.addConflictDescriptions(usages, conflicts)
      RenamePsiElementProcessor.forElement(target).findExistingNameConflicts(target, to, conflicts, myAllRenames)
      if (!conflicts.isEmpty) {
        throw McpToolError("Renaming would conflict, nothing was renamed: " + conflicts.values().filterNotNull().take(3).joinToString("; ") { StringUtil.removeHtmlTags(it) })
      }
      val files = usages.mapNotNull { it?.virtualFile } + myAllRenames.keys.mapNotNull { PsiUtilCore.getVirtualFile(it) }
      files.firstOrNull { !at.contains(it) }?.let { throw McpToolError("Renaming would change ${it.path}, outside this workspace; nothing was renamed") }
      touched += files
      references = usages.size
      return true
    }
  }

  /** The Local Shared Index query over the worktree; its errors become this server's tool errors. */
  private fun inWorkspace(worktree: String?, block: (Workspace) -> String): String = lsi { IndexWorkspaces.read(worktree, block) }

  private fun locate(worktree: String?): Located = lsi { IndexWorkspaces.locate(worktree) }

  private fun fresh(at: Located, path: String): VirtualFile = lsi { IndexWorkspaces.fresh(at, path) }

  private fun <T> lsi(block: () -> T): T = try {
    block()
  } catch (e: IndexToolError) {
    throw McpToolError(e.message ?: "Failed")
  }

  private companion object {
    const val RENAME_WAIT_S = 30L
    const val PENDING = 0
    const val STARTED = 1
    const val ABANDONED = 2
  }
}

/** Code an agent sends, fitted to where it goes. */
internal object CodeText {
  /** The whitespace before [offset] on its line, or nothing when code comes before it there. */
  fun indentOf(document: Document, offset: Int): String {
    val before = document.charsSequence.subSequence(document.getLineStartOffset(document.getLineNumber(offset)), offset).toString()
    return if (before.isBlank()) before else ""
  }

  /** [code] without its blank first and last lines and its common indentation, each line then at [indent] (the first only if [indentFirst]). */
  fun indented(code: String, indent: String, indentFirst: Boolean): String {
    val lines = code.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.map { it.trimEnd() }
    val common = lines.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
    return lines.mapIndexed { i, line ->
      when {
        line.isBlank() -> ""
        i == 0 && !indentFirst -> line.drop(common)
        else -> indent + line.drop(common)
      }
    }.joinToString("\n")
  }
}
