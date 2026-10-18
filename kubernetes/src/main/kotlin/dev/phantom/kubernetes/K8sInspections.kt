// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kubernetes

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue

/** Runs [check] on every Kubernetes resource document of a YAML file. */
abstract class K8sInspection : LocalInspectionTool() {
  override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
    if (file !is YAMLFile) return null
    // YAML in a Markdown fence or a string is an example, often cut short on purpose
    if (com.intellij.lang.injection.InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return null
    val resources = K8sResource.all(file)
    if (resources.isEmpty()) return null
    val out = ArrayList<ProblemDescriptor>()
    for (r in resources) check(r) { element, message, type -> out.add(manager.createProblemDescriptor(element, message, isOnTheFly, null, type)) }
    return out.toTypedArray()
  }

  abstract fun check(resource: K8sResource, report: (PsiElement, String, ProblemHighlightType) -> Unit)

  protected fun mappingAt(root: YAMLMapping, path: List<String>): YAMLMapping? {
    var m: YAMLMapping = root
    for (key in path) m = m.getKeyValueByKey(key)?.value as? YAMLMapping ?: return null
    return m
  }

  /** The container mappings of the pod spec. */
  protected fun containers(resource: K8sResource): List<YAMLMapping> {
    val pod = K8sSchema.podSpecPath(resource.kind)?.let { mappingAt(resource.root, it) } ?: return emptyList()
    return listOf("containers", "initContainers").flatMap { key ->
      (pod.getKeyValueByKey(key)?.value as? YAMLSequence)?.items?.mapNotNull { it.value as? YAMLMapping }.orEmpty()
    }
  }
}

