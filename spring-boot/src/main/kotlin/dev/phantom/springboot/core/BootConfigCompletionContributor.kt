// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiEnumConstant

/** What a completion item stands for; also handed to the documentation provider. */
class ConfigLookupObject(val name: String, val property: ConfigProperty?, val groupDescription: String?)

/** Key and value completion for application*.yml and application*.properties, driven by [ConfigKeys]. */
abstract class BootConfigCompletionContributor(private val syntax: ConfigSyntax, private val yaml: Boolean) : CompletionContributor() {
  override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
    if (parameters.completionType != CompletionType.BASIC) return
    val file = parameters.originalFile
    if (!BootConfigFiles.isConfigFile(file)) return
    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return
    if (!SpringBootGate.isBootModule(module)) return

    val context = syntax.contextAt(parameters.editor.document.charsSequence, parameters.offset) ?: return
    val keys = ConfigMetadataService.getInstance(file.project).keysFor(module)
    if (keys.size == 0) return
    when (context) {
      is KeyContext -> if (yaml) addYamlKeys(context, keys, result) else addPropertiesKeys(context, keys, result)
      is ValueContext -> addValues(module, context, keys, result)
    }
  }

  private fun addYamlKeys(context: KeyContext, keys: ConfigKeys, result: CompletionResultSet) {
    val group = context.groupPath
    val matcher = result.withPrefixMatcher(context.segmentPrefix)
    val children = keys.childrenOf(group)
    for (node in children) {
      val element = keyElement(node.segment, node.fullName, node.property, keys, node.isGroup && node.hasChildren || node.property?.isMap == true)
        .withInsertHandler(YamlKeyInsertHandler(nested = node.isGroup, indent = context.lineIndent + context.indentUnit))
      matcher.addElement(PrioritizedLookupElement.withPriority(element, 1.0))
    }
    // Deeper keys as dotted relative names, so `ddl` finds `jpa.hibernate.ddl-auto` while standing in `spring`.
    // Only when no direct child matches, to keep the list short.
    if (context.segmentPrefix.length >= 2 && children.none { matcher.prefixMatcher.prefixMatches(it.segment) }) {
      val prefixLength = if (group.isEmpty()) 0 else ConfigNames.normalize(group).length + 1
      for (property in keys.deepLeavesUnder(group, DEEP_LIMIT)) {
        val relative = property.name.substring(prefixLength)
        val element = keyElement(relative, property.name, property, keys, property.isMap)
          .withLookupString(property.name.substringAfterLast('.'))
          .withInsertHandler(YamlKeyInsertHandler(nested = property.isMap, indent = context.lineIndent + context.indentUnit))
        matcher.addElement(PrioritizedLookupElement.withPriority(element, 0.0))
      }
    }
  }

  private fun addPropertiesKeys(context: KeyContext, keys: ConfigKeys, result: CompletionResultSet) {
    val matcher = result.withPrefixMatcher(context.typed)
    for (property in keys.leavesUnder(context.groupPath, PROPERTIES_LIMIT)) {
      val suffix = if (property.isMap) "." else "="
      matcher.addElement(keyElement(property.name, property.name, property, keys, property.isMap).withInsertHandler(SuffixInsertHandler(suffix)))
    }
  }

  private fun keyElement(lookup: String, fullName: String, property: ConfigProperty?, keys: ConfigKeys, bold: Boolean): LookupElementBuilder {
    var element = LookupElementBuilder.create(ConfigLookupObject(fullName, property, keys.groupDescription(fullName)), lookup)
      .withTypeText(property?.shortType, true)
      .withBoldness(bold)
    property?.defaultValue?.takeIf { it.isNotEmpty() }?.let { element = element.withTailText(" = $it", true) }
    if (property?.deprecation != null) element = element.withStrikeoutness(true)
    return element
  }

  private fun addValues(module: Module, context: ValueContext, keys: ConfigKeys, result: CompletionResultSet) {
    val property = keys.property(context.key)
    val matcher = result.withPrefixMatcher(context.typed)
    val seen = HashSet<String>()
    fun add(value: String, description: String?) {
      if (seen.add(value)) matcher.addElement(LookupElementBuilder.create(value).withTypeText(description?.lineSequence()?.first(), true))
    }
    for (hint in keys.hintValues(context.key)) add(hint.value, hint.description)
    addTypedValues(module, property?.type, ::add)
    // Known values are the whole answer: other contributors (e.g. Java class names in .properties) would bury them
    if (seen.isNotEmpty()) result.stopHere()
  }

  private fun addTypedValues(module: Module, type: String?, add: (String, String?) -> Unit) {
    if (type == null) return
    when (type) {
      "java.lang.Boolean", "boolean" -> {
        add("true", null)
        add("false", null)
      }
      else -> {
        val psiClass = JavaPsiFacade.getInstance(module.project).findClass(type.replace('$', '.'), module.getModuleWithDependenciesAndLibrariesScope(false))
        if (psiClass != null && psiClass.isEnum) {
          // Spring binds enum constants case-insensitively; lower-case kebab is the documented style.
          psiClass.fields.filterIsInstance<PsiEnumConstant>().forEach { add(it.name.lowercase().replace('_', '-'), null) }
        }
      }
    }
  }

  private class YamlKeyInsertHandler(private val nested: Boolean, private val indent: Int) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
      val document = context.document
      val tail = context.tailOffset
      if (tail < document.textLength && document.charsSequence[tail] == ':') return
      val text = if (nested) ":\n" + " ".repeat(indent) else ": "
      document.insertString(tail, text)
      context.editor.caretModel.moveToOffset(tail + text.length)
      context.commitDocument()
      AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
    }
  }

  private class SuffixInsertHandler(private val suffix: String) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
      val document = context.document
      val tail = context.tailOffset
      if (tail < document.textLength && document.charsSequence[tail].let { it == '=' || it == ':' || it == '.' }) return
      document.insertString(tail, suffix)
      context.editor.caretModel.moveToOffset(tail + suffix.length)
      if (suffix == ".") {
        context.commitDocument()
        AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
      }
    }
  }

  private companion object {
    const val DEEP_LIMIT = 400
    const val PROPERTIES_LIMIT = 4000
  }
}
