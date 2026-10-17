// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement

/** Stands in for a configuration key so the platform documentation popup can show it. */
class ConfigKeyDocElement(val lookup: ConfigLookupObject, private val context: PsiElement) : FakePsiElement() {
  override fun getParent(): PsiElement = context
  override fun getName(): String = lookup.name
  override fun getPresentableText(): String = lookup.name
  override fun isValid(): Boolean = context.isValid
}

/** Quick documentation (Ctrl+Q and the completion popup) for configuration keys. */
abstract class BootConfigDocumentationProvider(private val syntax: ConfigSyntax) : AbstractDocumentationProvider() {
  override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? {
    if (contextElement == null || !BootConfigFiles.isConfigFile(file.name)) return null
    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return null
    if (!SpringBootGate.isBootModule(module)) return null
    val name = syntax.keyAt(editor.document.charsSequence, targetOffset) ?: return null
    val keys = ConfigMetadataService.getInstance(file.project).keysFor(module)
    val property = keys.property(name)
    val group = keys.groupDescription(name)
    if (property == null && group == null && !keys.isKnownPath(name)) return null
    return ConfigKeyDocElement(ConfigLookupObject(ConfigNames.normalize(name), property, group), contextElement)
  }

  override fun getDocumentationElementForLookupItem(psiManager: PsiManager, `object`: Any?, element: PsiElement?): PsiElement? {
    val lookup = `object` as? ConfigLookupObject ?: return null
    return ConfigKeyDocElement(lookup, element ?: return null)
  }

  override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
    val lookup = (element as? ConfigKeyDocElement)?.lookup ?: return null
    return render(lookup)
  }

  override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
    val lookup = (element as? ConfigKeyDocElement)?.lookup ?: return null
    return lookup.name + (lookup.property?.shortType?.let { ": $it" } ?: "")
  }

  companion object {
    fun render(lookup: ConfigLookupObject): String {
      val property = lookup.property
      val sb = StringBuilder()
      sb.append(DocumentationMarkup.DEFINITION_START).append(StringUtil.escapeXmlEntities(lookup.name))
      property?.shortType?.let { sb.append(": ").append(StringUtil.escapeXmlEntities(it)) }
      sb.append(DocumentationMarkup.DEFINITION_END)
      // Metadata descriptions are Javadoc-derived HTML already.
      val description = property?.description ?: lookup.groupDescription
      if (description != null) sb.append(DocumentationMarkup.CONTENT_START).append(description).append(DocumentationMarkup.CONTENT_END)
      sb.append(DocumentationMarkup.SECTIONS_START)
      property?.deprecation?.let { d ->
        val text = listOfNotNull(d.reason, d.replacement?.let { PhantomSpringBootBundle.message("doc.replacement", it) }).joinToString(" ")
        section(sb, PhantomSpringBootBundle.message("doc.deprecated"), StringUtil.escapeXmlEntities(text.ifEmpty { d.level ?: "" }))
      }
      property?.type?.let { section(sb, PhantomSpringBootBundle.message("doc.type"), "<code>${StringUtil.escapeXmlEntities(it)}</code>") }
      property?.defaultValue?.let { section(sb, PhantomSpringBootBundle.message("doc.default"), "<code>${StringUtil.escapeXmlEntities(it)}</code>") }
      property?.sourceType?.let { section(sb, PhantomSpringBootBundle.message("doc.source"), "<code>${StringUtil.escapeXmlEntities(it)}</code>") }
      sb.append(DocumentationMarkup.SECTIONS_END)
      return sb.toString()
    }

    private fun section(sb: StringBuilder, title: String, value: String) {
      sb.append(DocumentationMarkup.SECTION_HEADER_START).append(StringUtil.escapeXmlEntities(title)).append(DocumentationMarkup.SECTION_SEPARATOR)
        .append(value).append(DocumentationMarkup.SECTION_END)
    }
  }
}
