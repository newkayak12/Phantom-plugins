// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.ide.scratch.ScratchesSearchScope
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenameDialog
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import dev.phantom.db.core.exec.DbConsoles
import dev.phantom.db.core.exec.DbNames
import java.awt.GridBagConstraints
import javax.swing.JCheckBox
import javax.swing.JPanel

/**
 * A reference to a database table or column from outside SQL, such as a JPA `@Table(name = …)`. Usages include them;
 * a rename changes them only when asked to ([SqlDbRenameOptions.updateMappings]).
 */
interface SqlDbMappingReference : PsiReference

/** Code that maps tables and columns by name (JPA in the jvm module): whether the project has any, for the rename dialog. */
interface SqlDbMappings {
  fun isAvailable(project: Project): Boolean

  companion object {
    val EP_NAME: ExtensionPointName<SqlDbMappings> = ExtensionPointName.create("dev.phantom.db.mappings")

    fun isAvailable(project: Project): Boolean = EP_NAME.extensionList.any { it.isAvailable(project) }
  }
}

/**
 * The choices of one rename, kept on the renamed [SqlDbTarget] (each rename resolves a new one), so two renames never
 * see each other's choices. Both are off unless ticked.
 */
data class SqlDbRenameOptions(val updateMappings: Boolean = false, val generateAlter: Boolean = false) {
  companion object {
    private val KEY = Key.create<SqlDbRenameOptions>("phantom.db.rename.options")

    fun of(element: PsiElement): SqlDbRenameOptions = element.getUserData(KEY) ?: SqlDbRenameOptions()

    fun set(element: PsiElement, options: SqlDbRenameOptions) = element.putUserData(KEY, options)
  }
}

/** The scope plus the consoles, which live under Scratches and so outside "Project Files". */
internal fun withConsoles(project: Project, scope: SearchScope): SearchScope =
  // scratches first: the project scope's own union takes any scope inside the project model for one of its parts
  if (scope is GlobalSearchScope) ScratchesSearchScope.getScratchesScope(project).union(scope) else scope

/**
 * Usages of a database table or column: the name as a word, case-insensitively as SQL names are, in SQL files and in
 * strings (SQL injected into Java and Kotlin), each kept when its reference names the same table or column of the same
 * data source. Identifiers of Java or Kotlin code are not searched.
 */
class SqlDbReferenceSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
  override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
    val target = parameters.elementToSearch as? SqlDbTarget ?: return
    val scope = parameters.effectiveSearchScope
    val sqlFiles = when (scope) {
      is GlobalSearchScope -> GlobalSearchScope.getScopeRestrictedByFileTypes(scope, SqlFileType.INSTANCE)
      is LocalSearchScope -> LocalSearchScope.getScopeRestrictedByFileTypes(scope, SqlFileType.INSTANCE)
      else -> scope
    }
    parameters.optimizer.searchWord(target.name, sqlFiles, UsageSearchContext.IN_CODE, false, target)
    // SQL in Java and Kotlin strings: every string with the word, then the names of the SQL injected into it. Walking
    // the injected SQL instead of mapping offsets keeps names next to escapes (`\"CUSTOMER\"`) found.
    if (scope !is GlobalSearchScope) return
    // the data source's consoles, which need not be indexed (they live under Scratches)
    val psi = PsiManager.getInstance(parameters.project)
    for (console in DbConsoles.consoles(target.dataSource.id)) {
      if (!scope.contains(console)) continue
      val file = psi.findFile(console) as? SqlFile ?: continue
      if (!references(file, target).all { consumer.process(it) }) return
    }
    val hosts = HashSet<PsiLanguageInjectionHost>()
    PsiSearchHelper.getInstance(parameters.project).processElementsWithWord({ element, _ ->
      val host = element as? PsiLanguageInjectionHost
      if (host == null || !hosts.add(host)) return@processElementsWithWord true
      var go = true
      InjectedLanguageManager.getInstance(host.project).enumerate(host) { injected, _ ->
        if (go && injected is SqlFile) go = references(injected, target).all { consumer.process(it) }
      }
      go
    }, scope, target.name, UsageSearchContext.IN_STRINGS, false)
  }

  private fun references(file: SqlFile, target: SqlDbTarget): List<PsiReference> =
    PsiTreeUtil.findChildrenOfAnyType(file, SqlTableRef::class.java, SqlColumnRef::class.java, SqlQualifier::class.java)
      .mapNotNull { it.reference }.filter { it.isReferenceTo(target) }
}

