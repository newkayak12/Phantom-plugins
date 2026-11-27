// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.asyncapi

import com.intellij.json.JsonLanguage
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/** A channel of a spec and the Kafka topic it names; [anchor] is the first leaf of the channel's key. */
class Channel(val topic: String, val key: PsiElement) {
  val anchor: PsiElement get() = PsiTreeUtil.getDeepestFirst(key)
}

/** AsyncAPI documents in YAML or JSON, read through one view of mappings, sequences and strings. */
object AsyncApiDocs {
  private val EXTENSIONS = listOf("yaml", "yml", "json")

  fun isSpec(file: PsiFile?): Boolean {
    if (file !is YAMLFile && file !is JsonFile) return false
    return CachedValuesManager.getCachedValue(file) {
      CachedValueProvider.Result.create(entries(root(file)).any { it.first == "asyncapi" }, file)
    }
  }

  /** The project's specs. Only files whose first 4 KB declare an AsyncAPI version are parsed. */
  fun specFiles(project: Project): List<PsiFile> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val scope = GlobalSearchScope.projectScope(project)
    val psiManager = PsiManager.getInstance(project)
    val files = EXTENSIONS.flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }
      .filter { AsyncApiVersion.of(it) != null }
      .mapNotNull { psiManager.findFile(it) }
      .filter { isSpec(it) }
    // YAML and JSON edits and new or deleted files only: a Java edit does not rescan the specs.
    val documents = PsiModificationTracker.getInstance(project).forLanguages { it.isKindOf(YAMLLanguage.INSTANCE) || it.isKindOf(JsonLanguage.INSTANCE) }
    CachedValueProvider.Result.create(files, documents, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
  }

  /**
   * The channels of [file] that name a topic: the Kafka binding's `topic`, else the channel key in AsyncAPI 2 and the
   * `address` in AsyncAPI 3, where a channel without an address has no known topic.
   */
  fun channels(file: PsiFile): List<Channel> = CachedValuesManager.getCachedValue(file) {
    val root = root(file)
    val v3 = text(child(root, "asyncapi"))?.startsWith("3") == true
    val result = entries(child(root, "channels")).mapNotNull { (name, entry) ->
      val channel = valueOf(entry)
      val topic = text(child(child(child(channel, "bindings"), "kafka"), "topic"))
        ?: if (v3) text(child(channel, "address")) else name
      val key = (entry as? YAMLKeyValue)?.key ?: (entry as? JsonProperty)?.nameElement
      if (topic.isNullOrBlank() || key == null) null else Channel(topic, key)
    }
    CachedValueProvider.Result.create(result, file)
  }

  private fun root(file: PsiFile): PsiElement? = when (file) {
    is YAMLFile -> file.documents.firstOrNull()?.topLevelValue
    is JsonFile -> file.topLevelValue
    else -> null
  }

  private fun entries(value: PsiElement?): List<Pair<String, PsiElement>> = when (value) {
    is YAMLMapping -> value.keyValues.map { it.keyText to it }
    is JsonObject -> value.propertyList.map { it.name to it }
    else -> emptyList()
  }

  private fun valueOf(entry: PsiElement): PsiElement? = (entry as? YAMLKeyValue)?.value ?: (entry as? JsonProperty)?.value

  private fun child(value: PsiElement?, key: String): PsiElement? = entries(value).firstOrNull { it.first == key }?.second?.let(::valueOf)

  /** A string value; YAML's `null` and `~` are no string. */
  private fun text(value: PsiElement?): String? = when (value) {
    is YAMLScalar -> value.textValue.takeUnless { value.text == "null" || value.text == "~" }
    is JsonStringLiteral -> value.value
    else -> null
  }
}
