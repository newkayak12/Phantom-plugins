// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kubernetes

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.*
import javax.swing.Icon

/**
 * The resources of the project's plain Kubernetes manifests (Helm templates and other templated files left out),
 * read only when a manifest's gutter asks and kept until the next PSI change.
 */
object K8sProjectResources {
  class Named(val resource: K8sResource, val name: String, val namespace: String?) {
    val nameElement: PsiElement?
      get() = ((resource.root.getKeyValueByKey("metadata")?.value as? YAMLMapping)?.getKeyValueByKey("name")?.value) ?: resource.root
  }

  fun all(project: Project): List<Named> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val scope = GlobalSearchScope.projectScope(project)
    val manager = PsiManager.getInstance(project)
    val out = ArrayList<Named>()
    for (ext in listOf("yaml", "yml")) {
      for (file in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
        val text = FileDocumentManager.getInstance().getCachedDocument(file)?.charsSequence
                   ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: continue
        if (!text.contains("kind:") || !text.contains("apiVersion:")) continue
        val yaml = manager.findFile(file) as? YAMLFile ?: continue
        for (resource in K8sResource.all(yaml)) {
          val metadata = resource.root.getKeyValueByKey("metadata")?.value as? YAMLMapping ?: continue
          val name = (metadata.getKeyValueByKey("name")?.value as? YAMLScalar)?.textValue ?: continue
          out += Named(resource, name, namespaceOf(resource))
        }
      }
    }
    CachedValueProvider.Result.create(out as List<Named>, PsiModificationTracker.MODIFICATION_COUNT)
  }

  fun namespaceOf(resource: K8sResource): String? =
    ((resource.root.getKeyValueByKey("metadata")?.value as? YAMLMapping)?.getKeyValueByKey("namespace")?.value as? YAMLScalar)?.textValue

  /** A manifest without a namespace gets one when applied (`-n`, kustomize), so it matches any. */
  fun sameNamespace(a: String?, b: String?): Boolean = a == null || b == null || a == b

  fun podLabelsPath(kind: String): List<String>? = K8sSchema.podSpecPath(kind)?.dropLast(1)?.plus(listOf("metadata", "labels"))

  fun labels(mapping: YAMLMapping?): Map<String, String> =
    mapping?.keyValues?.mapNotNull { kv -> (kv.value as? YAMLScalar)?.textValue?.let { kv.keyText to it } }?.toMap().orEmpty()

  fun mappingAt(root: YAMLMapping, path: List<String>): YAMLMapping? {
    var m = root
    for (key in path) m = m.getKeyValueByKey(key)?.value as? YAMLMapping ?: return null
    return m
  }
}

/**
 * Links between manifests: a Service's selector and the workloads whose pods it selects (both ways); ConfigMap and
 * Secret references (envFrom, valueFrom, volumes, imagePullSecrets) to their definitions; an Ingress backend to its
 * Service; serviceAccountName to the ServiceAccount. Matched by name within the namespace, in the project's files.
 */
