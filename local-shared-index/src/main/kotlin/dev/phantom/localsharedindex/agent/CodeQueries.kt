// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex.agent

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
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
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.CommonProcessors
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters

/** The queries themselves, inside a read action, over [scope]; [shown] is how a file is named in the answer. */
class CodeQueries(
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
    return targetAt(psi, documentOf(psi), line, name) ?: throw IndexToolError("No symbol named $name at line $line of ${shown(file)}")
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
      0 -> throw IndexToolError("No declaration named $name in ${shown(file)}; see file_outline")
      1 -> found.single()
      else -> throw IndexToolError("$name is declared at lines ${found.joinToString { (document.getLineNumber(offsetOf(it)) + 1).toString() }} of ${shown(file)}; give line")
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
      ?: throw IndexToolError("Phantom has no outline for ${shown(file)}; give line")
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
    if (line < 1 || line > document.lineCount) throw IndexToolError("Line $line is outside the file (it has ${document.lineCount} lines)")
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
      ?: throw IndexToolError("Phantom has no outline for ${shown(file)}; read the file instead")
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

  private fun psiOf(file: VirtualFile): PsiFile = PsiManager.getInstance(project).findFile(file) ?: throw IndexToolError("Cannot read ${shown(file)}")

  private fun documentOf(file: PsiFile): Document =
    PsiDocumentManager.getInstance(project).getDocument(file) ?: throw IndexToolError("Cannot read ${file.name}")

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
