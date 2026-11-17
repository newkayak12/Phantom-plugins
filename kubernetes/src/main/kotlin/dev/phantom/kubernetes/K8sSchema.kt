// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kubernetes

import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * A compact schema of the built-in resources most manifests use: current and removed API versions per kind, and the
 * keys of the levels that are listed completely. Custom resources and kinds not listed are left alone.
 */
object K8sSchema {
  /** Kind → API versions the current Kubernetes serves. */
  val CURRENT: Map<String, Set<String>> = mapOf(
    "Pod" to setOf("v1"), "Service" to setOf("v1"), "ConfigMap" to setOf("v1"), "Secret" to setOf("v1"), "Namespace" to setOf("v1"),
    "ServiceAccount" to setOf("v1"), "PersistentVolumeClaim" to setOf("v1"), "PersistentVolume" to setOf("v1"),
    "Deployment" to setOf("apps/v1"), "StatefulSet" to setOf("apps/v1"), "DaemonSet" to setOf("apps/v1"), "ReplicaSet" to setOf("apps/v1"),
    "Job" to setOf("batch/v1"), "CronJob" to setOf("batch/v1"),
    "Ingress" to setOf("networking.k8s.io/v1"), "IngressClass" to setOf("networking.k8s.io/v1"), "NetworkPolicy" to setOf("networking.k8s.io/v1"),
    "HorizontalPodAutoscaler" to setOf("autoscaling/v2", "autoscaling/v1"), "PodDisruptionBudget" to setOf("policy/v1"),
    "Role" to setOf("rbac.authorization.k8s.io/v1"), "RoleBinding" to setOf("rbac.authorization.k8s.io/v1"),
    "ClusterRole" to setOf("rbac.authorization.k8s.io/v1"), "ClusterRoleBinding" to setOf("rbac.authorization.k8s.io/v1"),
    "StorageClass" to setOf("storage.k8s.io/v1"), "PriorityClass" to setOf("scheduling.k8s.io/v1"),
  )

  /** "kind apiVersion" → the Kubernetes version that stopped serving it. */
  val REMOVED: Map<String, String> = buildMap {
    for (kind in listOf("Deployment", "DaemonSet", "ReplicaSet")) {
      put("$kind extensions/v1beta1", "1.16"); put("$kind apps/v1beta1", "1.16"); put("$kind apps/v1beta2", "1.16")
    }
    put("StatefulSet apps/v1beta1", "1.16"); put("StatefulSet apps/v1beta2", "1.16")
    put("NetworkPolicy extensions/v1beta1", "1.16")
    put("Ingress extensions/v1beta1", "1.22"); put("Ingress networking.k8s.io/v1beta1", "1.22")
    put("IngressClass networking.k8s.io/v1beta1", "1.22")
    for (kind in listOf("Role", "RoleBinding", "ClusterRole", "ClusterRoleBinding")) put("$kind rbac.authorization.k8s.io/v1beta1", "1.22")
    put("StorageClass storage.k8s.io/v1beta1", "1.22"); put("PriorityClass scheduling.k8s.io/v1beta1", "1.22")
    put("CronJob batch/v1beta1", "1.25"); put("CronJob batch/v2alpha1", "1.21")
    put("PodDisruptionBudget policy/v1beta1", "1.25")
    put("HorizontalPodAutoscaler autoscaling/v2beta1", "1.25"); put("HorizontalPodAutoscaler autoscaling/v2beta2", "1.26")
  }

  private val COMMON_TOP = setOf("apiVersion", "kind", "metadata", "spec", "status")

  private val TOP: Map<String, Set<String>> = mapOf(
    "ConfigMap" to setOf("apiVersion", "kind", "metadata", "data", "binaryData", "immutable"),
    "Secret" to setOf("apiVersion", "kind", "metadata", "data", "stringData", "type", "immutable"),
    "Role" to setOf("apiVersion", "kind", "metadata", "rules"),
    "ClusterRole" to setOf("apiVersion", "kind", "metadata", "rules", "aggregationRule"),
    "RoleBinding" to setOf("apiVersion", "kind", "metadata", "subjects", "roleRef"),
    "ClusterRoleBinding" to setOf("apiVersion", "kind", "metadata", "subjects", "roleRef"),
    "ServiceAccount" to setOf("apiVersion", "kind", "metadata", "secrets", "imagePullSecrets", "automountServiceAccountToken"),
  )

  val METADATA = setOf("name", "generateName", "namespace", "labels", "annotations", "ownerReferences", "finalizers", "uid",
                       "resourceVersion", "generation", "creationTimestamp", "deletionTimestamp", "deletionGracePeriodSeconds",
                       "managedFields", "selfLink")

  private val WORKLOAD_SPEC: Map<String, Set<String>> = mapOf(
    "Deployment" to setOf("replicas", "selector", "template", "strategy", "minReadySeconds", "revisionHistoryLimit", "paused", "progressDeadlineSeconds"),
    "ReplicaSet" to setOf("replicas", "selector", "template", "minReadySeconds"),
    "StatefulSet" to setOf("replicas", "selector", "serviceName", "template", "volumeClaimTemplates", "updateStrategy", "podManagementPolicy",
                           "revisionHistoryLimit", "minReadySeconds", "persistentVolumeClaimRetentionPolicy", "ordinals"),
    "DaemonSet" to setOf("selector", "template", "updateStrategy", "minReadySeconds", "revisionHistoryLimit"),
    "Job" to JOB_SPEC,
    "CronJob" to setOf("concurrencyPolicy", "failedJobsHistoryLimit", "jobTemplate", "schedule", "startingDeadlineSeconds",
                       "successfulJobsHistoryLimit", "suspend", "timeZone"),
    "Service" to setOf("allocateLoadBalancerNodePorts", "clusterIP", "clusterIPs", "externalIPs", "externalName", "externalTrafficPolicy",
                       "healthCheckNodePort", "internalTrafficPolicy", "ipFamilies", "ipFamilyPolicy", "loadBalancerClass", "loadBalancerIP",
                       "loadBalancerSourceRanges", "ports", "publishNotReadyAddresses", "selector", "sessionAffinity", "sessionAffinityConfig",
                       "trafficDistribution", "type"),
    "Ingress" to setOf("defaultBackend", "ingressClassName", "rules", "tls"),
  )

