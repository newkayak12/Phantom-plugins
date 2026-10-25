// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.LocalTimeCounter
import com.intellij.util.ui.JBUI
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.SqlFileType
import dev.phantom.db.core.exec.DbColumnInfo
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * What a WHERE or ORDER BY fragment is written against: SQL completion in the fragment offers [columns] (and
 * keywords) instead of looking for a FROM clause.
 */
class DbFilterContext(val columns: () -> List<DbColumnInfo>) {
  companion object {
    private val KEY = Key.create<DbFilterContext>("phantom.db.filter.context")

    fun of(file: PsiFile): DbFilterContext? = file.getUserData(KEY) ?: file.originalFile.getUserData(KEY)

    internal fun attach(file: PsiFile, context: DbFilterContext) = file.putUserData(KEY, context)
  }
}

/**
 * WHERE and ORDER BY fields over a grid: one-line SQL editors with completion of the columns, Enter applies (unless a
 * completion popup is open), and the drop-down at the right lists earlier filters of the same table ([historyKey]).
 */
class DbFilterBar(private val project: Project, historyKey: String, context: DbFilterContext, private val onApply: () -> Unit) : JPanel(BorderLayout(JBUI.scale(6), 0)) {
  @get:org.jetbrains.annotations.TestOnly
  internal val whereField: Field = Field("phantom.db.where:$historyKey", context, PhantomDbBundle.message("db.data.where.hint"))
  private val orderField = Field("phantom.db.order:$historyKey", context, PhantomDbBundle.message("db.data.order.hint"))

  var where: String
    get() = whereField.text
    set(value) { whereField.text = value }

  var orderBy: String
    get() = orderField.text
    set(value) { orderField.text = value }

  init {
    border = JBUI.Borders.empty(2, 6)
    add(JBLabel("WHERE"), BorderLayout.WEST)
    add(whereField.component, BorderLayout.CENTER)
    add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
      add(JBLabel("ORDER BY"), BorderLayout.WEST)
      add(orderField.component.also { it.preferredSize = JBUI.size(240, it.preferredSize.height) }, BorderLayout.CENTER)
    }, BorderLayout.EAST)
  }

  /** Keeps both fields in their history, then applies them. */
  fun apply() {
    whereField.remember()
    orderField.remember()
    onApply()
  }

  inner class Field(private val historyKey: String, context: DbFilterContext, hint: String) {
    // built on the EDT when a table opens; creating the PSI and its document needs a read action there
    private val file: PsiFile = runReadAction {
      PsiFileFactory.getInstance(project)
        .createFileFromText("filter.sql", SqlFileType.INSTANCE, "", LocalTimeCounter.currentTime(), true)
        .also {
          DbFilterContext.attach(it, context)
          // a fragment is not a statement: no syntax errors or inspections, only colors and completion
          DaemonCodeAnalyzer.getInstance(project).setHighlightingEnabled(it, false)
        }
    }
    private val editor = EditorTextField(runReadAction { PsiDocumentManager.getInstance(project).getDocument(file) }, project, SqlFileType.INSTANCE, false, true).apply {
      setPlaceholder(hint)
      setShowPlaceholderWhenFocused(true)
      toolTipText = hint
      addSettingsProvider { e ->
        object : DumbAwareAction() {
          override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
          override fun update(e2: AnActionEvent) {
            e2.presentation.isEnabled = LookupManager.getActiveLookup(e) == null
          }

          override fun actionPerformed(e2: AnActionEvent) = apply()
        }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)), e.contentComponent)
      }
    }
    val component: JPanel = JPanel(BorderLayout()).apply {
      add(editor, BorderLayout.CENTER)
      add(JButton(AllIcons.General.ArrowDown).apply {
        isFocusable = false
        toolTipText = PhantomDbBundle.message("db.data.history")
        putClientProperty("JButton.buttonType", "borderless")
        preferredSize = JBUI.size(22, 22)
        addActionListener { showHistory(this) }
      }, BorderLayout.EAST)
    }

    var text: String
      get() = editor.text
      set(value) { editor.text = value }

    fun history(): List<String> = PropertiesComponent.getInstance().getList(historyKey).orEmpty()

    fun remember() {
      val t = text.trim()
      if (t.isEmpty()) return
      PropertiesComponent.getInstance().setList(historyKey, (listOf(t) + history().filter { it != t }).take(HISTORY))
    }

    private fun showHistory(owner: java.awt.Component) {
      val items = history()
      if (items.isEmpty()) return
      JBPopupFactory.getInstance().createPopupChooserBuilder(items)
        .setItemChosenCallback { text = it; apply() }
        .createPopup().showUnderneathOf(owner)
    }
  }

  private companion object {
    const val HISTORY = 20
  }
}
