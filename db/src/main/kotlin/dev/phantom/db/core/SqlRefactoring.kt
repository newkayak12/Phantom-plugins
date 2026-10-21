// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.lang.ASTFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.PostprocessReformattingAspect
import com.intellij.psi.impl.source.codeStyle.CodeEditUtil
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
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

/** The choices of the rename dialog for a table or column; both off unless ticked in this rename. */
object SqlDbRenameOptions {
  @Volatile var updateMappings: Boolean = false
  @Volatile var generateAlter: Boolean = false
}

/**
 * Usages of a database table or column: every word that could be its name, case-insensitively as SQL names are, in
 * code and strings (SQL injected into Java and Kotlin), each kept when its reference resolves to the same table or
 * column of the same data source.
 */
class SqlDbReferenceSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
  override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
    val target = parameters.elementToSearch as? SqlDbTarget ?: return
    val context = (UsageSearchContext.IN_CODE.toInt() or UsageSearchContext.IN_FOREIGN_LANGUAGES.toInt() or UsageSearchContext.IN_STRINGS.toInt()).toShort()
    parameters.optimizer.searchWord(target.name, parameters.effectiveSearchScope, context, false, target)
  }
}

/** Replaces the name in a table, column or qualifier reference, keeping how it was written (quotes, case). */
class SqlNameManipulator : AbstractElementManipulator<PsiElement>() {
  override fun handleContentChange(element: PsiElement, range: TextRange, newContent: String): PsiElement {
    val leaf = element.findElementAt(range.startOffset)?.node as? LeafElement ?: return element
    val dbms = SqlSchema.dataSourceFor(element.containingFile, strict = false)?.dialect()
    val text = SqlRename.nameText(leaf.text, newContent, dbms)
    val type = when {
      text.first() in "\"`[" -> SqlTokens.QUOTED_IDENTIFIER
      SqlTokens.isKeyword(text) -> SqlTokens.KEYWORD
      else -> SqlTokens.IDENTIFIER
    }
    val replacement = ASTFactory.leaf(type, text)
    // a name changes in place: nothing to reformat around it
    CodeEditUtil.setNodeGenerated(replacement, true)
    PostprocessReformattingAspect.getInstance(element.project).disablePostprocessFormattingInside { leaf.treeParent.replaceChild(leaf, replacement) }
    return element
  }
}

object SqlRename {
  private val PLAIN = Regex("[A-Za-z_][A-Za-z0-9_]*")

  /**
   * How [newName] is written where [old] was: a quoted name stays quoted with the same quotes and gets [newName]'s exact
   * case; an unquoted one keeps its all-lower or all-upper case, and is quoted when [newName] needs it.
   */
  fun nameText(old: String, newName: String, dbms: Dbms?): String {
    val open = old.firstOrNull()
    if (old.length >= 2 && open in listOf('"', '`', '[')) {
      return when (open) {
        '[' -> "[$newName]"
        else -> "$open${newName.replace("$open", "$open$open")}$open"
      }
    }
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
    val table = DbNames.table(dbms, target.table)
    val column = target.column
    if (column != null) {
      val old = DbNames.quote(dbms, column.name)
      val new = DbNames.quote(dbms, newName)
      return if (dbms == Dbms.H2) "ALTER TABLE $table ALTER COLUMN $old RENAME TO $new"
      else "ALTER TABLE $table RENAME COLUMN $old TO $new"
    }
    val new = DbNames.quote(dbms, newName)
    return when {
      dbms.usesCatalogs -> "RENAME TABLE $table TO ${DbNames.qualified(dbms, target.table.catalog, newName)}"
      dbms == Dbms.POSTGRESQL && target.table.isMaterializedView -> "ALTER MATERIALIZED VIEW $table RENAME TO $new"
      dbms == Dbms.POSTGRESQL && target.table.isView -> "ALTER VIEW $table RENAME TO $new"
      else -> "ALTER TABLE $table RENAME TO $new"
    }
  }

  /** Appends [sql] to the data source's console (its first one, made when there is none) and opens it; nothing runs. */
  fun appendToConsole(project: Project, dataSource: DbDataSource, sql: String) {
    val file = DbConsoles.consoleFile(dataSource) ?: return
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return
    val prefix = if (document.textLength == 0 || document.text.endsWith("\n")) "" else "\n"
    document.insertString(document.textLength, "$prefix$sql;\n")
    if (!ApplicationManager.getApplication().isUnitTestMode) {
      ApplicationManager.getApplication().invokeLater({ FileEditorManager.getInstance(project).openFile(file, true) }, project.disposed)
    }
  }
}

/**
 * Rename of a database table or column: the names in SQL (.sql files, consoles, SQL strings) change, the database does
 * not. The dialog can also write the ALTER statement into the data source's console, and rename JPA mappings.
 */
class SqlDbRenameProcessor : RenamePsiElementProcessor() {
  override fun canProcessElement(element: PsiElement): Boolean = element is SqlDbTarget

  override fun createRenameDialog(project: Project, element: PsiElement, nameSuggestionContext: PsiElement?, editor: Editor?): RenameDialog =
    SqlDbRenameDialog(project, element, nameSuggestionContext, editor)

  override fun findReferences(element: PsiElement, searchScope: SearchScope, searchInCommentsAndStrings: Boolean): Collection<PsiReference> {
    val all = super.findReferences(element, searchScope, searchInCommentsAndStrings)
    return if (SqlDbRenameOptions.updateMappings) all else all.filterNot { it is SqlDbMappingReference }
  }

  override fun renameElement(element: PsiElement, newName: String, usages: Array<out UsageInfo>, listener: RefactoringElementListener?) {
    // the same name can be found twice (an injected reference and its host); rename each place once
    val done = HashSet<Pair<PsiElement, TextRange>>()
    for (usage in usages) {
      val ref = usage.reference ?: continue
      if (!ref.element.isValid || !done.add(ref.element to ref.rangeInElement)) continue
      ref.handleElementRename(newName)
    }
    val target = element as? SqlDbTarget ?: return
    if (SqlDbRenameOptions.generateAlter) SqlRename.appendToConsole(target.project, target.dataSource, SqlRename.alterStatement(target, newName))
    listener?.elementRenamed(element)
  }

  override fun isInplaceRenameSupported(): Boolean = false
}

class SqlDbRenameDialog(project: Project, element: PsiElement, context: PsiElement?, editor: Editor?) : RenameDialog(project, element, context, editor) {
  private var alter: JCheckBox? = null
  private var mappings: JCheckBox? = null

  init {
    SqlDbRenameOptions.updateMappings = false
    SqlDbRenameOptions.generateAlter = false
  }

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
    SqlDbRenameOptions.generateAlter = alter?.isSelected == true
    SqlDbRenameOptions.updateMappings = mappings?.isSelected == true
    super.doAction()
  }
}