  private val JOB_SPEC get() = setOf("activeDeadlineSeconds", "backoffLimit", "backoffLimitPerIndex", "completionMode", "completions", "managedBy",
                                     "manualSelector", "maxFailedIndexes", "parallelism", "podFailurePolicy", "podReplacementPolicy", "selector",
                                     "successPolicy", "suspend", "template", "ttlSecondsAfterFinished")

  val POD_SPEC = setOf("activeDeadlineSeconds", "affinity", "automountServiceAccountToken", "containers", "dnsConfig", "dnsPolicy",
                       "enableServiceLinks", "ephemeralContainers", "hostAliases", "hostIPC", "hostNetwork", "hostPID", "hostUsers", "hostname",
                       "hostnameOverride", "imagePullSecrets", "initContainers", "nodeName", "nodeSelector", "os", "overhead", "preemptionPolicy",
                       "priority", "priorityClassName", "readinessGates", "resourceClaims", "resources", "restartPolicy", "runtimeClassName",
                       "schedulerName", "schedulingGates", "securityContext", "serviceAccount", "serviceAccountName", "setHostnameAsFQDN",
                       "shareProcessNamespace", "subdomain", "terminationGracePeriodSeconds", "tolerations", "topologySpreadConstraints", "volumes")

  val CONTAINER = setOf("name", "image", "command", "args", "workingDir", "ports", "envFrom", "env", "resources", "resizePolicy",
                        "restartPolicy", "volumeMounts", "volumeDevices", "livenessProbe", "readinessProbe", "startupProbe", "lifecycle",
                        "terminationMessagePath", "terminationMessagePolicy", "imagePullPolicy", "securityContext", "stdin", "stdinOnce", "tty")

  val CONTAINER_PORT = setOf("containerPort", "hostIP", "hostPort", "name", "protocol")

  val TEMPLATE = setOf("metadata", "spec")

  /** The path of the pod spec inside a resource of [kind]. */
  fun podSpecPath(kind: String): List<String>? = when (kind) {
    "Pod" -> listOf("spec")
    "Deployment", "ReplicaSet", "StatefulSet", "DaemonSet", "Job" -> listOf("spec", "template", "spec")
    "CronJob" -> listOf("spec", "jobTemplate", "spec", "template", "spec")
    else -> null
  }

  /**
   * The complete key set of the mapping at [path] (keys, `[]` for a list item) in a resource of [kind], or null when
   * that level is not listed.
   */
  fun keysAt(kind: String, path: List<String>): Set<String>? {
    if (kind !in CURRENT) return null
    if (path.isEmpty()) return TOP[kind] ?: COMMON_TOP
    if (path == listOf("metadata")) return METADATA
    if (path == listOf("spec")) return if (kind == "Pod") POD_SPEC else WORKLOAD_SPEC[kind]
    if (kind == "CronJob" && path == listOf("spec", "jobTemplate", "spec")) return JOB_SPEC
    val pod = podSpecPath(kind) ?: return null
    val template = pod.dropLast(1)
    if (kind != "Pod" && path == template) return TEMPLATE
    if (kind != "Pod" && path == template + "metadata") return METADATA
    if (path == pod) return POD_SPEC
    if (path.size == pod.size + 2 && path.subList(0, pod.size) == pod && path[pod.size] in setOf("containers", "initContainers") && path.last() == "[]") return CONTAINER
    if (path.size == pod.size + 4 && path.subList(0, pod.size) == pod && path[pod.size + 2] == "ports" && path.last() == "[]") return CONTAINER_PORT
    return null
  }
}

/** A manifest document: its kind and API version, or null when the document is not a Kubernetes resource. */
class K8sResource(val document: YAMLDocument, val root: YAMLMapping, val kind: String, val apiVersion: String) {
  val kindScalar: YAMLScalar? get() = root.getKeyValueByKey("kind")?.value as? YAMLScalar
  val apiVersionScalar: YAMLScalar? get() = root.getKeyValueByKey("apiVersion")?.value as? YAMLScalar

  companion object {
    /** Every resource document of [file]; none for Helm templates or files with template syntax. */
    fun all(file: YAMLFile): List<K8sResource> {
      if (file.textContains('{') && file.text.contains("{{")) return emptyList()
      return file.documents.mapNotNull { doc ->
        val root = doc.topLevelValue as? YAMLMapping ?: return@mapNotNull null
        val kind = (root.getKeyValueByKey("kind")?.value as? YAMLScalar)?.textValue ?: return@mapNotNull null
        val api = (root.getKeyValueByKey("apiVersion")?.value as? YAMLScalar)?.textValue ?: return@mapNotNull null
        K8sResource(doc, root, kind, api)
      }
    }

    /** Path of [mapping] from the document root: keys, and `[]` for list items. */
    fun pathOf(mapping: YAMLMapping): List<String>? {
      val path = ArrayList<String>()
      var e: com.intellij.psi.PsiElement? = mapping.parent
      while (e != null && e !is YAMLDocument) {
        when (e) {
          is YAMLKeyValue -> path.add(e.keyText)
          is YAMLSequenceItem -> path.add("[]")
        }
        e = e.parent
      }
      return if (e is YAMLDocument) path.asReversed() else null
    }
  }
}
