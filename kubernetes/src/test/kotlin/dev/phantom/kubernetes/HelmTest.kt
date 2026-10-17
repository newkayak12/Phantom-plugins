// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kubernetes

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLKeyValue

class HelmTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    myFixture.addFileToProject("deploy/charts/data-plane/Chart.yaml", "apiVersion: v2\nname: data-plane\nversion: 0.1.0\n")
    myFixture.addFileToProject("deploy/charts/data-plane/values.yaml", """
      mysql:
        image: mysql:8.4
        rootPassword: secret
        storage: 1Gi
      kafka:
        version: 4.0.0
        clusterName: local
      podAnnotations: {}
    """.trimIndent())
    myFixture.addFileToProject("deploy/charts/data-plane/templates/_helpers.tpl", """
      {{- define "data-plane.labels" -}}
      app.kubernetes.io/part-of: data-plane
      {{- end }}
    """.trimIndent())
    myFixture.enableInspections(HelmValuesInspection::class.java)
  }

  private var templates = 0

  private fun template(text: String) {
    val file = myFixture.addFileToProject("deploy/charts/data-plane/templates/t${templates++}.yaml", text)
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
  }

  fun testValuesCompletion() {
    template("image: {{ .Values.mysql.<caret> }}")
    assertSameElements(myFixture.completeBasic().map { it.lookupString }, "image", "rootPassword", "storage")
  }

  fun testTopLevelAndDollarCompletion() {
    template("password: {{ \$.Values.<caret> }}")
    assertSameElements(myFixture.completeBasic().map { it.lookupString }, "mysql", "kafka", "podAnnotations")
  }

  fun testBuiltinsAndInclude() {
    template("name: {{ .Release.<caret> }}")
    assertTrue(myFixture.completeBasic().map { it.lookupString }.containsAll(listOf("Name", "Namespace")))

    // _helpers.tpl must open as text for its define blocks to be read
    assertEquals("PLAIN_TEXT", myFixture.findFileInTempDir("deploy/charts/data-plane/templates/_helpers.tpl").fileType.name)
    template("labels: {{- include \"<caret>\" . | nindent 4 }}")
    val items = myFixture.completeBasic()?.map { it.lookupString }
    assertEquals(listOf("data-plane.labels"), items)
  }

  fun testNavigation() {
    template("storage: {{ .Values.mysql.sto<caret>rage }}")
    val offset = myFixture.caretOffset
    val targets = HelmGotoDeclarationHandler().getGotoDeclarationTargets(myFixture.file.findElementAt(offset), offset, myFixture.editor)
    assertEquals("storage", (targets!!.single() as YAMLKeyValue).keyText)

    template("labels: {{- include \"data-plane.lab<caret>els\" . }}")
    val includeOffset = myFixture.caretOffset
    val define = HelmGotoDeclarationHandler().getGotoDeclarationTargets(myFixture.file.findElementAt(includeOffset), includeOffset, myFixture.editor)!!.single()
    assertEquals("_helpers.tpl", define.containingFile.name)
  }

  fun testMissingValues() {
    template("""
      image: {{ .Values.mysql.imgae }}
      version: {{ .Values.kafka.version }}
      cluster: {{ .Values.kafak.clusterName }}
      annotations: {{ .Values.podAnnotations.extra }}
      global: {{ .Values.global.registry }}
      # .Values.notInAnAction stays plain text
    """.trimIndent())
    val warnings = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).mapNotNull(HighlightInfo::getDescription).filter { "values.yaml" in it }
    assertSameElements(warnings,
                       "'.Values.mysql.imgae' is not in data-plane/values.yaml; Helm renders it empty unless the release sets it",
                       "'.Values.kafak' is not in data-plane/values.yaml; Helm renders it empty unless the release sets it")
  }

  fun testYamlOutsideChartsIsIgnored() {
    myFixture.configureByText("application.yaml", "name: {{ .Values.<caret> }}")
    assertFalse(myFixture.completeBasic().orEmpty().any { it.lookupString == "mysql" })
  }
}