/** Find Usages of a table or column also looks in the consoles. */
class SqlDbFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
  override fun canFindUsages(element: PsiElement): Boolean = element is SqlDbTarget

  override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler = object : FindUsagesHandler(element) {
    override fun processElementUsages(element: PsiElement, processor: Processor<in UsageInfo>, options: FindUsagesOptions): Boolean {
      val withConsoles = options.clone().also { it.searchScope = withConsoles(project, it.searchScope) }
      return super.processElementUsages(element, processor, withConsoles)
    }
  }
}

/**
 * Changes to SQL text wherever it is: a .sql file or console, or a Java or Kotlin string it is injected into. Edits to
 * injected SQL go to the host document, escaped for the host string; all edits of one document apply from the last
 * offset to the first, so several in one string do not move each other.
 */
object SqlTextEdits {
  class Edit(val document: Document, val range: TextRange, val text: String)

  /** [range] of [file] (SQL, injected or not) replaced by [text]. */
  fun edit(file: PsiFile, range: TextRange, text: String): Edit? {
    val project = file.project
    val manager = InjectedLanguageManager.getInstance(project)
    if (!manager.isInjectedFragment(file)) {
      return PsiDocumentManager.getInstance(project).getDocument(file)?.let { Edit(it, range, text) }
    }
    val host = manager.getInjectionHost(file) ?: return null
    val document = PsiDocumentManager.getInstance(project).getDocument(manager.getTopLevelFile(file)) ?: return null
    return Edit(document, manager.injectedToHost(file, range), escape(host, text))
  }

  /** [range] of a Java or Kotlin string [host] (not injected) set to [text], escaped for it. */
  fun hostEdit(host: PsiLanguageInjectionHost, range: TextRange, text: String): Edit? {
    val document = PsiDocumentManager.getInstance(host.project).getDocument(host.containingFile) ?: return null
    return Edit(document, range.shiftRight(host.textRange.startOffset), escape(host, text))
  }

  /** [text] as it must be written inside the string [host]: quotes, backslashes (and `$` in Kotlin) escaped. */
  fun escape(host: PsiLanguageInjectionHost, text: String): String {
    val kotlin = host.language.id == "kotlin"
    if (host.text.startsWith("\"\"\"")) return if (kotlin) text.replace("$", "\${'$'}") else text // text block, raw string
    val escaped = StringUtil.escapeStringCharacters(text)
    return if (kotlin) escaped.replace("$", "\\$") else escaped
  }

  fun apply(project: Project, edits: Collection<Edit>) {
    val documents = PsiDocumentManager.getInstance(project)
    for ((document, list) in edits.groupBy { it.document }) {
      documents.doPostponedOperationsAndUnblockDocument(document)
      for (e in list.distinctBy { it.range }.sortedByDescending { it.range.startOffset }) document.replaceString(e.range.startOffset, e.range.endOffset, e.text)
      documents.commitDocument(document)
    }
  }

  /** The name token [leaf] renamed to [newName] (see [SqlRename.nameText]); a quoted name changes between its quotes only. */
  fun rename(leaf: PsiElement, newName: String, dbms: Dbms?): Edit? {
    val old = leaf.text
    val range = leaf.textRange
    val q = SqlRename.quoteLength(old)
    if (q > 0) return edit(leaf.containingFile, TextRange(range.startOffset + q, range.endOffset - q), SqlRename.quotedText(old[q - 1], newName, dbms))
    return edit(leaf.containingFile, range, SqlRename.nameText(old, newName, dbms))
  }
}

/** Replaces the name in a table, column or qualifier reference, keeping how it was written (quotes, case). */
class SqlNameManipulator : AbstractElementManipulator<PsiElement>() {
  override fun handleContentChange(element: PsiElement, range: TextRange, newContent: String): PsiElement {
    val leaf = element.findElementAt(range.startOffset) ?: return element
    val file = element.containingFile
    val dbms = SqlSchema.dataSourceFor(file, strict = false)?.dialect()
    val edit = SqlTextEdits.rename(leaf, newContent, dbms) ?: return element
    val manager = InjectedLanguageManager.getInstance(element.project)
    val injected = manager.isInjectedFragment(file)
    val hostFile = manager.getTopLevelFile(file)
    val offset = edit.range.startOffset
    SqlTextEdits.apply(element.project, listOf(edit))
    if (element.isValid) return element
    val at = if (injected) manager.findInjectedElementAt(hostFile, offset) else hostFile.findElementAt(offset)
    return PsiTreeUtil.getParentOfType(at, element.javaClass, false) ?: element
  }
}

