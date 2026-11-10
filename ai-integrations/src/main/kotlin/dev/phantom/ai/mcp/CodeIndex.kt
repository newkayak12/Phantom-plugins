// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.mcp

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopes
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.CommonProcessors
import com.intellij.util.Processor
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.indexing.FindSymbolParameters
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Answers from the IDE's indexes for the agent's own workspace ([worktree]): short `path:line  code` lines instead of
 * whole files. Fakeable; the real one is [IdeCodeIndex]. Called off the EDT.
 */
interface CodeIndex {
  fun findSymbol(worktree: String?, query: String, limit: Int): String
  fun findFile(worktree: String?, query: String, limit: Int): String
  fun findUsages(worktree: String?, path: String, line: Int, name: String, limit: Int): String
  fun fileOutline(worktree: String?, path: String): String
}

/** The queries themselves, inside a read action, over [scope]; [shown] is how a file is named in the answer. */
internal class CodeQueries(
  private val project: Project,
  private val scope: GlobalSearchScope,
  private val shown: (VirtualFile) -> String,
) {
  fun symbols(query: String, limit: Int): String {
    val matcher = NameUtil.buildMatcher("*$query", NameUtil.MatchingCaseSensitivity.NONE)
    val contributors = ChooseByNameContributor.CLASS_EP_NAME.extensionList + ChooseByNameContributor.SYMBOL_EP_NAME.extensionList
    val named = contributors.flatMap { c -> namesOf(c).filter(matcher::matches).map { it to c } }
      .sortedWith(compareBy({ rank(it.first, query) }, { it.first.length }, { it.first }))
    val found = LinkedHashSet<String>()
    var more = false
    fun take(item: NavigationItem): Boolean {
      ProgressManager.checkCanceled()
      val line = lineOf(item) ?: return true
      if (line in found) return true
      if (found.size == limit) { more = true; return false }
      found += line
      return true
    }
    for ((name, contributor) in named) {
      if (contributor is ChooseByNameContributorEx) {
        contributor.processElementsWithName(name, Processor(::take), FindSymbolParameters.wrap(name, scope))
      } else {
        contributor.getItemsByName(name, name, project, false).all(::take)
      }
      if (more) break
    }
    return answer(found.toList(), more, "No symbol matches \"$query\" in this workspace.")
  }

  private fun namesOf(contributor: ChooseByNameContributor): Collection<String> {
    if (contributor !is ChooseByNameContributorEx) return contributor.getNames(project, false).toSet()
    return HashSet<String>().also { contributor.processNames(CommonProcessors.CollectProcessor(it), scope, null) }
  }

  fun files(query: String, limit: Int): String {
    val matcher = NameUtil.buildMatcher("*$query", NameUtil.MatchingCaseSensitivity.NONE)
    val names = HashSet<String>()
    FilenameIndex.processAllFileNames(CommonProcessors.CollectProcessor(names), scope, null)
    val paths = names.filter(matcher::matches)
      .sortedWith(compareBy({ rank(it, query) }, { it.length }, { it }))
      .asSequence()
      .flatMap { name -> FilenameIndex.getVirtualFilesByName(name, scope).map(shown).sorted() }
      .take(limit + 1)
      .toList()
    return answer(paths.take(limit), paths.size > limit, "No file name matches \"$query\" in this workspace.")
  }

  fun usages(file: VirtualFile, line: Int, name: String, limit: Int): String {
    val psi = psiOf(file)
    val document = documentOf(psi)
    val target = targetAt(psi, document, line, name) ?: throw McpToolError("No symbol named $name at line $line of ${shown(file)}")
    val found = LinkedHashSet<String>()
    var more = false
    ReferencesSearch.search(target, scope).forEach(Processor { reference ->
      ProgressManager.checkCanceled()
      val element = reference.element
      val at = element.textRange.startOffset + reference.rangeInElement.startOffset
      val where = lineAt(element.containingFile, at) ?: return@Processor true
      if (where in found) return@Processor true
      if (found.size == limit) { more = true; return@Processor false }
      found += where
      true
    })
    return answer(found.toList(), more, "No usages of $name in this workspace.")
  }

  /** The declaration [name] names on 1-based [line], or the one a reference there resolves to. */
  private fun targetAt(psi: PsiFile, document: Document, line: Int, name: String): PsiElement? {
    if (line < 1 || line > document.lineCount) throw McpToolError("Line $line is outside the file (it has ${document.lineCount} lines)")
    val start = document.getLineStartOffset(line - 1)
    val text = document.getText(com.intellij.openapi.util.TextRange(start, document.getLineEndOffset(line - 1)))
    return generateSequence(text.indexOf(name)) { text.indexOf(name, it + 1) }
      .takeWhile { it >= 0 }
      .firstNotNullOfOrNull { resolve(psi, start + it) }
  }

  private fun resolve(psi: PsiFile, offset: Int): PsiElement? {
    val leaf = psi.findElementAt(offset) ?: return null
    val owner = PsiTreeUtil.getParentOfType(leaf, PsiNameIdentifierOwner::class.java, false)
    if (owner?.nameIdentifier?.textRange?.contains(offset) == true) return owner
    return psi.findReferenceAt(offset)?.resolve()
  }

  fun outline(file: VirtualFile): String {
    val builder = StructureViewBuilder.getProvider().getStructureViewBuilder(file.fileType, file, project) as? TreeBasedStructureViewBuilder
      ?: throw McpToolError("Phantom has no outline for ${shown(file)}; read the file instead")
    val model = builder.createStructureViewModel(null)
    try {
      val lines = ArrayList<String>()
      fun walk(element: TreeElement, depth: Int) {
        ProgressManager.checkCanceled()
        for (child in element.children) {
          if (lines.size == MAX_OUTLINE) return
          val psi = (child as? StructureViewTreeElement)?.value as? PsiElement
          val text = child.presentation.presentableText.orEmpty()
          val line = psi?.takeIf { it.isValid }?.let { p -> documentOf(p.containingFile).getLineNumber(offsetOf(p)) + 1 }
          lines += "  ".repeat(depth) + (line?.let { "$it  " } ?: "") + text
          if (depth < MAX_OUTLINE_DEPTH) walk(child, depth + 1)
        }
      }
      walk(model.root, 0)
      return answer(lines, lines.size == MAX_OUTLINE, "${shown(file)} has no declarations.")
    } finally {
      Disposer.dispose(model)
    }
  }

  private fun lineOf(item: NavigationItem): String? {
    val raw = (item as? PsiElementNavigationItem)?.targetElement ?: item as? PsiElement ?: return null
    val element = raw.navigationElement
    val file = PsiUtilCore.getVirtualFile(element) ?: return null
    if (!scope.contains(file)) return null
    return lineAt(element.containingFile, offsetOf(element))
  }

  private fun offsetOf(element: PsiElement): Int = (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: element.textOffset

  private fun lineAt(file: PsiFile, offset: Int): String? {
    val virtual = file.virtualFile ?: return null
    val document = documentOf(file)
    if (offset !in 0..document.textLength) return null
    val line = document.getLineNumber(offset)
    val code = document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString().trim()
    return "${shown(virtual)}:${line + 1}  ${code.take(MAX_CODE)}"
  }

  private fun psiOf(file: VirtualFile): PsiFile = PsiManager.getInstance(project).findFile(file) ?: throw McpToolError("Cannot read ${shown(file)}")

  private fun documentOf(file: PsiFile): Document =
    PsiDocumentManager.getInstance(project).getDocument(file) ?: throw McpToolError("Cannot read ${file.name}")

  private fun answer(lines: List<String>, more: Boolean, none: String): String = when {
    lines.isEmpty() -> none
    more -> (lines + "... more results; narrow the query or raise limit").joinToString("\n")
    else -> lines.joinToString("\n")
  }

  private fun rank(name: String, query: String): Int = when {
    name == query -> 0
    name.equals(query, ignoreCase = true) -> 1
    name.startsWith(query, ignoreCase = true) -> 2
    else -> 3
  }

  private companion object {
    const val MAX_CODE = 160
    const val MAX_OUTLINE = 300
    const val MAX_OUTLINE_DEPTH = 3
  }
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

  private class Workspace(val root: Path, val queries: CodeQueries) {
    fun file(path: String): VirtualFile {
      val given = Path.of(path.removePrefix("file://"))
      val absolute = (if (given.isAbsolute) given else root.resolve(given)).normalize()
      if (!absolute.startsWith(root)) throw McpToolError("$path is outside this workspace")
      return LocalFileSystem.getInstance().findFileByNioFile(absolute)?.takeUnless { it.isDirectory } ?: throw McpToolError("No such file: $path")
    }
  }

  private fun inWorkspace(worktree: String?, block: (Workspace) -> String): String {
    val root = Path.of(worktree ?: throw McpToolError(NOT_AVAILABLE)).normalize()
    val project = ProjectManager.getInstance().openProjects
      .filter { p -> p.basePath?.let { root.startsWith(Path.of(it)) } == true }
      .maxByOrNull { it.basePath!!.length }
      ?: throw McpToolError(NOT_OPEN)
    val promise = ReadAction.nonBlocking<Result<String>> {
      try {
        if (DumbService.isDumb(project)) throw McpToolError(INDEXING)
        val dir = LocalFileSystem.getInstance().findFileByNioFile(root) ?: throw McpToolError(NOT_OPEN)
        val scope = GlobalSearchScope.projectScope(project).intersectWith(GlobalSearchScopes.directoryScope(project, dir, true))
        val shown = { file: VirtualFile -> runCatching { root.relativize(file.toNioPath()).toString() }.getOrDefault(file.path) }
        Result.success(block(Workspace(root, CodeQueries(project, scope, shown))))
      } catch (e: McpToolError) {
        Result.failure(e)
      }
    }.expireWith(project).submit(AppExecutorUtil.getAppExecutorService())
    return try {
      promise.get(TIMEOUT_S, TimeUnit.SECONDS).getOrThrow()
    } catch (e: TimeoutException) {
      promise.cancel()
      throw McpToolError(TOO_SLOW)
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    }
  }

  private companion object {
    const val TIMEOUT_S = 20L
    const val NOT_AVAILABLE = "The IDE index is not available for this terminal; use your own search."
    const val NOT_OPEN = "This workspace is not open in Phantom, so it has no index; use your own search."
    const val INDEXING = "Phantom is still indexing this workspace; use your own search for now."
    const val TOO_SLOW = "The IDE index took too long; narrow the query or use your own search."
  }
}
