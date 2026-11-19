// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.mcp

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiWhiteSpace
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
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
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger
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

  /** The declaration [name] names on 1-based [line] of [file], or the one a reference there resolves to. */
  fun target(file: VirtualFile, line: Int, name: String): PsiElement {
    val psi = psiOf(file)
    return targetAt(psi, documentOf(psi), line, name) ?: throw McpToolError("No symbol named $name at line $line of ${shown(file)}")
  }

  fun inWorkspace(element: PsiElement): Boolean = PsiUtilCore.getVirtualFile(element)?.let(scope::contains) == true

  /**
   * The declaration [name] in [file]: the one on 1-based [line] (or the one a reference there resolves to), else the
   * declaration of the file's outline with that name, where `Outer.name` picks the one inside `Outer`.
   */
  fun declaration(file: VirtualFile, line: Int?, name: String): PsiElement {
    val simple = name.substringAfterLast('.')
    if (line != null) return target(file, line, simple).navigationElement
    val qualifiers = name.split('.').dropLast(1)
    val found = declarationsOf(file).filter { (element, outer) ->
      (element as? PsiNamedElement)?.name == simple && outer.takeLast(qualifiers.size) == qualifiers
    }.map { it.first }
    val document = documentOf(psiOf(file))
    return when (found.size) {
      0 -> throw McpToolError("No declaration named $name in ${shown(file)}; see file_outline")
      1 -> found.single()
      else -> throw McpToolError("$name is declared at lines ${found.joinToString { (document.getLineNumber(offsetOf(it)) + 1).toString() }} of ${shown(file)}; give line")
    }
  }

  /** The source of a declaration with its line numbers, doc comment and annotations included. */
  fun read(declaration: PsiElement): String {
    val file = declaration.containingFile
    val document = documentOf(file)
    val first = document.getLineNumber(declaration.textRange.startOffset)
    val last = document.getLineNumber(declaration.textRange.endOffset)
    val shownLast = minOf(last, first + MAX_READ_LINES - 1)
    val where = file.virtualFile?.let(shown) ?: file.name
    val lines = (first..shownLast).map { n ->
      "${n + 1}  " + document.getText(TextRange(document.getLineStartOffset(n), document.getLineEndOffset(n)))
    }
    val more = if (shownLast < last) listOf("... ${last - shownLast} more lines; read $where from line ${shownLast + 2}") else emptyList()
    return (listOf("$where:${first + 1}-${last + 1}") + lines + more).joinToString("\n")
  }

  /** The declarations of the file's outline, each with the names of the declarations it is inside. */
  private fun declarationsOf(file: VirtualFile): List<Pair<PsiElement, List<String>>> {
    val builder = StructureViewBuilder.getProvider().getStructureViewBuilder(file.fileType, file, project) as? TreeBasedStructureViewBuilder
      ?: throw McpToolError("Phantom has no outline for ${shown(file)}; give line")
    val model = builder.createStructureViewModel(null)
    try {
      val found = ArrayList<Pair<PsiElement, List<String>>>()
      fun walk(element: TreeElement, outer: List<String>, depth: Int) {
        for (child in element.children) {
          val psi = ((child as? StructureViewTreeElement)?.value as? PsiElement)?.takeIf { it.isValid } ?: continue
          found += psi to outer
          if (depth < MAX_OUTLINE_DEPTH) walk(child, outer + ((psi as? PsiNamedElement)?.name ?: ""), depth + 1)
        }
      }
      walk(model.root, emptyList(), 0)
      return found
    } finally {
      Disposer.dispose(model)
    }
  }

  /** Where the symbol is declared, its signature and its doc comment: what reading its file would be for. */
  fun symbolInfo(file: VirtualFile, line: Int, name: String): String {
    val declaration = target(file, line, name).navigationElement
    return listOfNotNull(whereIs(declaration), signature(declaration), docOf(declaration)).joinToString("\n")
  }

  /** Who calls the symbol, each with the member the call is in, and what the symbol calls within this workspace. */
  fun calls(file: VirtualFile, line: Int, name: String, limit: Int): String {
    val target = target(file, line, name).navigationElement
    val members = HashMap<VirtualFile, List<Pair<TextRange, String>>>()
    val callers = LinkedHashSet<String>()
    var moreCallers = false
    ReferencesSearch.search(target, scope).forEach(Processor { reference ->
      ProgressManager.checkCanceled()
      val element = reference.element
      val at = element.textRange.startOffset + reference.rangeInElement.startOffset
      val where = lineAt(element.containingFile, at, memberAt(element.containingFile, at, members)) ?: return@Processor true
      if (where in callers) return@Processor true
      if (callers.size == limit) { moreCallers = true; return@Processor false }
      callers += where
      true
    })
    val called = LinkedHashSet<String>()
    var moreCalled = false
    if (inWorkspace(target)) {
      val text = target.containingFile.viewProvider.contents
      target.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
          ProgressManager.checkCanceled()
          for (reference in element.references) {
            if (!isCall(text, element.textRange.startOffset + reference.rangeInElement.endOffset)) continue
            val callee = reference.resolve()?.navigationElement ?: continue
            if (PsiTreeUtil.isAncestor(target, callee, false) || !inWorkspace(callee)) continue
            val where = lineAt(callee.containingFile, offsetOf(callee)) ?: continue
            if (where in called) continue
            if (called.size == limit) { moreCalled = true; stopWalking(); return }
            called += where
          }
          super.visitElement(element)
        }
      })
    }
    return "Called from:\n" + answer(callers.toList(), moreCallers, "nowhere in this workspace") +
      "\nCalls:\n" + answer(called.toList(), moreCalled, if (inWorkspace(target)) "nothing in this workspace" else "unknown: $name is declared outside this workspace")
  }

  fun usages(file: VirtualFile, line: Int, name: String, limit: Int): String {
    val target = target(file, line, name)
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

  /** `path:line  code`, with `[member]` before the code when the line is inside one. */
  private fun lineAt(file: PsiFile, offset: Int, member: String? = null): String? {
    val virtual = file.virtualFile ?: return null
    val document = documentOf(file)
    if (offset !in 0..document.textLength) return null
    val line = document.getLineNumber(offset)
    val code = document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString().trim()
    return "${shown(virtual)}:${line + 1}  ${member?.let { "[$it]  " }.orEmpty()}${code.take(MAX_CODE)}"
  }

  private fun whereIs(declaration: PsiElement): String {
    if (inWorkspace(declaration)) declaration.containingFile?.let { lineAt(it, offsetOf(declaration)) }?.let { return it }
    val file = PsiUtilCore.getVirtualFile(declaration)
    return "Declared outside this workspace, in ${file?.path?.substringAfterLast("!/") ?: "a library"}"
  }

  /** The declaration's head without its doc comment or body: up to `{`, `;`, an initializer's `=` or Python's `:`. */
  private fun signature(declaration: PsiElement): String? {
    var start = 0
    var child = declaration.firstChild
    while (child is PsiComment || child is PsiWhiteSpace) { start += child.textLength; child = child.nextSibling }
    val text = declaration.text ?: return null
    val head = StringBuilder()
    var depth = 0
    for (i in start until text.length) {
      val c = text[i]
      val next = text.getOrNull(i + 1)
      val previous = text.getOrNull(i - 1)
      when {
        c == '(' || c == '[' || c == '<' -> depth++
        (c == ')' || c == ']' || c == '>') && previous != '-' && previous != '=' -> depth = (depth - 1).coerceAtLeast(0)
        depth == 0 && (c == '{' || c == ';') -> break
        depth == 0 && c == '=' && next != '=' && next != '>' && previous != '!' && previous != '=' -> break
        depth == 0 && c == '\n' && head.trimEnd().endsWith(":") -> break
      }
      head.append(c)
      if (head.length >= MAX_SIGNATURE) break
    }
    return head.toString().replace(WHITESPACE, " ").trim().removeSuffix(":").trim().ifEmpty { null }
  }

  /** The comment right before or at the start of the declaration, without its comment marks. */
  private fun docOf(declaration: PsiElement): String? {
    val comment = declaration.firstChild as? PsiComment
      ?: generateSequence(declaration.prevSibling) { it.prevSibling }.firstOrNull { it !is PsiWhiteSpace } as? PsiComment
      ?: return null
    return comment.text.lines()
      .map { it.trim().removePrefix("/**").removePrefix("/*").removeSuffix("*/").removePrefix("*").removePrefix("//").removePrefix("#").trim() }
      .filter { it.isNotEmpty() }
      .joinToString(" ")
      .take(MAX_DOC)
      .ifEmpty { null }
  }

  /** A reference ending at [end] is a call when `(` comes next. */
  private fun isCall(text: CharSequence, end: Int): Boolean {
    var i = end
    while (i < text.length && text[i].isWhitespace()) i++
    return i < text.length && text[i] == '('
  }

  /** The innermost member of the file's structure (method, function, class) holding [offset]. */
  private fun memberAt(file: PsiFile, offset: Int, cache: MutableMap<VirtualFile, List<Pair<TextRange, String>>>): String? {
    val virtual = file.virtualFile ?: return null
    return cache.getOrPut(virtual) { membersOf(virtual) }.filter { it.first.contains(offset) }.minByOrNull { it.first.length }?.second
  }

  private fun membersOf(file: VirtualFile): List<Pair<TextRange, String>> {
    val builder = StructureViewBuilder.getProvider().getStructureViewBuilder(file.fileType, file, project) as? TreeBasedStructureViewBuilder
      ?: return emptyList()
    val model = builder.createStructureViewModel(null)
    try {
      val members = ArrayList<Pair<TextRange, String>>()
      fun walk(element: TreeElement, depth: Int) {
        for (child in element.children) {
          val psi = (child as? StructureViewTreeElement)?.value as? PsiElement
          psi?.takeIf { it.isValid }?.let { members += it.textRange to child.presentation.presentableText.orEmpty() }
          if (depth < MAX_OUTLINE_DEPTH) walk(child, depth + 1)
        }
      }
      walk(model.root, 0)
      return members
    } finally {
      Disposer.dispose(model)
    }
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
    const val MAX_SIGNATURE = 300
    const val MAX_DOC = 800
    const val MAX_READ_LINES = 500
    val WHITESPACE = Regex("\\s+")
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
  override fun symbolInfo(worktree: String?, path: String, line: Int, name: String) =
    inWorkspace(worktree) { it.queries.symbolInfo(it.file(path), line, name) }
  override fun calls(worktree: String?, path: String, line: Int, name: String, limit: Int) =
    inWorkspace(worktree) { it.queries.calls(it.file(path), line, name, limit) }

  /** The worktree [root] in the open [project] that holds it. */
  private class Located(val project: Project, val root: Path) {
    fun absolute(path: String): Path {
      val given = Path.of(path.removePrefix("file://"))
      val absolute = (if (given.isAbsolute) given else root.resolve(given)).normalize()
      if (!absolute.startsWith(root)) throw McpToolError("$path is outside this workspace")
      return absolute
    }

    fun shown(file: VirtualFile): String = runCatching { root.relativize(file.toNioPath()).toString() }.getOrDefault(file.path)

    fun contains(file: VirtualFile): Boolean = runCatching { file.toNioPath().normalize().startsWith(root) }.getOrDefault(false)

    /** Needs a read action. */
    fun workspace(): Workspace {
      if (DumbService.isDumb(project)) throw McpToolError(INDEXING)
      val dir = LocalFileSystem.getInstance().findFileByNioFile(root) ?: throw McpToolError(NOT_OPEN)
      val scope = GlobalSearchScope.projectScope(project).intersectWith(GlobalSearchScopes.directoryScope(project, dir, true))
      return Workspace(this, CodeQueries(project, scope, ::shown))
    }
  }

  private class Workspace(val at: Located, val queries: CodeQueries) {
    fun file(path: String): VirtualFile =
      LocalFileSystem.getInstance().findFileByNioFile(at.absolute(path))?.takeUnless { it.isDirectory } ?: throw McpToolError("No such file: $path")
  }

  private fun locate(worktree: String?): Located {
    val root = Path.of(worktree ?: throw McpToolError(NOT_AVAILABLE)).normalize()
    val project = ProjectManager.getInstance().openProjects
      .filter { p -> p.basePath?.let { root.startsWith(Path.of(it)) } == true }
      .maxByOrNull { it.basePath!!.length }
      ?: throw McpToolError(NOT_OPEN)
    return Located(project, root)
  }

  /** The file as it is on disk now: the agent may have just written it. */
  private fun fresh(at: Located, path: String): VirtualFile {
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(at.absolute(path))?.takeUnless { it.isDirectory }
      ?: throw McpToolError("No such file: $path")
    VfsUtil.markDirtyAndRefresh(false, false, false, file)
    return file
  }

  override fun fileProblems(worktree: String?, path: String, warnings: Boolean): String {
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
      val psi = PsiManager.getInstance(at.project).findFile(file) ?: throw McpToolError("Cannot read $path")
      if (!ProblemHighlightFilter.shouldProcessFileInBatch(psi)) throw McpToolError("Phantom does not analyze $path: it is not in the project's sources")
    }
    val indicator = ProgressIndicatorBase()
    val analysis = AppExecutorUtil.getAppExecutorService().submit(Callable {
      ProgressManager.getInstance().runProcess(Computable { CodeSmellDetector.getInstance(at.project).findCodeSmells(listOf(file)) }, indicator)
    })
    val found = try {
      analysis.get(PROBLEMS_TIMEOUT_S, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
      indicator.cancel()
      throw McpToolError("The IDE's analysis of $path took too long; build instead")
    } catch (e: ExecutionException) {
      if (e.cause is ProcessCanceledException) throw McpToolError("The analysis was interrupted by a change; call again")
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

  override fun rename(worktree: String?, path: String, line: Int, name: String, newName: String): String {
    val at = locate(worktree)
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(at.root)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
      ?: throw McpToolError(NOT_OPEN)
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
        done.complete(block())
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

  private fun inWorkspace(worktree: String?, block: (Workspace) -> String): String {
    val at = locate(worktree)
    val project = at.project
    val promise = ReadAction.nonBlocking<Result<String>> {
      try {
        Result.success(block(at.workspace()))
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
    const val PROBLEMS_TIMEOUT_S = 60L
    const val RENAME_WAIT_S = 30L
    const val COMMIT_WAIT_S = 5L
    const val MAX_PROBLEMS = 100
    const val PENDING = 0
    const val STARTED = 1
    const val ABANDONED = 2
    const val NOT_AVAILABLE = "The IDE index is not available for this terminal; use your own search."
    const val NOT_OPEN = "This workspace is not open in Phantom, so it has no index; use your own search."
    const val INDEXING = "Phantom is still indexing this workspace; use your own search for now."
    const val TOO_SLOW = "The IDE index took too long; narrow the query or use your own search."
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