object SqlRename {
  private val PLAIN = Regex("[A-Za-z_][A-Za-z0-9_]*")

  fun isQuoted(text: String): Boolean = quoteLength(text) > 0

  /** How many characters the quotes of a quoted name take on each side: 1 (`"a"`, `` `a` ``, `[a]`), 2 for `\"a\"` in a Java or Kotlin string, else 0. */
  fun quoteLength(text: String): Int = when {
    text.length >= 4 && text.startsWith("\\\"") && text.endsWith("\\\"") -> 2
    text.length >= 2 && text[0] in "\"`[" && text.last() == (if (text[0] == '[') ']' else text[0]) -> 1
    else -> 0
  }

  /**
   * [newName] inside the quotes [open]: a plain name in the case the database stores it unquoted (upper in H2, lower
   * in PostgreSQL, as typed elsewhere), so quoted and unquoted references and the ALTER all name one table; any other
   * name exactly, its closing quotes doubled.
   */
  fun quotedText(open: Char, newName: String, dbms: Dbms?): String {
    val name = if (PLAIN.matches(newName)) when (dbms) {
      Dbms.H2 -> newName.uppercase()
      Dbms.POSTGRESQL -> newName.lowercase()
      else -> newName
    } else newName
    val close = if (open == '[') ']' else open
    return name.replace("$close", "$close$close")
  }

  /**
   * How [newName] is written where [old] was: a quoted name stays quoted (see [quotedText]); an unquoted one keeps its
   * all-lower or all-upper case, and is quoted when [newName] needs it.
   */
  fun nameText(old: String, newName: String, dbms: Dbms?): String {
    val q = quoteLength(old)
    if (q > 0) return old.take(q) + quotedText(old[q - 1], newName, dbms) + old.takeLast(q)
    if (!PLAIN.matches(newName) || SqlTokens.isKeyword(newName)) {
      return dbms?.let { DbNames.quote(it, newName) } ?: "\"${newName.replace("\"", "\"\"")}\""
    }
    val letters = old.filter { it.isLetter() }
    return when {
      letters.isNotEmpty() && letters == letters.lowercase() -> newName.lowercase()
      letters.isNotEmpty() && letters == letters.uppercase() -> newName.uppercase()
      else -> newName
    }
  }

  /** The DDL that renames [target] in the database to [newName]; not run, only written into a console. */
  fun alterStatement(target: SqlDbTarget, newName: String): String {
    val dbms = target.dataSource.dialect()
    val ref = target.table
    val qualifier = if (dbms.usesCatalogs) ref.catalog else ref.schema
    val table = listOfNotNull(qualifier, ref.name).joinToString(".") { DbNames.identifier(dbms, it) }
    val new = DbNames.quote(dbms, newName)
    val column = target.column
    val sql = when {
      column != null -> {
        val old = DbNames.identifier(dbms, column.name)
        if (dbms == Dbms.H2) "ALTER TABLE $table ALTER COLUMN $old RENAME TO $new" else "ALTER TABLE $table RENAME COLUMN $old TO $new"
      }
      dbms.usesCatalogs -> "RENAME TABLE $table TO ${listOfNotNull(qualifier?.let { DbNames.identifier(dbms, it) }, new).joinToString(".")}"
      dbms == Dbms.POSTGRESQL && ref.isMaterializedView -> "ALTER MATERIALIZED VIEW $table RENAME TO $new"
      dbms == Dbms.POSTGRESQL && ref.isView -> "ALTER VIEW $table RENAME TO $new"
      else -> "ALTER TABLE $table RENAME TO $new"
    }
    // the generic form is standard SQL; some databases spell it differently
    return if (dbms == Dbms.GENERIC) "-- check for your database\n$sql" else sql
  }

  /**
   * The console the ALTER goes to: the one the rename started from when it is a console of the target's data source,
   * else the data source's first console (made when there is none). Blocking file access.
   */
  fun consoleFor(target: SqlDbTarget): VirtualFile? {
    val file = target.containingFile?.takeUnless { InjectedLanguageManager.getInstance(target.project).isInjectedFragment(it) }?.virtualFile
    if (file != null && DbConsoles.isConsole(file) && DbConsoles.dataSourceFor(target.project, file)?.id == target.dataSource.id) return file
    return DbConsoles.consoleFile(target.dataSource)
  }

  /** Appends [sql] to [console] and opens it; nothing runs. */
  fun appendToConsole(project: Project, console: VirtualFile, sql: String) {
    val document = FileDocumentManager.getInstance().getDocument(console) ?: return
    val prefix = if (document.textLength == 0 || document.text.endsWith("\n")) "" else "\n"
    document.insertString(document.textLength, "$prefix$sql;\n")
    if (!ApplicationManager.getApplication().isUnitTestMode) {
      ApplicationManager.getApplication().invokeLater({ FileEditorManager.getInstance(project).openFile(console, true) }, project.disposed)
    }
  }
}