class K8sLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomKubernetesBundle.message("k8s.gutter.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null) return null
    val kv = element.parent as? YAMLKeyValue ?: return null
    if (kv.key != element) return null
    val file = element.containingFile as? YAMLFile ?: return null
    val document = PsiTreeUtil.getParentOfType(kv, YAMLDocument::class.java) ?: return null
    val resource = K8sResource.all(file).firstOrNull { it.document == document } ?: return null
    val link = linkOf(kv, resource) ?: return null
    val project = file.project
    val namespace = K8sProjectResources.namespaceOf(resource)
    val targets = K8sProjectResources.all(project).filter { it.resource.document != document && K8sProjectResources.sameNamespace(namespace, it.namespace) && link.matches(it) }
    if (targets.isEmpty()) return null
    val names = targets.joinToString { "${it.resource.kind}/${it.name}" }
    return NavigationGutterIconBuilder.create(link.icon)
      .setTargets(targets.mapNotNull { it.nameElement })
      .setTooltipText(PhantomKubernetesBundle.message(link.tooltipKey, names))
      .setPopupTitle(PhantomKubernetesBundle.message("k8s.gutter.popup"))
      .createLineMarkerInfo(element)
  }

  private class Link(val tooltipKey: String, val icon: Icon, val matches: (K8sProjectResources.Named) -> Boolean)

  private fun linkOf(kv: YAMLKeyValue, resource: K8sResource): Link? {
    val parent = kv.parentMapping ?: return null
    val root = resource.root
    // Service selector -> workloads whose pod template labels contain it
    if (resource.kind == "Service" && kv.keyText == "selector" && parent == K8sProjectResources.mappingAt(root, listOf("spec"))) {
      val selector = K8sProjectResources.labels(kv.value as? YAMLMapping).takeIf { it.isNotEmpty() } ?: return null
      return Link("k8s.gutter.selects", AllIcons.Gutter.ImplementedMethod) { other ->
        val path = K8sProjectResources.podLabelsPath(other.resource.kind) ?: return@Link false
        val labels = K8sProjectResources.labels(K8sProjectResources.mappingAt(other.resource.root, path))
        selector.all { (k, v) -> labels[k] == v }
      }
    }
    // workload pod labels -> Services that select them
    val labelsPath = K8sProjectResources.podLabelsPath(resource.kind)
    if (labelsPath != null && kv.keyText == "labels" && parent == K8sProjectResources.mappingAt(root, labelsPath.dropLast(1))) {
      val labels = K8sProjectResources.labels(kv.value as? YAMLMapping).takeIf { it.isNotEmpty() } ?: return null
      return Link("k8s.gutter.selected.by", AllIcons.Gutter.ImplementingMethod) { other ->
        if (other.resource.kind != "Service") return@Link false
        val spec = K8sProjectResources.mappingAt(other.resource.root, listOf("spec"))
        val selector = K8sProjectResources.labels(spec?.getKeyValueByKey("selector")?.value as? YAMLMapping)
        selector.isNotEmpty() && selector.all { (k, v) -> labels[k] == v }
      }
    }
    val value = (kv.value as? YAMLScalar)?.textValue?.takeIf { it.isNotEmpty() } ?: return null
    fun named(kind: String, key: String) = Link(key, AllIcons.Gutter.ImplementedMethod) { it.resource.kind == kind && it.name == value }
    val owner = (parent.parent as? YAMLKeyValue)?.keyText
    return when {
      kv.keyText == "name" && owner in setOf("configMapRef", "configMapKeyRef", "configMap") -> named("ConfigMap", "k8s.gutter.configmap")
      kv.keyText == "name" && owner in setOf("secretRef", "secretKeyRef") -> named("Secret", "k8s.gutter.secret")
      kv.keyText == "secretName" && owner == "secret" -> named("Secret", "k8s.gutter.secret")
      kv.keyText == "name" && (parent.parent as? YAMLSequenceItem)?.let { (it.parent?.parent as? YAMLKeyValue)?.keyText } == "imagePullSecrets" ->
        named("Secret", "k8s.gutter.secret")
      resource.kind == "Ingress" && kv.keyText == "name" && owner == "service" &&
        ((parent.parent?.parent as? YAMLMapping)?.parent as? YAMLKeyValue)?.keyText in setOf("backend", "defaultBackend") -> named("Service", "k8s.gutter.service")
      resource.kind == "Ingress" && kv.keyText == "serviceName" && owner == "backend" -> named("Service", "k8s.gutter.service")
      kv.keyText in setOf("serviceAccountName", "serviceAccount") &&
        K8sSchema.podSpecPath(resource.kind)?.let { parent == K8sProjectResources.mappingAt(resource.root, it) } == true ->
        named("ServiceAccount", "k8s.gutter.service.account")
      else -> null
    }
  }
}
