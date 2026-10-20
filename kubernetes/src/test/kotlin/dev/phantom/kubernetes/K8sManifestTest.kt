// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kubernetes

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class K8sManifestTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(K8sApiVersionInspection::class.java, K8sKeysInspection::class.java, K8sDuplicateEnvInspection::class.java,
                                K8sSelectorInspection::class.java, HelmValuesInspection::class.java)
  }

  private fun problems(): List<String> = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
    .mapNotNull(HighlightInfo::getDescription).sorted()

  private val deployment = """
    apiVersion: apps/v1
    kind: Deployment
    metadata:
      name: reservation
      labels:
        app: reservation
    spec:
      replicas: 2
      selector:
        matchLabels:
          app: reservation
      template:
        metadata:
          labels:
            app: reservation
        spec:
          containers:
            - name: app
              image: registry.local/reservation:1.0
              ports:
                - containerPort: 8080
                  name: http
              env:
                - name: SPRING_PROFILES_ACTIVE
                  value: k8s
              readinessProbe:
                httpGet:
                  path: /actuator/health
                  port: http
    ---
    apiVersion: v1
    kind: Service
    metadata:
      name: reservation
    spec:
      selector:
        app: reservation
      ports:
        - port: 80
          targetPort: http
    ---
    apiVersion: gateway.networking.k8s.io/v1
    kind: Gateway
    metadata:
      name: edge
    spec:
      gatewayClassName: envoy
      anything: goes
  """.trimIndent()

  fun testCleanManifestsAndOtherConfigs() {
    myFixture.configureByText("app.yaml", deployment)
    assertEmpty(problems())
    // the user's k3d cluster config: apiVersion and kind, but not a Kubernetes resource
    myFixture.configureByText("cluster.yaml", javaClass.getResource("/k8s/k3d-cluster.yaml")!!.readText())
    assertEmpty(problems())
    // Helm templates are skipped
    myFixture.addFileToProject("chart/Chart.yaml", "apiVersion: v2\nname: c\nversion: 0.1.0\n")
    myFixture.addFileToProject("chart/values.yaml", "image: x\n")
    val template = myFixture.addFileToProject("chart/templates/d.yaml", "apiVersion: extensions/v1beta1\nkind: Deployment\nmetadata:\n  name: {{ .Release.Name }}\nspec:\n  bogus: 1\n")
    myFixture.configureFromExistingVirtualFile(template.virtualFile)
    assertEmpty(problems())
  }

  fun testProblems() {
    myFixture.configureByText("bad.yaml", """
      apiVersion: extensions/v1beta1
      kind: Ingress
      metadata:
        name: old
      ---
      apiVersion: apps/v2
      kind: Deployment
      metadata:
        labels: {}
      spec:
        replica: 1
        selector:
          matchLabels:
            app: api
        template:
          metadata:
            labels:
              app: web
          spec:
            containers:
              - name: api
                imagePullPolicy: Always
                port: 80
                env:
                  - name: A
                    value: "1"
                  - name: A
                    value: "2"
      ---
      apiVersion: batch/v1
      kind: CronJob
      metadata:
        name: nightly
      spec:
        jobTemplate:
          spec:
            template:
              spec:
                restartPolicy: OnFailure
                containers:
                  - name: job
                    image: busybox
    """.trimIndent())
    assertEquals(listOf(
      "'A' is set more than once in this container; only the last value is used",
      "'A' is set more than once in this container; only the last value is used",
      "'port' is not a key here in a Deployment",
      "'replica' is not a key here in a Deployment",
      "A container needs 'image'",
      "CronJob needs spec.schedule",
      "Deployment needs metadata.name",
      "The pod template has no label app: api, so the selector matches none of its pods",
      "apps/v2 does not serve Deployment; use apps/v1",
      "extensions/v1beta1 Ingress was removed in Kubernetes 1.22; use networking.k8s.io/v1",
    ), problems())
  }

  fun testKeyCompletion() {
    myFixture.configureByText("c.yaml", """
      apiVersion: apps/v1
      kind: Deployment
      metadata:
        name: x
      spec:
        replicas: 1
        <caret>
    """.trimIndent())
    val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertTrue(items.toString(), items.containsAll(listOf("selector", "template", "strategy")))
    assertFalse(items.contains("replicas"))

    myFixture.configureByText("d.yaml", """
      apiVersion: v1
      kind: Pod
      metadata:
        name: p
      spec:
        containers:
          - name: c
            imagePu<caret>
    """.trimIndent())
    myFixture.completeBasic()
    assertTrue(myFixture.file.text, myFixture.file.text.contains("imagePullPolicy"))
  }

  fun testRequiredKeysOfListItems() {
    myFixture.configureByText("pod.yaml", """
      apiVersion: v1
      kind: Pod
      metadata:
        name: p
      spec:
        volumes:
          - emptyDir: {}
        containers:
          - name: app
            image: app:1
            ports:
              - name: http
            env:
              - value: x
            volumeMounts:
              - name: data
      ---
      apiVersion: v1
      kind: Service
      metadata:
        name: s
      spec:
        ports:
          - targetPort: 8080
    """.trimIndent())
    assertEquals(listOf(
      "An item of env[] needs 'name'",
      "An item of ports[] needs 'containerPort'",
      "An item of ports[] needs 'port'",
      "An item of volumeMounts[] needs 'mountPath'",
      "An item of volumes[] needs 'name'",
    ), problems())
  }

  fun testDeprecatedLabels() {
    myFixture.enableInspections(K8sDeprecatedLabelsInspection::class.java)
    myFixture.configureByText("deploy.yaml", """
      apiVersion: v1
      kind: Pod
      metadata:
        name: p
        labels:
          kubernetes.io/os: linux
        annotations:
          kubernetes.io/ingress.class: nginx
          container.apparmor.security.beta.kubernetes.io/app: runtime/default
      spec:
        nodeSelector:
          beta.kubernetes.io/os: linux
        tolerations:
          - key: node-role.kubernetes.io/master
            effect: NoSchedule
        affinity:
          podAntiAffinity:
            preferredDuringSchedulingIgnoredDuringExecution:
              - weight: 1
                podAffinityTerm:
                  topologyKey: topology.kubernetes.io/zone
        containers:
          - name: app
            image: app:1
    """.trimIndent())
    assertEquals(listOf(
      "'beta.kubernetes.io/os' is deprecated; use 'kubernetes.io/os'",
      "'container.apparmor.security.beta.kubernetes.io/app' is no longer read by Kubernetes; use securityContext.appArmorProfile",
      "'kubernetes.io/ingress.class' is deprecated; use 'spec.ingressClassName'",
      "'node-role.kubernetes.io/master' is deprecated; use 'node-role.kubernetes.io/control-plane'",
    ), problems())
  }
}
