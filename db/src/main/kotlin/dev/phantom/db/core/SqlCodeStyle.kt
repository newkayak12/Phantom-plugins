// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.lang.Language
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.CustomCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil

/** SQL options of Settings → Editor → Code Style → SQL that are not the common ones (indent comes from those). */
class SqlCodeStyleSettings(container: CodeStyleSettings) : CustomCodeStyleSettings("PhantomSqlCodeStyleSettings", container) {
  /** [AS_WRITTEN], [UPPER] or [LOWER]: what Reformat Code does to keywords. */
  @JvmField var KEYWORD_CASE: Int = AS_WRITTEN

  companion object {
    const val AS_WRITTEN = 0
    const val UPPER = 1
    const val LOWER = 2
  }
}

class SqlLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
  override fun getLanguage(): Language = SqlLanguage

  override fun createCustomSettings(settings: CodeStyleSettings): CustomCodeStyleSettings = SqlCodeStyleSettings(settings)

  override fun getIndentOptionsEditor(): IndentOptionsEditor = IndentOptionsEditor()

  override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
    indentOptions.INDENT_SIZE = 4
    indentOptions.CONTINUATION_INDENT_SIZE = 4
  }

  override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
    if (settingsType != SettingsType.WRAPPING_AND_BRACES_SETTINGS) return
    consumer.showCustomOption(SqlCodeStyleSettings::class.java, "KEYWORD_CASE", PhantomDbBundle.message("sql.code.style.keyword.case"),
                              PhantomDbBundle.message("sql.code.style.case.group"),
                              arrayOf(PhantomDbBundle.message("sql.code.style.case.as.written"), "UPPER", "lower"),
                              intArrayOf(SqlCodeStyleSettings.AS_WRITTEN, SqlCodeStyleSettings.UPPER, SqlCodeStyleSettings.LOWER))
  }

  override fun getCodeSample(settingsType: SettingsType): String =
    "with recent as (select guest_id, count(*) as stays from reservation where status = 'DONE' group by guest_id)\n" +
    "select g.id, g.name, r.stays from guest g join recent r on r.guest_id = g.id where r.stays > 2 order by r.stays desc;\n" +
    "update guest set name = 'x' where id = 1;"

  override fun createConfigurable(baseSettings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
    object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, configurableDisplayName) {
      override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
        object : TabbedLanguageCodeStylePanel(SqlLanguage, currentSettings, settings) {
          override fun initTabs(settings: CodeStyleSettings) {
            addIndentOptionsTab(settings)
            addWrappingAndBracesTab(settings)
          }
        }
    }
}

/** After Reformat Code: keywords in the requested case. Names that are keywords elsewhere (`t.type`) are left alone. */
class SqlKeywordCasePostFormatProcessor : PostFormatProcessor {
  override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement {
    apply(source, source.textRange, settings)
    return source
  }

  override fun processText(source: PsiFile, rangeToReformat: TextRange, settings: CodeStyleSettings): TextRange {
    apply(source, rangeToReformat, settings)
    return rangeToReformat
  }

  private fun apply(root: PsiElement, range: TextRange, settings: CodeStyleSettings) {
    if (root.containingFile !is SqlFile) return
    val case = settings.getCustomSettings(SqlCodeStyleSettings::class.java).KEYWORD_CASE
    if (case == SqlCodeStyleSettings.AS_WRITTEN) return
    val keywords = PsiTreeUtil.collectElements(root) { e ->
      e is LeafPsiElement && e.node.elementType == SqlTokens.KEYWORD && range.contains(e.textRange) &&
      e.parent !is SqlColumnRef && e.parent !is SqlQualifier && e.parent !is SqlTableRef && e.parent !is SqlDeclaration
    }
    for (k in keywords) {
      val text = if (case == SqlCodeStyleSettings.UPPER) k.text.uppercase() else k.text.lowercase()
      if (text != k.text) (k as LeafPsiElement).replaceWithText(text)
    }
  }
}
