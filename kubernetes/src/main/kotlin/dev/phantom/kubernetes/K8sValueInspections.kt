// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kubernetes

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

/** Kinds each built-in API group serves (any version). A kind outside its group's list is a typo or a wrong group. */
internal object K8sKinds {
  val BY_GROUP: Map<String, Set<String>> = mapOf(
    "" to setOf("Pod", "Service", "ConfigMap", "Secret", "Namespace", "ServiceAccount", "PersistentVolumeClaim", "PersistentVolume",
                "Endpoints", "Event", "LimitRange", "ResourceQuota", "Node", "ReplicationController", "PodTemplate", "Binding",
                "ComponentStatus", "List"),
    "apps" to setOf("Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "ControllerRevision"),
    "batch" to setOf("Job", "CronJob"),
    "networking.k8s.io" to setOf("Ingress", "IngressClass", "NetworkPolicy", "IPAddress", "ServiceCIDR"),
    "autoscaling" to setOf("HorizontalPodAutoscaler", "Scale"),
    "policy" to setOf("PodDisruptionBudget", "PodSecurityPolicy", "Eviction"),
    "rbac.authorization.k8s.io" to setOf("Role", "RoleBinding", "ClusterRole", "ClusterRoleBinding"),
    "storage.k8s.io" to setOf("StorageClass", "VolumeAttachment", "CSIDriver", "CSINode", "CSIStorageCapacity", "VolumeAttributesClass"),
    "scheduling.k8s.io" to setOf("PriorityClass"),
    "coordination.k8s.io" to setOf("Lease", "LeaseCandidate"),
    "discovery.k8s.io" to setOf("EndpointSlice"),
    "admissionregistration.k8s.io" to setOf("MutatingWebhookConfiguration", "ValidatingWebhookConfiguration", "ValidatingAdmissionPolicy",
                                            "ValidatingAdmissionPolicyBinding", "MutatingAdmissionPolicy", "MutatingAdmissionPolicyBinding"),
    "apiextensions.k8s.io" to setOf("CustomResourceDefinition"),
    "certificates.k8s.io" to setOf("CertificateSigningRequest", "ClusterTrustBundle"),
    "node.k8s.io" to setOf("RuntimeClass"),
    "events.k8s.io" to setOf("Event"),
    "flowcontrol.apiserver.k8s.io" to setOf("FlowSchema", "PriorityLevelConfiguration"),
    "apiregistration.k8s.io" to setOf("APIService"),
    "authentication.k8s.io" to setOf("TokenReview", "TokenRequest", "SelfSubjectReview"),
    "authorization.k8s.io" to setOf("SubjectAccessReview", "SelfSubjectAccessReview", "LocalSubjectAccessReview", "SelfSubjectRulesReview"),
    "resource.k8s.io" to setOf("ResourceClaim", "ResourceClaimTemplate", "DeviceClass", "ResourceSlice"),
  )

  /** The API group of an `apiVersion` (`apps/v1` → `apps`, `v1` → the core group ""). */
  fun group(apiVersion: String): String = if (apiVersion.contains('/')) apiVersion.substringBefore('/') else ""

  /** Kinds the API server removed or deprecated, kind → message argument (the replacement or the release). */
  val DEPRECATED: Map<String, String> = mapOf(
    "PodSecurityPolicy" to "removed in Kubernetes 1.25; use Pod Security Admission (namespace labels)",
    "ComponentStatus" to "deprecated since Kubernetes 1.19",
    "Endpoints" to "deprecated since Kubernetes 1.33; use EndpointSlice",
  )
}

/** A kind its API group does not serve: `kind: Deploymnet` with `apiVersion: apps/v1`. Custom resource groups are not checked. */
class K8sUnknownResourceInspection : K8sInspection() {
  override fun check(resource: K8sResource, report: (PsiElement, String, ProblemHighlightType) -> Unit) {
    val group = K8sKinds.group(resource.apiVersion)
    val kinds = K8sKinds.BY_GROUP[group] ?: return
    if (resource.kind in kinds || resource.kind.contains('$')) return
    // a kind of another group with the wrong apiVersion is the API version check's report
    if (resource.kind in K8sSchema.CURRENT || K8sKinds.BY_GROUP.values.any { resource.kind in it }) return
    val near = kinds.minByOrNull { distance(it.lowercase(), resource.kind.lowercase()) }?.takeIf { distance(it.lowercase(), resource.kind.lowercase()) <= 2 }
    val message = if (near != null) PhantomKubernetesBundle.message("k8s.kind.unknown.near", resource.kind, resource.apiVersion, near)
                  else PhantomKubernetesBundle.message("k8s.kind.unknown", resource.kind, resource.apiVersion)
    report(resource.kindScalar ?: resource.root, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
  }

  private fun distance(a: String, b: String): Int {
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length) {
      d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
    }
    return d[a.length][b.length]
  }
}

/** Fields with a fixed set of values (`imagePullPolicy`, `restartPolicy`, Service `type`, port `protocol`, …); values are case-sensitive. */
class K8sUnknownValuesInspection : K8sInspection() {
  private class Rule(val key: String, val values: List<String>, val applies: (kind: String, parent: List<String>) -> Boolean)

