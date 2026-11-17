// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.rainbow.core

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/** The colors are in colorSchemes/RainbowDefault.xml and RainbowDarcula.xml, and can be changed under Editor | Color Scheme | Rainbow. */
object RainbowColors {
  val BRACKETS: List<TextAttributesKey> = (1..5).map { TextAttributesKey.createTextAttributesKey("PHANTOM_RAINBOW_BRACKET_$it") }
  val INDENTS: List<TextAttributesKey> = (1..4).map { TextAttributesKey.createTextAttributesKey("PHANTOM_RAINBOW_INDENT_$it") }
}

class RainbowColorSettingsPage : ColorSettingsPage {
  override fun getDisplayName() = PhantomRainbowBundle.message("colors.page")
  override fun getIcon(): Icon? = null
  override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

  override fun getAttributeDescriptors(): Array<AttributesDescriptor> =
    (RainbowColors.BRACKETS.mapIndexed { i, key -> AttributesDescriptor(PhantomRainbowBundle.message("colors.bracket", i + 1), key) } +
     RainbowColors.INDENTS.mapIndexed { i, key -> AttributesDescriptor(PhantomRainbowBundle.message("colors.indent", i + 1), key) })
      .toTypedArray()

  override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

  override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> =
    RainbowColors.BRACKETS.withIndex().associate { "b${it.index + 1}" to it.value } +
    RainbowColors.INDENTS.withIndex().associate { "i${it.index + 1}" to it.value }

  override fun getDemoText(): String = """
    fun main<b1>(</b1>args: Array<b2>(</b2>String<b2>)</b2><b1>)</b1> <b1>{</b1>
    <i1>    </i1>if <b2>(</b2>check<b3>(</b3>list<b4>[</b4>index<b5>(</b5>0<b5>)</b5><b4>]</b4><b3>)</b3><b2>)</b2> <b2>{</b2>
    <i1>    </i1><i2>    </i2>for <b3>(</b3>x in xs<b3>)</b3> <b3>{</b3>
    <i1>    </i1><i2>    </i2><i3>    </i3>run <b4>{</b4> print<b5>(</b5>x<b5>)</b5> <b4>}</b4>
    <i1>    </i1><i2>    </i2><i3>    </i3>if <b4>(</b4>x<b4>)</b4> <b4>{</b4>
    <i1>    </i1><i2>    </i2><i3>    </i3><i4>    </i4>stop<b5>(</b5><b5>)</b5>
    <i1>    </i1><i2>    </i2><i3>    </i3><b4>}</b4>
    <i1>    </i1><i2>    </i2><b3>}</b3>
    <i1>    </i1><b2>}</b2>
    <b1>}</b1>
  """.trimIndent()
}
