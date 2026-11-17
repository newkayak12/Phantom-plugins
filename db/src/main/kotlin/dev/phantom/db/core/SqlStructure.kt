// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import javax.swing.Icon

/** Structure (Cmd/Ctrl+F12) of a SQL file or console: its statements in order, each by its first words. */
class SqlStructureViewFactory : PsiStructureViewFactory {
  override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
    val file = psiFile as? SqlFile ?: return null
    return object : TreeBasedStructureViewBuilder() {
      override fun createStructureViewModel(editor: Editor?): StructureViewModel =
        object : StructureViewModelBase(file, editor, SqlFileElement(file)), StructureViewModel.ElementInfoProvider {
          override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false
          override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = element is SqlStatementElement
        }.withSuitableClasses(SqlStatement::class.java)

      override fun isRootNodeShown(): Boolean = false
    }
  }
}

private class SqlFileElement(file: SqlFile) : PsiTreeElementBase<SqlFile>(file) {
  override fun getChildrenBase(): Collection<StructureViewTreeElement> = element?.statements.orEmpty().map(::SqlStatementElement)
  override fun getPresentableText(): String? = element?.name
}

private class SqlStatementElement(statement: SqlStatement) : PsiTreeElementBase<SqlStatement>(statement) {
  override fun getChildrenBase(): Collection<StructureViewTreeElement> = emptyList()
  override fun getPresentableText(): String = element?.text?.let(SqlStructure::title).orEmpty()
  override fun getIcon(open: Boolean): Icon = SqlStructure.icon(element?.text.orEmpty())
}

internal object SqlStructure {
  private const val MAX = 80

  /** The statement on one line without its comments, cut at [MAX] characters. */
  fun title(sql: String): String {
    val text = sql.lineSequence().map { it.substringBefore("--").trim() }.filter { it.isNotEmpty() }.joinToString(" ")
      .replace(Regex("/\\*.*?\\*/"), " ").replace(Regex("\\s+"), " ").trim().removeSuffix(";").trim()
    return if (text.length > MAX) text.take(MAX - 1) + "…" else text
  }

  fun icon(sql: String): Icon = when (title(sql).substringBefore(' ').uppercase()) {
    "SELECT", "WITH", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "VALUES", "TABLE" -> AllIcons.Nodes.DataTables
    "INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE", "UPSERT", "TRUNCATE" -> AllIcons.Actions.Edit
    "CREATE", "ALTER", "DROP", "RENAME", "COMMENT" -> AllIcons.Nodes.DataSchema
    else -> AllIcons.Nodes.Property
  }
}
