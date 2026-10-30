// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.rainbow.core

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.lang.BracePair
import com.intellij.lang.LanguageBraceMatching
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace

/**
 * Colors each bracket by how deeply it is nested in brackets of its kind, and the indentation of each line by level.
 * Brackets come from the language's PairedBraceMatcher, so every language with one is covered; a bracket is colored only
 * when its partner has the same parent, which leaves `<` and `>` as operators alone.
 */
class RainbowHighlightVisitor : HighlightVisitor {
  private var holder: HighlightInfoHolder? = null
  private var file: PsiFile? = null

  override fun suitableForFile(psiFile: PsiFile): Boolean {
    val settings = RainbowSettings.getInstance()
    return settings.brackets || settings.indent
  }

  override fun analyze(psiFile: PsiFile, updateWholeFile: Boolean, holder: HighlightInfoHolder, action: Runnable): Boolean {
    this.holder = holder
    this.file = psiFile
    try {
      action.run()
    }
    finally {
      this.holder = null
      this.file = null
    }
    return true
  }

  override fun visit(element: PsiElement) {
    if (element.firstChild != null) return
    val settings = RainbowSettings.getInstance()
    if (element is PsiWhiteSpace) {
      if (settings.indent) indent(element)
    }
    else if (settings.brackets) {
      bracket(element)
    }
  }

  override fun clone(): HighlightVisitor = RainbowHighlightVisitor()

  private fun bracket(element: PsiElement) {
    val type = element.node?.elementType ?: return
    val matcher = LanguageBraceMatching.INSTANCE.forLanguage(element.language) ?: return
    val pair = matcher.pairs.firstOrNull { it.leftBraceType == type || it.rightBraceType == type } ?: return
    val parent = element.parent ?: return
    if (!hasPair(parent, pair)) return
    var depth = 0
    var ancestor = parent.parent
    while (ancestor != null && ancestor !is PsiFile) {
      if (hasPair(ancestor, pair)) depth++
      ancestor = ancestor.parent
    }
    add(element.textRange.startOffset, element.textRange.endOffset, RainbowColors.BRACKETS[depth % RainbowColors.BRACKETS.size])
  }

  private fun hasPair(element: PsiElement, pair: BracePair): Boolean {
    val node = element.node ?: return false
    return node.findChildByType(pair.leftBraceType) != null && node.findChildByType(pair.rightBraceType) != null
  }

  /** The indentation after the whitespace's last line break, when code follows it on that line. */
  private fun indent(element: PsiWhiteSpace) {
    val text = element.text
    val lineBreak = text.lastIndexOf('\n')
    if (lineBreak < 0 || lineBreak == text.length - 1) return
    val base = element.textRange.startOffset
    val options = CodeStyle.getIndentOptions(file ?: element.containingFile)
    for (segment in IndentLevels.segments(text, lineBreak + 1, text.length, options.INDENT_SIZE, options.TAB_SIZE)) {
      add(base + segment.start, base + segment.end, RainbowColors.INDENTS[segment.level % RainbowColors.INDENTS.size])
    }
  }

  private fun add(start: Int, end: Int, key: TextAttributesKey) {
    val type = HighlightInfoType.HighlightInfoTypeImpl(HighlightInfoType.SYMBOL_TYPE_SEVERITY, key)
    holder?.add(HighlightInfo.newHighlightInfo(type).range(start, end).create())
  }
}
