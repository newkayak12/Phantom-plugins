// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.codeInspection.*
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

/**
 * Deprecated keys (from metadata) and unknown keys under the project's own `@ConfigurationProperties` prefixes,
 * where the full set of keys is known. Library groups are not checked for unknown keys: their metadata leaves out
 * map entries and properties bound in code.
 */
class BootConfigKeyInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
    override fun visitFile(file: PsiFile) {
      if (!BootConfigFiles.isConfigFile(file)) return
      val yaml = !file.name.endsWith(".properties")
      val syntax = if (yaml) YamlConfigSyntax else PropertiesConfigSyntax
      val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return
      if (!SpringBootGate.isBootModule(module)) return
      val keys = ConfigMetadataService.getInstance(file.project).keysFor(module)
      if (keys.size == 0) return
      val sourceGroups = SourceConfigProperties.metadata(module).groups.keys
      val text = PsiDocumentManager.getInstance(file.project).getDocument(file)?.charsSequence ?: file.viewProvider.contents
      for (range in keyRanges(text, yaml)) {
        val key = syntax.keyAt(text, range.startOffset) ?: continue
        val property = keys.property(key)
        if (property == null) {
          val name = ConfigNames.normalize(key)
          val parent = name.substringBeforeLast('.', "")
          if (parent in sourceGroups && !keys.isKnownPath(name)) {
            holder.registerProblem(file, PhantomSpringBootBundle.message("inspection.key.unknown", name, parent),
                                   ProblemHighlightType.GENERIC_ERROR_OR_WARNING, range)
          }
          continue
        }
        val deprecation = property.deprecation ?: continue
        val message = listOfNotNull(PhantomSpringBootBundle.message("inspection.key.deprecated", property.name), deprecation.reason,
                                    deprecation.replacement?.let { PhantomSpringBootBundle.message("doc.replacement", it) }).joinToString(" ")
        val type = if (deprecation.level == "error") ProblemHighlightType.LIKE_MARKED_FOR_REMOVAL else ProblemHighlightType.LIKE_DEPRECATED
        val fix = deprecation.replacement?.let { replacementFix(text.subSequence(range.startOffset, range.endOffset).toString(), property.name, it, yaml) }
        holder.registerProblem(file, message, type, range, *listOfNotNull(fix).toTypedArray())
      }
    }
  }

  /** The text ranges of the key on each line (the last segment in YAML, the whole dotted key in properties). */
  private fun keyRanges(text: CharSequence, yaml: Boolean): List<TextRange> {
    val result = ArrayList<TextRange>()
    var start = 0
    while (start < text.length) {
      val end = lineEndOf(text, start)
      var i = start
      while (i < end && (text[i] == ' ' || text[i] == '\t')) i++
      if (i < end && text[i] != '#' && text[i] != '!' && text[i] != '-') {
        val separator = (i until end).firstOrNull { text[it] == ':' || !yaml && text[it] == '=' }
        if (separator != null) {
          var keyEnd = separator
          while (keyEnd > i && text[keyEnd - 1] == ' ') keyEnd--
          if (keyEnd > i) result.add(TextRange(i, keyEnd))
        }
      }
      start = end + 1
    }
    return result
  }

  /** Properties keys are replaced whole; a YAML key only when the replacement differs in the last segment. */
  private fun replacementFix(written: String, name: String, replacement: String, yaml: Boolean): LocalQuickFix? {
    val newText = when {
      !yaml -> replacement
      name.substringBeforeLast('.') == replacement.substringBeforeLast('.') -> replacement.substringAfterLast('.')
      else -> return null
    }
    if (newText == written) return null
    return ReplaceKeyFix(newText)
  }

  private class ReplaceKeyFix(private val newText: String) : LocalQuickFix {
    override fun getFamilyName(): String = PhantomSpringBootBundle.message("inspection.key.replace", newText)

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val file = descriptor.psiElement as? PsiFile ?: return
      val document = file.viewProvider.document ?: return
      val range = descriptor.textRangeInElement ?: return
      document.replaceString(range.startOffset, range.endOffset, newText)
      PsiDocumentManager.getInstance(project).commitDocument(document)
    }
  }
}