/**
 * Rename of a database table or column: the names in SQL (.sql files, consoles, SQL strings) change, the database does
 * not. The dialog can also write the ALTER statement into a console of the data source, and rename JPA mappings.
 */
class SqlDbRenameProcessor : RenamePsiElementProcessor() {
  override fun canProcessElement(element: PsiElement): Boolean = element is SqlDbTarget

  override fun createRenameDialog(project: Project, element: PsiElement, nameSuggestionContext: PsiElement?, editor: Editor?): RenameDialog =
    SqlDbRenameDialog(project, element, nameSuggestionContext, editor)

  override fun findReferences(element: PsiElement, searchScope: SearchScope, searchInCommentsAndStrings: Boolean): Collection<PsiReference> {
    // while the usages are searched, before the write action: the console the ALTER goes to (blocking file access)
    if (element is SqlDbTarget && SqlDbRenameOptions.of(element).generateAlter) element.putUserData(CONSOLE, SqlRename.consoleFor(element))
    val all = super.findReferences(element, withConsoles(element.project, searchScope), searchInCommentsAndStrings)
    return if (SqlDbRenameOptions.of(element).updateMappings) all else all.filterNot { it is SqlDbMappingReference }
  }

  override fun renameElement(element: PsiElement, newName: String, usages: Array<out UsageInfo>, listener: RefactoringElementListener?) {
    val target = element as? SqlDbTarget ?: return
    val dbms = target.dataSource.dialect()
    // every edit is computed first and applied per document from the end: several names in one string stay right
    val edits = usages.mapNotNull { usage ->
      val ref = usage.reference ?: return@mapNotNull null
      val host = ref.element
      if (ref is SqlDbMappingReference) {
        if (host is PsiLanguageInjectionHost) SqlTextEdits.hostEdit(host, ref.rangeInElement, newName) else null
      }
      else host.findElementAt(ref.rangeInElement.startOffset)?.let { SqlTextEdits.rename(it, newName, dbms) }
    }
    SqlTextEdits.apply(target.project, edits)
    if (SqlDbRenameOptions.of(target).generateAlter) {
      target.getUserData(CONSOLE)?.let { SqlRename.appendToConsole(target.project, it, SqlRename.alterStatement(target, newName)) }
    }
    listener?.elementRenamed(element)
  }

  override fun isInplaceRenameSupported(): Boolean = false

  // names in comments and plain text are not database names
  override fun isToSearchInComments(element: PsiElement): Boolean = false
  override fun setToSearchInComments(element: PsiElement, enabled: Boolean) {}
  override fun isToSearchForTextOccurrences(element: PsiElement): Boolean = false
  override fun setToSearchForTextOccurrences(element: PsiElement, enabled: Boolean) {}

  private companion object {
    val CONSOLE = Key.create<VirtualFile>("phantom.db.rename.console")
  }
}

class SqlDbRenameDialog(project: Project, element: PsiElement, context: PsiElement?, editor: Editor?) : RenameDialog(project, element, context, editor) {
  private var alter: JCheckBox? = null
  private var mappings: JCheckBox? = null

  override fun createCheckboxes(panel: JPanel, gbConstraints: GridBagConstraints) {
    super.createCheckboxes(panel, gbConstraints)
    gbConstraints.gridwidth = GridBagConstraints.REMAINDER
    gbConstraints.gridx = 0
    gbConstraints.gridy = GridBagConstraints.RELATIVE
    val target = psiElement as? SqlDbTarget
    alter = JCheckBox(PhantomDbBundle.message("sql.rename.generate.alter", target?.dataSource?.name.orEmpty()), false).also { panel.add(it, gbConstraints) }
    if (SqlDbMappings.isAvailable(project)) {
      mappings = JCheckBox(PhantomDbBundle.message("sql.rename.update.mappings"), false).also { panel.add(it, gbConstraints) }
    }
  }

  override fun doAction() {
    SqlDbRenameOptions.set(psiElement, SqlDbRenameOptions(updateMappings = mappings?.isSelected == true, generateAlter = alter?.isSelected == true))
    super.doAction()
  }
}
