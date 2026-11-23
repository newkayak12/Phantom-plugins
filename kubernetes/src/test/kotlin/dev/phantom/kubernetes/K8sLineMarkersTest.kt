// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kubernetes

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile

class K8sLineMarkersTest : BasePlatformTestCase() {
  /** Tooltip -> the text of each target, for the Kubernetes gutters of the open file. */
  private fun gutters(): List<String> = myFixture.findAllGutters().mapNotNull { g ->
    val info = (g as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.lineMarkerInfo as? RelatedItemLineMarkerInfo<*> ?: return@mapNotNull null
    if (info.lineMarkerTooltip == null) return@mapNotNull null
    "${g.tooltipText} -> " + info.createGotoRelatedItems().mapNotNull { it.element?.text }.sorted().joinToString()
  }.sorted()

  private val deployment = """
    apiVersion: apps/v1
    kind: Deployment
    metadata:
      name: api
    spec:
      selector:
        matchLabels: {app: api}
      template:
        metadata:
          labels:
            app: api
            tier: web
        spec:
          serviceAccountName: api-sa
          imagePullSecrets:
            - name: registry
          containers:
            - name: api
              image: api:1
              envFrom:
                - configMapRef:
                    name: api-config
              env:
                - name: PASSWORD
                  valueFrom:
                    secretKeyRef:
                      name: api-secret
                      key: password
                - name: MISSING
                  valueFrom:
                    configMapKeyRef:
                      name: nowhere
                      key: x
          volumes:
            - name: conf
              configMap:
                name: api-config
            - name: certs
              secret:
                secretName: api-secret
  """.trimIndent()

  private val others = """
    apiVersion: v1
    kind: Service
    metadata:
      name: api
    spec:
      selector:
        app: api
      ports:
        - port: 80
    ---
    apiVersion: v1
    kind: Service
    metadata:
      name: other
    spec:
      selector:
        app: other
    ---
    apiVersion: v1
    kind: ConfigMap
    metadata:
      name: api-config
    data: {a: b}
    ---
    apiVersion: v1
    kind: Secret
    metadata:
      name: api-secret
    ---
    apiVersion: v1
    kind: Secret
    metadata:
      name: registry
    ---
    apiVersion: v1
    kind: ServiceAccount
    metadata:
      name: api-sa
    ---
    apiVersion: networking.k8s.io/v1
    kind: Ingress
    metadata:
      name: web
    spec:
      defaultBackend:
        service:
          name: api
          port: {number: 80}
      rules:
        - http:
            paths:
              - path: /
                pathType: Prefix
                backend:
                  service:
                    name: other
                    port: {number: 80}
  """.trimIndent()

  fun testWorkloadReferences() {
    myFixture.addFileToProject("k8s/others.yaml", others)
    myFixture.configureByText("deployment.yaml", deployment)
    assertEquals(listOf(
      "Defined as ConfigMap/api-config -> api-config",
      "Defined as ConfigMap/api-config -> api-config",
      "Defined as Secret/api-secret -> api-secret",
      "Defined as Secret/api-secret -> api-secret",
      "Defined as Secret/registry -> registry",
      "Defined as ServiceAccount/api-sa -> api-sa",
      "Pods selected by Service/api -> api",
    ), gutters())
  }

  fun testServiceAndIngress() {
    myFixture.addFileToProject("k8s/deployment.yaml", deployment)
    myFixture.configureByText("others.yaml", others)
    // the Service "other" selects nothing in the project; the Ingress backends point at Services of this same file
    assertEquals(listOf("Selects pods of Deployment/api -> api"), gutters().filter { "Selects" in it })
  }

  fun testIngressBackendToServiceInAnotherFile() {
    myFixture.addFileToProject("k8s/svc.yaml", others.substringBefore("---"))
    myFixture.configureByText("ingress.yaml", others.substringAfterLast("---\n"))
    assertEquals(listOf("Defined as Service/api -> api"), gutters())
  }

  fun testNamespacesMustMatch() {
    myFixture.addFileToProject("k8s/cm.yaml", """
      apiVersion: v1
      kind: ConfigMap
      metadata:
        name: cfg
        namespace: prod
    """.trimIndent())
    myFixture.configureByText("pod.yaml", """
      apiVersion: v1
      kind: Pod
      metadata:
        name: p
        namespace: dev
      spec:
        containers:
          - name: c
            image: x
            envFrom:
              - configMapRef:
                  name: cfg
    """.trimIndent())
    assertEmpty(gutters())
  }

  /** The resources of a file are read once per file version, not once per key the gutter visits. */
  fun testResourcesAreCachedPerFile() {
    val file = myFixture.configureByText("cm.yaml", "apiVersion: v1\nkind: <caret>ConfigMap\nmetadata:\n  name: cfg\n") as YAMLFile
    val first = K8sResource.all(file)
    assertSame(first, K8sResource.all(file))
    myFixture.type("X")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertEquals(listOf("XConfigMap"), K8sResource.all(file).map { it.kind })
  }

  /** A YAML edit in another manifest updates the targets; an edit in a file of another language keeps the scan. */
  fun testProjectResourcesFollowYamlEditsOnly() {
    val cm = myFixture.addFileToProject("k8s/cm.yaml", "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cfg\n")
    val notes = myFixture.addFileToProject("notes.txt", "hello\n")
    myFixture.configureByText("pod.yaml", """
      apiVersion: v1
      kind: Pod
      metadata:
        name: p
      spec:
        containers:
          - name: c
            image: x
            envFrom:
              - configMapRef:
                  name: cfg2
    """.trimIndent())
    assertEmpty(gutters())
    val before = K8sProjectResources.all(project)
    edit(notes) { it.insertString(0, "more ") }
    assertSame(before, K8sProjectResources.all(project))
    edit(cm) { it.replaceString(it.text.indexOf("cfg"), it.text.indexOf("cfg") + 3, "cfg2") }
    assertEquals(listOf("Defined as ConfigMap/cfg2 -> cfg2"), gutters())
  }

  private fun edit(file: PsiFile, change: (Document) -> Unit) {
    val documents = PsiDocumentManager.getInstance(project)
    WriteCommandAction.runWriteCommandAction(project) { change(documents.getDocument(file)!!) }
    documents.commitAllDocuments()
  }
}