  private fun inContainer(parent: List<String>) =
    parent.size >= 2 && parent.last() == "[]" && parent[parent.size - 2] in setOf("containers", "initContainers", "ephemeralContainers")
  private fun inPodSpec(kind: String, parent: List<String>) = K8sSchema.podSpecPath(kind) == parent

  private val rules = listOf(
    Rule("imagePullPolicy", listOf("Always", "IfNotPresent", "Never")) { _, p -> inContainer(p) },
    Rule("restartPolicy", listOf("Always", "OnFailure", "Never")) { k, p -> inPodSpec(k, p) },
    // a container's own restartPolicy (sidecar init containers) only takes Always
    Rule("restartPolicy", listOf("Always")) { _, p -> inContainer(p) },
    Rule("dnsPolicy", listOf("ClusterFirst", "ClusterFirstWithHostNet", "Default", "None")) { k, p -> inPodSpec(k, p) },
    Rule("preemptionPolicy", listOf("PreemptLowerPriority", "Never")) { k, p -> inPodSpec(k, p) },
    Rule("protocol", listOf("TCP", "UDP", "SCTP")) { _, p -> p.size >= 2 && p.last() == "[]" && p[p.size - 2] == "ports" },
    Rule("type", listOf("ClusterIP", "NodePort", "LoadBalancer", "ExternalName")) { k, p -> k == "Service" && p == listOf("spec") },
    Rule("sessionAffinity", listOf("None", "ClientIP")) { k, p -> k == "Service" && p == listOf("spec") },
    Rule("externalTrafficPolicy", listOf("Cluster", "Local")) { k, p -> k == "Service" && p == listOf("spec") },
    Rule("internalTrafficPolicy", listOf("Cluster", "Local")) { k, p -> k == "Service" && p == listOf("spec") },
    Rule("ipFamilyPolicy", listOf("SingleStack", "PreferDualStack", "RequireDualStack")) { k, p -> k == "Service" && p == listOf("spec") },
    Rule("type", listOf("RollingUpdate", "Recreate")) { k, p -> k == "Deployment" && p == listOf("spec", "strategy") },
    Rule("type", listOf("RollingUpdate", "OnDelete")) { k, p -> k in setOf("StatefulSet", "DaemonSet") && p == listOf("spec", "updateStrategy") },
    Rule("podManagementPolicy", listOf("OrderedReady", "Parallel")) { k, p -> k == "StatefulSet" && p == listOf("spec") },
    Rule("concurrencyPolicy", listOf("Allow", "Forbid", "Replace")) { k, p -> k == "CronJob" && p == listOf("spec") },
    Rule("completionMode", listOf("NonIndexed", "Indexed")) { k, p -> (k == "Job" && p == listOf("spec")) || (k == "CronJob" && p == listOf("spec", "jobTemplate", "spec")) },
    Rule("pathType", listOf("Exact", "Prefix", "ImplementationSpecific")) { k, p -> k == "Ingress" && p.lastOrNull() == "[]" && p.getOrNull(p.size - 2) == "paths" },
    Rule("volumeMode", listOf("Filesystem", "Block")) { k, p -> k in setOf("PersistentVolumeClaim", "PersistentVolume") && p == listOf("spec") },
    Rule("persistentVolumeReclaimPolicy", listOf("Retain", "Delete", "Recycle")) { k, p -> k == "PersistentVolume" && p == listOf("spec") },
    Rule("terminationMessagePolicy", listOf("File", "FallbackToLogsOnError")) { _, p -> inContainer(p) },
  )

