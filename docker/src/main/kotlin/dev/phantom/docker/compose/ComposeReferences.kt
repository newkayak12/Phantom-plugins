// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker.compose

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import dev.phantom.docker.ComposeFiles
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue

/** Where a service names another service, a network or a volume. */
object ComposeReferences {
  enum class Kind(val section: String) { SERVICE("services"), NETWORK("networks"), VOLUME("volumes") }

  /** A name and the element that holds it: a scalar, or the key of a mapping entry. */
  data class Target(val name: String, val element: PsiElement, val kind: Kind)

  fun serviceTargets(service: YAMLMapping): List<Target> = buildList {
    val dependsOn = service.getKeyValueByKey("depends_on")?.value
    addAll(names(dependsOn, Kind.SERVICE) { it })
    addAll(names(service.getKeyValueByKey("links")?.value, Kind.SERVICE) { it.substringBefore(':') })
    addAll(names(service.getKeyValueByKey("volumes_from")?.value, Kind.SERVICE) { v -> v.takeUnless { it.startsWith("container:") }?.substringBefore(':') })
    when (val extends = service.getKeyValueByKey("extends")?.value) {
      is YAMLScalar -> add(Target(extends.textValue, extends, Kind.SERVICE))
      is YAMLMapping -> if (extends.getKeyValueByKey("file") == null) {
        (extends.getKeyValueByKey("service")?.value as? YAMLScalar)?.let { add(Target(it.textValue, it, Kind.SERVICE)) }
      }
      else -> {}
    }
  }.filter { it.name.isNotEmpty() && !it.name.contains('$') }

  fun networkTargets(service: YAMLMapping): List<Target> =
    names(service.getKeyValueByKey("networks")?.value, Kind.NETWORK) { it }.filter { !it.name.contains('$') }

  fun volumeTargets(service: YAMLMapping): List<Target> = buildList {
    val volumes = service.getKeyValueByKey("volumes")?.value as? YAMLSequence ?: return@buildList
    for (item in volumes.items) {
      when (val v = item.value) {
        is YAMLScalar -> {
          // `C:\data:/data` is a Windows bind mount, not volume `C`
          if (WINDOWS_PATH.containsMatchIn(v.textValue)) continue
          val parts = v.textValue.split(':')
          val source = parts[0]
          if (parts.size >= 2 && NAMED_VOLUME.matches(source)) add(Target(source, v, Kind.VOLUME))
        }
        is YAMLMapping -> {
          val type = (v.getKeyValueByKey("type")?.value as? YAMLScalar)?.textValue
          val source = v.getKeyValueByKey("source")?.value as? YAMLScalar
          if (type == "volume" && source != null && NAMED_VOLUME.matches(source.textValue)) add(Target(source.textValue, source, Kind.VOLUME))
        }
        else -> {}
      }
    }
  }

  private val NAMED_VOLUME = Regex("""[a-zA-Z0-9][a-zA-Z0-9_.-]*""")
  private val WINDOWS_PATH = Regex("""^[A-Za-z]:[\\/]""")

  /** Items of a list (scalars, mapped by [name]) or keys of a mapping. */
  private fun names(value: YAMLValue?, kind: Kind, name: (String) -> String?): List<Target> = when (value) {
    is YAMLSequence -> value.items.mapNotNull { item ->
      val scalar = item.value as? YAMLScalar ?: return@mapNotNull null
      name(scalar.textValue)?.let { Target(it, scalar, kind) }
    }
    is YAMLMapping -> value.keyValues.map { Target(it.keyText, it.key ?: it, kind) }
    else -> emptyList()
  }

  /** The target this [element] (a scalar or a key) names, when it is one of the places above. */
  fun targetAt(element: PsiElement): Target? {
    val service = serviceOf(element) ?: return null
    return (serviceTargets(service) + networkTargets(service) + volumeTargets(service)).firstOrNull { it.element == element }
  }

  private fun serviceOf(element: PsiElement): YAMLMapping? {
    val file = element.containingFile as? YAMLFile ?: return null
    if (!ComposeFiles.isComposeFile(file.name)) return null
    var kv = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false)
    while (kv != null) {
      val parent = PsiTreeUtil.getParentOfType(kv, YAMLKeyValue::class.java, true)
      if (parent != null && parent.keyText == "services" && (parent.parent as? YAMLMapping)?.parent is org.jetbrains.yaml.psi.YAMLDocument) {
        return kv.value as? YAMLMapping
      }
      kv = parent
    }
    return null
  }

  /** The definitions of [kind] in this compose file and the compose files beside it. */
  fun definitions(file: YAMLFile, kind: Kind): Map<String, YAMLKeyValue> = CachedValuesManager.getCachedValue(file) {
    val siblings = ComposeModel.siblings(file)
    val files = listOf(file) + siblings
    val all = Kind.entries.associateWith { k ->
      val result = LinkedHashMap<String, YAMLKeyValue>()
      for (f in files) {
        val root = f.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: continue
        for (kv in Yaml.keys(root.getKeyValueByKey(k.section)?.value)) result.putIfAbsent(kv.keyText, kv)
      }
      result
    }
    CachedValueProvider.Result.create(all, *ComposeModel.siblingDeps(file, siblings))
  }[kind].orEmpty()
}

/** Ctrl+B and completion on the scalar names: `depends_on: [db]`, `links`, `extends`, `networks: [back]`, `vol:/data`. */
class ComposeReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    registrar.registerReferenceProvider(PlatformPatterns.psiElement(YAMLScalar::class.java), object : PsiReferenceProvider() {
      override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val scalar = element as YAMLScalar
        val target = ComposeReferences.targetAt(scalar) ?: return PsiReference.EMPTY_ARRAY
        val start = scalar.text.indexOf(target.name).takeIf { it >= 0 } ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(NameReference(scalar, TextRange(start, start + target.name.length), target))
      }
    })
  }

  private class NameReference(scalar: YAMLScalar, range: TextRange, private val target: ComposeReferences.Target) : PsiReferenceBase<YAMLScalar>(scalar, range, true) {
    private fun definitions() = ComposeReferences.definitions(element.containingFile as YAMLFile, target.kind)

    override fun resolve(): PsiElement? = definitions()[target.name]

    override fun getVariants(): Array<Any> {
      val own = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java)?.let { serviceName(it) }
      val icon = if (target.kind == ComposeReferences.Kind.SERVICE) AllIcons.Nodes.Deploy else AllIcons.Nodes.DataTables
      return definitions().keys.filter { it != own || target.kind != ComposeReferences.Kind.SERVICE }
        .map { LookupElementBuilder.create(it).withIcon(icon).withTypeText(target.kind.section) }.toTypedArray()
    }

    private fun serviceName(kv: YAMLKeyValue): String? {
      var current: YAMLKeyValue? = kv
      while (current != null) {
        val parent = PsiTreeUtil.getParentOfType(current, YAMLKeyValue::class.java, true)
        if (parent?.keyText == "services") return current.keyText
        current = parent
      }
      return null
    }
  }
}

/** Ctrl+B on the keys of `depends_on:` and `networks:` written as mappings. */
class ComposeGotoDeclarationHandler : GotoDeclarationHandler {
  override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
    val kv = source?.parent as? YAMLKeyValue ?: return null
    if (kv.key != source) return null
    val target = ComposeReferences.targetAt(source) ?: return null
    val definition = ComposeReferences.definitions(source.containingFile as YAMLFile, target.kind)[target.name] ?: return null
    return arrayOf(definition)
  }
}
