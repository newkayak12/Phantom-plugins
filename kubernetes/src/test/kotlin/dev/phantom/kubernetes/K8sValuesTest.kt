// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kubernetes

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class K8sValuesTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(K8sUnknownResourceInspection::class.java, K8sUnknownValuesInspection::class.java, K8sDeprecatedInspection::class.java,
                                K8sNonEditableKeysInspection::class.java, HelmChartInspection::class.java)
  }

  private fun problems(): List<String> = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING || it.description?.contains("deprecated") == true || it.description?.contains("removed") == true }
    .mapNotNull(HighlightInfo::getDescription).sorted()

  private fun msg(key: String, vararg p: Any) = PhantomKubernetesBundle.message(key, *p)

  fun testValidManifestHasNoProblems() {
    myFixture.configureByText("ok.yaml", """
      apiVersion: apps/v1
      kind: Deployment
      metadata:
        name: app
      spec:
        strategy:
          type: RollingUpdate
        selector:
          matchLabels: {app: app}
        template:
          metadata:
            labels: {app: app}
          spec:
            restartPolicy: Always
            serviceAccountName: app
            initContainers:
              - name: sidecar
                image: proxy
                restartPolicy: Always
            containers:
              - name: app
                image: app:1
                imagePullPolicy: IfNotPresent
                ports:
                  - containerPort: 8080
                    protocol: TCP
      ---
      apiVersion: v1
      kind: Service
      metadata:
        name: app
      spec:
        type: NodePort
        ports:
          - port: 80
      ---
      apiVersion: v1
      kind: PersistentVolumeClaim
      metadata:
        name: data
      spec:
        accessModes: [ReadWriteOnce]
      ---
      apiVersion: example.com/v1
      kind: Widjet
      metadata:
        name: custom
      status: {}
    """.trimIndent())
    assertEquals(emptyList<String>(), problems())
  }

  fun testUnknownKindsAndValues() {
    myFixture.configureByText("bad.yaml", """
      apiVersion: apps/v1
      kind: Deploymnet
      metadata:
        name: app
      ---
      apiVersion: v1
      kind: Pod
      metadata:
        name: p
      spec:
        restartPolicy: always
        dnsPolicy: ClusterFirst
        containers:
          - name: app
            image: app:1
            imagePullPolicy: Sometimes
            ports:
              - containerPort: 53
                protocol: ICMP
      ---
      apiVersion: v1
      kind: Service
      metadata:
        name: s
      spec:
        type: Loadbalancer
      ---
      apiVersion: v1
      kind: PersistentVolumeClaim
      metadata:
        name: d
      spec:
        accessModes:
          - ReadWriteOne
    """.trimIndent())
    assertEquals(listOf(
      msg("k8s.kind.unknown.near", "Deploymnet", "apps/v1", "Deployment"),
      msg("k8s.value.unknown", "ICMP", "protocol", "TCP, UDP, SCTP"),
      msg("k8s.value.unknown", "Loadbalancer", "type", "ClusterIP, NodePort, LoadBalancer, ExternalName"),
      msg("k8s.value.unknown", "ReadWriteOne", "accessModes", "ReadWriteOnce, ReadOnlyMany, ReadWriteMany, ReadWriteOncePod"),
      msg("k8s.value.unknown", "Sometimes", "imagePullPolicy", "Always, IfNotPresent, Never"),
      msg("k8s.value.unknown", "always", "restartPolicy", "Always, OnFailure, Never"),
    ).sorted(), problems())
  }

  fun testDeprecatedAndServerFields() {
    myFixture.configureByText("old.yaml", """
      apiVersion: policy/v1beta1
      kind: PodSecurityPolicy
      metadata:
        name: restricted
      ---
      apiVersion: v1
      kind: Pod
      metadata:
        name: p
        uid: 1f2e
        resourceVersion: "123"
      spec:
        serviceAccount: app
        containers:
          - name: app
            image: app:1
      status:
        phase: Running
    """.trimIndent())
    assertEquals(listOf(
      msg("k8s.kind.deprecated", "PodSecurityPolicy", "removed in Kubernetes 1.25; use Pod Security Admission (namespace labels)"),
      msg("k8s.key.deprecated", "serviceAccount", "serviceAccountName"),
      msg("k8s.key.server", "status"),
      msg("k8s.key.server", "metadata.uid"),
      msg("k8s.key.server", "metadata.resourceVersion"),
    ).sorted(), problems())
  }

  fun testKindsThatAreNeverStored() {
    myFixture.enableInspections(K8sNonEditableResourceInspection::class.java)
    myFixture.configureByText("review.yaml", """
      apiVersion: authorization.k8s.io/v1
      kind: SelfSubjectAccessReview
      spec:
        resourceAttributes:
          verb: get
      ---
      apiVersion: v1
      kind: Binding
      metadata:
        name: p
      target:
        name: node-1
      ---
      apiVersion: v1
      kind: ConfigMap
      metadata:
        name: fine
      ---
      apiVersion: example.com/v1
      kind: TokenReview
      metadata:
        name: custom-group-is-not-checked
    """.trimIndent())
    val notStored = problems().filter { it.contains("cannot be applied") }
    assertEquals(listOf(
      msg("k8s.kind.not.stored", "Binding", msg("k8s.kind.not.stored.subresource")),
      msg("k8s.kind.not.stored", "SelfSubjectAccessReview", msg("k8s.kind.not.stored.review")),
    ).sorted(), notStored)
  }

  fun testHelmChart() {
    myFixture.configureByText("Chart.yaml", """
      apiVersion: v3
      name: reservation
      type: app
      appVersoin: "1.0"
      dependencies:
        - name: redis
          version: 19.x
          repo: https://charts.example.com
      maintainers:
        - name: me
          email: me@example.com
    """.trimIndent())
    assertEquals(listOf(
      msg("helm.chart.missing", "version"),
      msg("helm.chart.unknown", "appVersoin", "Chart.yaml"),
      msg("helm.chart.unknown", "repo", "dependencies"),
      msg("helm.chart.value", "v3", "apiVersion", "v2, v1"),
      msg("helm.chart.value", "app", "type", "application, library"),
    ).sorted(), problems())
  }

  fun testValidHelmChart() {
    myFixture.configureByText("Chart.yaml", "apiVersion: v2\nname: app\nversion: 0.1.0\ntype: application\nappVersion: \"1.0\"\n")
    assertEquals(emptyList<String>(), problems())
  }
}
