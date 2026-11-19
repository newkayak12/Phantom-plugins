// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.grazie.ide.ui.configurable

import com.intellij.grazie.GrazieBundle
import com.intellij.grazie.GrazieConfig
import com.intellij.grazie.detection.toLanguage
import com.intellij.grazie.ide.ui.components.dsl.msg
import com.intellij.grazie.ide.ui.grammar.tabs.rules.component.GrazieDescriptionComponent
import com.intellij.grazie.ide.ui.grammar.tabs.rules.component.GrazieTreeComponent
import com.intellij.grazie.jlanguage.Lang
import com.intellij.grazie.text.Rule
import com.intellij.grazie.utils.TextStyleDomain
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.whenItemSelectedFromUi
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBUI.Borders
import com.intellij.util.ui.update.UiNotifyConnector
import javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
import javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * The Rules tab: the LanguageTool rules of each enabled language, switched on and off per domain (commit messages,
 * code comments, documentation, AI chat, other text).
 */
class RulesConfigurable : BoundConfigurable(GrazieBundle.message("grazie.settings.grammar.tabs.rules"), null), Disposable, Configurable.NoScroll {
  private val trees = HashMap<Pair<TextStyleDomain, Lang>, Pair<GrazieTreeComponent, GrazieDescriptionComponent>>()
  private val langComboModel = CollectionComboBoxModel(ArrayList<Lang>())
  private lateinit var langCombo: ComboBox<Lang>
  private lateinit var domainCombo: ComboBox<TextStyleDomain>

  private val filterComponent: SearchTextField = SearchTextField(false).also {
    it.textEditor.emptyText.text = GrazieBundle.message("grazie.settings.style.search.placeholder")
    it.textEditor.document.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) {
        currentTree()?.filter(filterComponent.text)
      }
    })
    it.border = JBEmptyBorder(5)
  }

  private val splitter = JBSplitter(false, 0.45f).apply {
    minimumSize = JBUI.size(150, 200)
    setHonorComponentsMinimumSize(true)
  }

  private val domain: TextStyleDomain get() = domainCombo.selectedItem as? TextStyleDomain ?: TextStyleDomain.Other
  private val lang: Lang? get() = langComboModel.selected

  val component: DialogPanel by lazy {
    loadLanguages()
    panel {
      row {
        comment(GrazieBundle.message("grazie.settings.rules.hint"))
      }
      row(GrazieBundle.message("grazie.settings.writing.style.domain")) {
        domainCombo = comboBox(
          CollectionComboBoxModel(TextStyleDomain.entries),
          textListCellRenderer("") { GrazieBundle.messageOrNull("grazie.settings.domain.profile.display.$it") ?: it.name }
        ).widthGroup("TopCombo").whenItemSelectedFromUi { showTree() }.component
        domainCombo.selectedItem = TextStyleDomain.Other
      }
      row(GrazieBundle.message("grazie.settings.language.chooser.label")) {
        langCombo = comboBox(langComboModel, textListCellRenderer("") { it.nativeName })
          .widthGroup("TopCombo")
          .whenItemSelectedFromUi { showTree() }
          .component
        langCombo.selectedItem = GrazieConfig.get().availableLanguages.find { it.isEnglish() }
        trackNewLanguageAddition()
      }
      row {
        cell(filterComponent).resizableColumn().align(Align.FILL)
      }
      row {
        cell(splitter).resizableColumn().align(Align.FILL)
        resizableRow()
      }
    }.also {
      it.border = Borders.empty()
      showTree()
    }
  }

  private fun treeFor(domain: TextStyleDomain, lang: Lang): Pair<GrazieTreeComponent, GrazieDescriptionComponent> =
    trees.getOrPut(domain to lang) {
      val description = GrazieDescriptionComponent()
      val tree = GrazieTreeComponent(description.listener, lang.toLanguage(), domain, filterComponent)
      tree.reset(GrazieConfig.get())
      tree to description
    }

  private fun currentTree(): GrazieTreeComponent? = lang?.let { trees[domain to it]?.first }

  private fun showTree() {
    val lang = lang ?: return
    val (tree, description) = treeFor(domain, lang)
    description.listener(lang.toLanguage())
    splitter.firstComponent = ScrollPaneFactory.createScrollPane(tree, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_AS_NEEDED)
    splitter.secondComponent = description.component
    tree.filter(filterComponent.text)
    splitter.revalidate()
    splitter.repaint()
  }

  override fun createPanel(): DialogPanel = component

  override fun isModified(): Boolean {
    val state = GrazieConfig.get()
    return super<BoundConfigurable>.isModified || trees.values.any { it.first.isModified(state) }
  }

  override fun apply() {
    super.apply()
    val modified = trees.values.map { it.first }.filter { it.isModified(GrazieConfig.get()) }
    if (modified.isEmpty()) return
    GrazieConfig.update { state -> modified.fold(state) { acc, tree -> tree.apply(acc) } }
    modified.forEach { it.reset(GrazieConfig.get()) }
  }

  override fun reset() {
    super.reset()
    val state = GrazieConfig.get()
    trees.values.forEach { it.first.reset(state) }
  }

  override fun dispose() {
    disposeUIResources()
  }

  override fun getDisplayName(): @NlsContexts.ConfigurableName String = msg("grazie.settings.page.name")

  private fun trackNewLanguageAddition() {
    GrazieConfig.subscribe(this) {
      SwingUtilities.invokeLater {
        if (loadLanguages() == null) return@invokeLater
        trees.clear()
        if (langComboModel.selected !in langComboModel.items) {
          langCombo.selectedItem = GrazieConfig.get().availableLanguages.firstOrNull { it.isEnglish() }
        }
        showTree()
      }
    }
  }

  private fun loadLanguages(): Set<Lang>? {
    val langs = GrazieConfig.get().availableLanguages
    if (langComboModel.items.toSet() == langs) return null
    val selected = langComboModel.selected
    langComboModel.removeAll()
    langComboModel.add(langs.toList())
    if (selected != null && selected in langs) langComboModel.selectedItem = selected
    return langs
  }

  private fun select(domain: TextStyleDomain, lang: Lang) {
    domainCombo.selectedItem = domain
    langCombo.selectedItem = lang
    showTree()
  }

  companion object {
    /** Opens the Rules settings with [rule] selected in the tree of the given domain */
    @JvmStatic
    fun focusRule(rule: Rule, domain: TextStyleDomain, lang: Lang, project: Project): Boolean {
      val configurable = RulesConfigurable().apply {
        createComponent()
        select(domain, lang)
        val tree = treeFor(domain, lang).first
        tree.focusRule(rule)
        UiNotifyConnector.doWhenFirstShown(tree) {
          SwingUtilities.invokeLater { IdeFocusManager.getInstance(project).requestFocus(tree, true) }
        }
      }
      return ShowSettingsUtil.getInstance().editConfigurable(project, configurable)
    }

    @JvmStatic
    fun open(project: Project?): Boolean = ShowSettingsUtil.getInstance().editConfigurable(project, RulesConfigurable())
  }
}