/** API versions removed from Kubernetes, and versions that never served the kind. */
class K8sApiVersionInspection : K8sInspection() {
  override fun check(resource: K8sResource, report: (PsiElement, String, ProblemHighlightType) -> Unit) {
    val current = K8sSchema.CURRENT[resource.kind] ?: return
    if (resource.apiVersion in current) return
    val at = resource.apiVersionScalar ?: return
    val removed = K8sSchema.REMOVED["${resource.kind} ${resource.apiVersion}"]
    val message = if (removed != null) PhantomKubernetesBundle.message("k8s.api.removed", resource.apiVersion, resource.kind, removed, current.first())
                  else PhantomKubernetesBundle.message("k8s.api.unknown", resource.apiVersion, resource.kind, current.joinToString(", "))
    report(at, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
  }
}

/** Keys not in the resource's schema at the fully listed levels, and keys the API server requires. */
class K8sKeysInspection : K8sInspection() {
  override fun check(resource: K8sResource, report: (PsiElement, String, ProblemHighlightType) -> Unit) {
    if (resource.kind !in K8sSchema.CURRENT) return
    // unknown keys, everywhere a level is listed
    for (mapping in PsiTreeUtil.findChildrenOfType(resource.document, YAMLMapping::class.java) + resource.root) {
      val path = K8sResource.pathOf(mapping) ?: continue
      val known = K8sSchema.keysAt(resource.kind, path) ?: continue
      for (kv in mapping.keyValues) {
        if (kv.keyText !in known && kv.keyText != "<<") report(kv.key ?: kv, PhantomKubernetesBundle.message("k8s.key.unknown", kv.keyText, resource.kind), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
      }
    }
    // required keys
    val metadata = resource.root.getKeyValueByKey("metadata")?.value as? YAMLMapping
    if (metadata == null || (metadata.getKeyValueByKey("name") == null && metadata.getKeyValueByKey("generateName") == null)) {
      report(resource.kindScalar ?: resource.root, PhantomKubernetesBundle.message("k8s.key.missing", "metadata.name", resource.kind), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
    }
    val spec = resource.root.getKeyValueByKey("spec")?.value as? YAMLMapping
    val required = when (resource.kind) {
      "Deployment", "ReplicaSet", "StatefulSet", "DaemonSet" -> listOf("selector", "template")
      "Job" -> listOf("template")
      "CronJob" -> listOf("schedule", "jobTemplate")
      else -> emptyList()
    }
    for (key in required) {
      if (spec?.getKeyValueByKey(key) == null) {
        report(resource.root.getKeyValueByKey("spec")?.key ?: resource.kindScalar ?: resource.root,
               PhantomKubernetesBundle.message("k8s.key.missing", "spec.$key", resource.kind), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
      }
    }
    for (container in containers(resource)) {
      for (key in listOf("name", "image")) {
        if (container.getKeyValueByKey(key) == null) {
          report(container.keyValues.firstOrNull()?.key ?: container, PhantomKubernetesBundle.message("k8s.container.missing", key), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        }
      }
    }
  }
}

/** The same variable twice in a container's `env`: the last one wins silently. */
class K8sDuplicateEnvInspection : K8sInspection() {
  override fun check(resource: K8sResource, report: (PsiElement, String, ProblemHighlightType) -> Unit) {
    for (container in containers(resource)) {
      val env = container.getKeyValueByKey("env")?.value as? YAMLSequence ?: continue
      val names = env.items.mapNotNull { (it.value as? YAMLMapping)?.getKeyValueByKey("name")?.value as? YAMLScalar }
      for ((name, scalars) in names.groupBy { it.textValue }) {
        if (scalars.size > 1) scalars.forEach { report(it, PhantomKubernetesBundle.message("k8s.env.duplicate", name), ProblemHighlightType.GENERIC_ERROR_OR_WARNING) }
      }
    }
  }
}

/** `spec.selector.matchLabels` that the pod template's labels do not carry: the API server rejects the workload. */
class K8sSelectorInspection : K8sInspection() {
  override fun check(resource: K8sResource, report: (PsiElement, String, ProblemHighlightType) -> Unit) {
    if (resource.kind !in setOf("Deployment", "ReplicaSet", "StatefulSet", "DaemonSet")) return
    val matchLabels = mappingAt(resource.root, listOf("spec", "selector", "matchLabels")) ?: return
    val labels = mappingAt(resource.root, listOf("spec", "template", "metadata", "labels"))
    for (kv in matchLabels.keyValues) {
      val want = (kv.value as? YAMLScalar)?.textValue ?: continue
      val have = (labels?.getKeyValueByKey(kv.keyText)?.value as? YAMLScalar)?.textValue
      if (have != want) report(kv, PhantomKubernetesBundle.message("k8s.selector.mismatch", kv.keyText, want), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
    }
  }
}

/** Key completion at the listed levels of built-in resources. */
class K8sCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, PlatformPatterns.psiElement().withLanguage(YAMLLanguage.INSTANCE), object : CompletionProvider<CompletionParameters>() {
      override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val file = parameters.originalFile as? YAMLFile ?: return
        val position = parameters.position
        val resource = K8sResource.all(file).firstOrNull { it.document.textRange.containsOffset(parameters.offset) } ?: return
        val (path, present) = levelAt(position) ?: return
        val known = K8sSchema.keysAt(resource.kind, path) ?: return
        for (key in known - present) result.addElement(LookupElementBuilder.create(key).withIcon(AllIcons.Nodes.Property).withTypeText(resource.kind))
      }
    })
  }

  /**
   * Where a key typed at [position] goes: the path of that mapping and the keys it already has. A word alone on a
   * line under `spec:` is parsed as the value of `spec`, so it starts a new mapping below that key.
   */
  private fun levelAt(position: PsiElement): Pair<List<String>, Set<String>>? {
    val kv = PsiTreeUtil.getParentOfType(position, YAMLKeyValue::class.java, false)
    val key = kv?.key
    if (kv != null && key != null && PsiTreeUtil.isAncestor(key, position, false)) {
      val mapping = kv.parent as? YAMLMapping ?: return null
      val path = K8sResource.pathOf(mapping) ?: return null
      return path to mapping.keyValues.filter { it != kv }.map { it.keyText }.toSet()
    }
    val scalar = PsiTreeUtil.getParentOfType(position, YAMLScalar::class.java, false) ?: return null
    val owner = scalar.parent as? YAMLKeyValue
    if (owner != null && owner.value == scalar) {
      if (!onOwnLine(scalar)) return null // `replicas: 3<caret>` is a value, not a key
      val mapping = owner.parent as? YAMLMapping ?: return null
      val path = K8sResource.pathOf(mapping) ?: return null
      return path + owner.keyText to emptySet()
    }
    val mapping = PsiTreeUtil.getParentOfType(scalar, YAMLMapping::class.java) ?: return null
    val path = K8sResource.pathOf(mapping) ?: return null
    return path to mapping.keyValues.map { it.keyText }.toSet()
  }

  /** Whether [value] starts on a line of its own, below its key. */
  private fun onOwnLine(value: YAMLValue): Boolean {
    var e = value.prevSibling
    while (e != null && e.textLength > 0 && e.text.isBlank()) {
      if (e.text.contains('\n')) return true
      e = e.prevSibling
    }
    return e?.text?.contains('\n') == true
  }
}
