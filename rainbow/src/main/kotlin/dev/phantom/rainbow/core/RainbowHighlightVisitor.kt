// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.rainbow.core

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.lang.BracePair
import com.intellij.lang.LanguageBraceMatching
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.CommonCodeStyleSettings

/**
 * Colors each bracket by how deeply it is nested in brackets of its kind, and the indentation of each line by level.
 * Brackets come from the language's PairedBraceMatcher, so every language with one is covered; a bracket is colored only
 * when its partner has the same parent, which leaves `<` and `>` as operators alone. Brackets in comments (`{@link}` in
 * Javadoc) are left alone.
 */
class RainbowHighlightVisitor : HighlightVisitor {
  private var holder: HighlightInfoHolder? = null
  private var file: PsiFile? = null
  private var brackets = false
  private var indent = false
  private var indentOptions: CommonCodeStyleSettings.IndentOptions? = null
  /** Per pass and brace pair: how many of an element and its ancestors hold that pair, -1 inside a comment. */
  private val depths = HashMap<BracePair, HashMap<PsiElement, Int>>()

  override fun suitableForFile(psiFile: PsiFile): Boolean {
    val settings = RainbowSettings.getInstance()
    return settings.brackets || settings.indent
  }

  override fun analyze(psiFile: PsiFile, updateWholeFile: Boolean, holder: HighlightInfoHolder, action: Runnable): Boolean {
    val settings = RainbowSettings.getInstance()
    this.holder = holder
    this.file = psiFile
    brackets = settings.brackets
    indent = settings.indent
    try {
      action.run()
    }
    finally {
      this.holder = null
      this.file = null
      indentOptions = null
      depths.clear()
    }
    return true
  }

  override fun visit(element: PsiElement) {
    if (element.firstChild != null) return
    if (element is PsiWhiteSpace) {
      if (indent) indent(element)
    }
    else if (brackets) {
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
    val depth = depth(parent, pair) - 1
    if (depth < 0) return
    add(element.textRange.startOffset, element.textRange.endOffset, KEYS_BRACKETS[depth % KEYS_BRACKETS.size])
  }

  /**
   * How many of [element] and its ancestors below the file hold [pair], or -1 when one of them is a comment. Remembered
   * for every element on the way, so each element is looked at once per pass rather than once per bracket below it.
   */
  private fun depth(element: PsiElement, pair: BracePair): Int {
    val known = depths.getOrPut(pair) { HashMap() }
    val path = ArrayList<PsiElement>()
    var depth = 0
    var current: PsiElement? = element
    while (current != null && current !is PsiFile) {
      val cached = known[current]
      if (cached != null) {
        depth = cached
        break
      }
      path += current
      current = current.parent
    }
    for (i in path.indices.reversed()) {
      val e = path[i]
      depth = if (depth < 0 || e is PsiComment) -1 else if (hasPair(e, pair)) depth + 1 else depth
      known[e] = depth
    }
    return depth
  }

  private fun hasPair(element: PsiElement, pair: BracePair): Boolean {
    val node = element.node ?: return false
    return node.findChildByType(pair.leftBraceType) != null && node.findChildByType(pair.rightBraceType) != null
  }

  /** The indentation after the whitespace's last line break, or of the whole whitespace at a line start, when code follows it. */
  private fun indent(element: PsiWhiteSpace) {
    val file = file ?: element.containingFile
    val contents = file.viewProvider.contents
    val range = element.textRange
    if (range.endOffset >= contents.length) return
    val text = element.text
    val lineBreak = text.lastIndexOf('\n')
    val start = when {
      lineBreak >= 0 -> lineBreak + 1
      range.startOffset == 0 || contents[range.startOffset - 1] == '\n' -> 0
      else -> return
    }
    if (start == text.length) return
    val options = indentOptions ?: CodeStyle.getIndentOptions(file).also { indentOptions = it }
    val base = range.startOffset
    for (segment in IndentLevels.segments(text, start, text.length, options.INDENT_SIZE, options.TAB_SIZE)) {
      add(base + segment.start, base + segment.end, KEYS_INDENTS[segment.level % KEYS_INDENTS.size])
    }
  }

  private fun add(start: Int, end: Int, type: HighlightInfoType) {
    holder?.add(HighlightInfo.newHighlightInfo(type).range(start, end).create())
  }

  private companion object {
    val KEYS_BRACKETS = RainbowColors.BRACKETS.map { HighlightInfoType.HighlightInfoTypeImpl(HighlightInfoType.SYMBOL_TYPE_SEVERITY, it) }
    val KEYS_INDENTS = RainbowColors.INDENTS.map { HighlightInfoType.HighlightInfoTypeImpl(HighlightInfoType.SYMBOL_TYPE_SEVERITY, it) }
  }
}