  private val ACCESS_MODES = listOf("ReadWriteOnce", "ReadOnlyMany", "ReadWriteMany", "ReadWriteOncePod")

  override fun check(resource: K8sResource, report: (PsiElement, String, ProblemHighlightType) -> Unit) {
    for (kv in PsiTreeUtil.findChildrenOfType(resource.document, YAMLKeyValue::class.java)) {
      val mapping = kv.parent as? YAMLMapping ?: continue
      val parent = K8sResource.pathOf(mapping) ?: continue
      if (kv.keyText == "accessModes") {
        val modes = kv.value as? YAMLSequence ?: continue
        for (item in modes.items) {
          val scalar = item.value as? YAMLScalar ?: continue
          if (literal(scalar) && scalar.textValue !in ACCESS_MODES) report(scalar, message(scalar.textValue, kv.keyText, ACCESS_MODES), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        }
        continue
      }
      val scalar = kv.value as? YAMLScalar ?: continue
      if (!literal(scalar)) continue
      val rule = rules.firstOrNull { it.key == kv.keyText && it.applies(resource.kind, parent) } ?: continue
      if (scalar.textValue !in rule.values) report(scalar, message(scalar.textValue, kv.keyText, rule.values), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
    }
  }

  private fun literal(scalar: YAMLScalar) = scalar.textValue.isNotEmpty() && !scalar.textValue.contains('$') && !scalar.textValue.contains("{{")
  private fun message(value: String, key: String, allowed: List<String>) =
    PhantomKubernetesBundle.message("k8s.value.unknown", value, key, allowed.joinToString(", "))
}

/** Keys and kinds the API still accepts but has replaced or dropped: `serviceAccount`, Service `topologyKeys`, PodSecurityPolicy. */
class K8sDeprecatedInspection : K8sInspection() {
  override fun check(resource: K8sResource, report: (PsiElement, String, ProblemHighlightType) -> Unit) {
    K8sKinds.DEPRECATED[resource.kind]?.let { why ->
      if (K8sKinds.group(resource.apiVersion).let { g -> K8sKinds.BY_GROUP[g]?.contains(resource.kind) == true }) {
        report(resource.kindScalar ?: resource.root, PhantomKubernetesBundle.message("k8s.kind.deprecated", resource.kind, why), ProblemHighlightType.LIKE_DEPRECATED)
      }
    }
    val pod = K8sSchema.podSpecPath(resource.kind)?.let { mappingAt(resource.root, it) }
    pod?.getKeyValueByKey("serviceAccount")?.let { kv ->
      report(kv.key ?: kv, PhantomKubernetesBundle.message("k8s.key.deprecated", "serviceAccount", "serviceAccountName"), ProblemHighlightType.LIKE_DEPRECATED)
    }
    if (resource.kind == "Service") {
      mappingAt(resource.root, listOf("spec"))?.getKeyValueByKey("topologyKeys")?.let { kv ->
        report(kv.key ?: kv, PhantomKubernetesBundle.message("k8s.key.removed", "topologyKeys", "1.22", "trafficDistribution or topology-aware routing"), ProblemHighlightType.LIKE_DEPRECATED)
      }
    }
  }
}

/** Fields the API server fills in (`status`, `metadata.uid`, `resourceVersion`, …): copied from `kubectl get -o yaml`, ignored or rejected on apply. */
class K8sNonEditableKeysInspection : K8sInspection() {
  private val SERVER_METADATA = listOf("uid", "resourceVersion", "creationTimestamp", "selfLink", "generation", "managedFields", "deletionTimestamp")

  override fun check(resource: K8sResource, report: (PsiElement, String, ProblemHighlightType) -> Unit) {
    if (K8sKinds.BY_GROUP[K8sKinds.group(resource.apiVersion)]?.contains(resource.kind) != true) return
    resource.root.getKeyValueByKey("status")?.let { kv -> report(kv.key ?: kv, PhantomKubernetesBundle.message("k8s.key.server", "status"), ProblemHighlightType.WEAK_WARNING) }
    val metadata = resource.root.getKeyValueByKey("metadata")?.value as? YAMLMapping ?: return
    for (key in SERVER_METADATA) {
      metadata.getKeyValueByKey(key)?.let { kv -> report(kv.key ?: kv, PhantomKubernetesBundle.message("k8s.key.server", "metadata.$key"), ProblemHighlightType.WEAK_WARNING) }
    }
  }
}

/**
 * Kinds the API server never stores, so a manifest of them cannot be applied: reviews (`TokenReview`,
 * `SubjectAccessReview`, ...) are evaluated once and returned, `Binding`/`Eviction`/`Scale` are written through their
 * parent's subresource, and `ComponentStatus` is read-only status.
 */
class K8sNonEditableResourceInspection : K8sInspection() {
  private val REVIEWS = setOf("TokenReview", "TokenRequest", "SelfSubjectReview", "SubjectAccessReview", "SelfSubjectAccessReview",
                              "LocalSubjectAccessReview", "SelfSubjectRulesReview")
  private val SUBRESOURCES = setOf("Binding", "Eviction", "Scale")

  override fun check(resource: K8sResource, report: (PsiElement, String, ProblemHighlightType) -> Unit) {
    if (K8sKinds.BY_GROUP[K8sKinds.group(resource.apiVersion)]?.contains(resource.kind) != true) return
    val why = when (resource.kind) {
      in REVIEWS -> PhantomKubernetesBundle.message("k8s.kind.not.stored.review")
      in SUBRESOURCES -> PhantomKubernetesBundle.message("k8s.kind.not.stored.subresource")
      "ComponentStatus" -> PhantomKubernetesBundle.message("k8s.kind.not.stored.readonly")
      else -> return
    }
    report(resource.kindScalar ?: resource.root, PhantomKubernetesBundle.message("k8s.kind.not.stored", resource.kind, why), ProblemHighlightType.WEAK_WARNING)
  }
}

/** `Chart.yaml`: the required `apiVersion`, `name` and `version`, keys Helm does not know, and the fixed values of `apiVersion` and `type`. */
class HelmChartInspection : LocalInspectionTool() {
  private val TOP = setOf("apiVersion", "name", "version", "kubeVersion", "description", "type", "keywords", "home", "sources", "dependencies",
                          "maintainers", "icon", "appVersion", "deprecated", "annotations",
                          // apiVersion v1 charts
                          "engine", "tillerVersion")
  private val DEPENDENCY = setOf("name", "version", "repository", "condition", "tags", "enabled", "import-values", "alias")
  private val MAINTAINER = setOf("name", "email", "url")

  override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
    if (file !is YAMLFile || file.name != "Chart.yaml") return null
    val root = file.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: return null
    val out = ArrayList<ProblemDescriptor>()
    fun report(e: PsiElement, message: String) = out.add(manager.createProblemDescriptor(e, message, isOnTheFly, null, ProblemHighlightType.GENERIC_ERROR_OR_WARNING))
    for (key in listOf("apiVersion", "name", "version")) {
      if (root.getKeyValueByKey(key) == null) report(root.keyValues.firstOrNull()?.key ?: root, PhantomKubernetesBundle.message("helm.chart.missing", key))
    }
    fun unknown(mapping: YAMLMapping, known: Set<String>, where: String) {
      for (kv in mapping.keyValues) if (kv.keyText !in known) report(kv.key ?: kv, PhantomKubernetesBundle.message("helm.chart.unknown", kv.keyText, where))
    }
    unknown(root, TOP, "Chart.yaml")
    for ((key, known) in listOf("dependencies" to DEPENDENCY, "maintainers" to MAINTAINER)) {
      val list = root.getKeyValueByKey(key)?.value as? YAMLSequence ?: continue
      for (item in list.items) (item.value as? YAMLMapping)?.let { unknown(it, known, key) }
    }
    (root.getKeyValueByKey("apiVersion")?.value as? YAMLScalar)?.let { v ->
      if (v.textValue !in setOf("v1", "v2")) report(v, PhantomKubernetesBundle.message("helm.chart.value", v.textValue, "apiVersion", "v2, v1"))
    }
    (root.getKeyValueByKey("type")?.value as? YAMLScalar)?.let { v ->
      if (v.textValue !in setOf("application", "library")) report(v, PhantomKubernetesBundle.message("helm.chart.value", v.textValue, "type", "application, library"))
    }
    return out.toTypedArray()
  }
}
