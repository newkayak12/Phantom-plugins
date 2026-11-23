// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.grazie.ide.inspection.grammar.quickfix

import com.intellij.codeInsight.intention.CustomizableIntentionAction
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.IntentionAndQuickFixAction
import com.intellij.grazie.GrazieConfig
import com.intellij.grazie.ide.ui.components.dsl.msg
import com.intellij.grazie.ide.ui.configurable.RulesConfigurable
import com.intellij.grazie.text.Rule
import com.intellij.grazie.utils.TextStyleDomain
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.psi.PsiFile
import javax.swing.Icon

class GrazieRuleSettingsAction(private val rule: Rule, private val domain: TextStyleDomain)
  : IntentionAndQuickFixAction(), Iconable, CustomizableIntentionAction, Comparable<IntentionAction>
{

  override fun isShowSubmenu(): Boolean = false

  override fun getIcon(flags: Int): Icon = AllIcons.Actions.Edit

  override fun getName(): String = msg(when (domain) {
    TextStyleDomain.Other -> "grazie.grammar.quickfix.open.rule.text"
    TextStyleDomain.Commit -> "grazie.grammar.quickfix.open.rule.text.commit"
    TextStyleDomain.CodeComment -> "grazie.grammar.quickfix.open.rule.text.comments"
    TextStyleDomain.CodeDocumentation -> "grazie.grammar.quickfix.open.rule.text.documentation"
    TextStyleDomain.AIPrompt -> "grazie.grammar.quickfix.open.rule.text.ai"
  }, rule.presentableName)

  override fun getFamilyName(): String = msg("grazie.grammar.quickfix.open.rule.family")

  override fun startInWriteAction(): Boolean = false

  override fun compareTo(other: IntentionAction): Int {
    return if (other is GrazieAddExceptionQuickFix) 1 else 0
  }

  override fun applyFix(project: Project, psiFile: PsiFile?, editor: Editor?) {
    val state = GrazieConfig.get()
    val availableLang = state.availableLanguages.first { it.iso == rule.language.iso }
    RulesConfigurable.focusRule(rule, domain, availableLang, project)
  }

}