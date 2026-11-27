// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.asyncapi

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.icons.AllIcons
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/** The string value of a `$ref` key, in YAML or JSON. */
internal fun isRefValue(element: PsiElement): Boolean = when (element) {
  is YAMLScalar -> (element.parent as? YAMLKeyValue)?.let { it.keyText == "\$ref" && it.value == element } == true
  is JsonStringLiteral -> (element.parent as? JsonProperty)?.let { it.name == "\$ref" && it.value == element } == true
  else -> false
}

/**
 * A `$ref` of an AsyncAPI document: `#/components/messages/Order`, `messages.yaml#/Order` or `schemas/order.json`, the
 * file part relative to the document. Remote references are soft: neither resolved nor reported, and never fetched.
 */
class AsyncApiReference(element: PsiElement, range: TextRange, private val value: String) : PsiReferenceBase<PsiElement>(element, range) {
  private val remote = value.startsWith("http://") || value.startsWith("https://")

  override fun isSoft(): Boolean = remote

  override fun resolve(): PsiElement? {
    if (remote) return null
    val filePart = value.substringBefore('#')
    val here = element.containingFile.originalFile
    val target = if (filePart.isEmpty()) here else {
      val vFile = here.virtualFile?.parent?.findFileByRelativePath(filePart)?.takeIf { !it.isDirectory } ?: return null
      PsiManager.getInstance(element.project).findFile(vFile) ?: return null
    }
    return AsyncApiDocs.resolvePointer(target, value.substringAfter('#', ""))
  }

  /** Renaming the target changes the last pointer segment (or the file name), not the whole value. */
  override fun handleElementRename(newElementName: String): PsiElement {
    val hash = value.indexOf('#')
    val pointer = if (hash >= 0) value.substring(hash + 1) else ""
    val renamed = if (pointer.isNotEmpty()) {
      value.substring(0, hash + 1) + pointer.substringBeforeLast('/', "") + "/" + newElementName.replace("~", "~0").replace("/", "~1")
    }
    else {
      val filePart = value.substringBefore('#')
      val dir = filePart.substringBeforeLast('/', "")
      (if (dir.isEmpty() && !filePart.startsWith("/")) newElementName else "$dir/$newElementName") + value.substring(filePart.length)
    }
    return ElementManipulators.handleContentChange(element, rangeInElement, renamed)
  }

  override fun getVariants(): Array<Any> = pointers(element.containingFile.originalFile)
    .map { LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.Field) }.toTypedArray()

  private fun pointers(file: PsiFile): List<String> =
    TOP_LEVEL.flatMap { kind -> AsyncApiDocs.names(file, kind).map { "#/$kind/$it" } } +
      AsyncApiDocs.names(file, "components").flatMap { kind -> AsyncApiDocs.names(file, "components", kind).map { "#/components/$kind/$it" } }

  private companion object {
    val TOP_LEVEL = listOf("servers", "channels", "operations")
  }
}

class AsyncApiReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    val provider = object : PsiReferenceProvider() {
      override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        if (!isRefValue(element) || !AsyncApiDocs.isSpec(element.containingFile.originalFile)) return PsiReference.EMPTY_ARRAY
        val range = ElementManipulators.getValueTextRange(element)
        return arrayOf(AsyncApiReference(element, range, range.substring(element.text)))
      }
    }
    registrar.registerReferenceProvider(PlatformPatterns.psiElement(YAMLScalar::class.java), provider)
    registrar.registerReferenceProvider(PlatformPatterns.psiElement(JsonStringLiteral::class.java), provider)
  }
}

/** `$ref` values of an AsyncAPI document that resolve to nothing. */
class AsyncApiRefInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    if (!AsyncApiDocs.isSpec(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    return object : PsiElementVisitor() {
      override fun visitElement(element: PsiElement) {
        if (!isRefValue(element)) return
        for (reference in element.references.filterIsInstance<AsyncApiReference>()) {
          if (reference.isSoft || reference.resolve() != null) continue
          holder.registerProblem(element, reference.rangeInElement, AsyncApiBundle.message("ref.unresolved", reference.canonicalText))
        }
      }
    }
  }
}
