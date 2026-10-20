// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.docker.dockerfile.DockerfileStageGotoDeclarationHandler

class DockerNavigationTest : BasePlatformTestCase() {
  /** Tooltip -> the targets (file path or key name) of the compose gutters of the open file. */
  private fun gutters(): List<String> = myFixture.findAllGutters().mapNotNull { g ->
    val info = (g as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.lineMarkerInfo as? RelatedItemLineMarkerInfo<*> ?: return@mapNotNull null
    if (g.tooltipText?.let { "Built from" in it || "Depends on" in it } != true) return@mapNotNull null
    "${g.tooltipText!!.substringBefore(' ')} -> " + info.createGotoRelatedItems().mapNotNull { item ->
      when (val e = item.element) {
        is PsiFile -> e.virtualFile.path.substringAfter("/src/")
        is PsiNamedElement -> e.name
        else -> e?.text
      }
    }.sorted().joinToString()
  }.sorted()

  fun testBuildAndDependsOn() {
    myFixture.addFileToProject("api/Dockerfile", "FROM eclipse-temurin:21\n")
    myFixture.addFileToProject("web/docker/web.Dockerfile", "FROM node:20\n")
    myFixture.addFileToProject("Dockerfile", "FROM alpine\n")
    myFixture.configureByText("compose.yaml", """
      services:
        root:
          build: .
        api:
          build: ./api
          depends_on:
            - db
            - cache
        web:
          build:
            context: web
            dockerfile: docker/web.Dockerfile
          depends_on:
            api:
              condition: service_started
        remote:
          build: https://github.com/x/y.git
        missing:
          build: ./nowhere
        db:
          image: mysql
    """.trimIndent())
    assertEquals(listOf(
      "Built -> Dockerfile",
      "Built -> api/Dockerfile",
      "Built -> web/docker/web.Dockerfile",
      "Depends -> api",
      "Depends -> db",
    ), gutters())
  }

  private fun stageTarget(text: String): Int? {
    val offset = text.indexOf("<caret>")
    myFixture.configureByText("Dockerfile", text)
    val source = myFixture.file.findElementAt(offset) ?: myFixture.file
    val targets = DockerfileStageGotoDeclarationHandler().getGotoDeclarationTargets(source, offset, myFixture.editor) ?: return null
    return targets.single().textOffset
  }

  fun testStageNavigation() {
    val text = """
      FROM gradle:8 AS build
      RUN gradle bootJar
      FROM build AS test
      FROM eclipse-temurin:21
      COPY --from=build /app.jar /app.jar
      RUN --mount=type=cache,from=test,target=/cache true
    """.trimIndent()
    val build = text.indexOf("build\n")
    val test = text.indexOf("test\n")
    assertEquals(build, stageTarget(text.replace("--from=build", "--from=bu<caret>ild")))
    assertEquals(test, stageTarget(text.replace("from=test,", "from=te<caret>st,")))
    assertEquals(build, stageTarget(text.replace("FROM build AS", "FROM bui<caret>ld AS")))
    // an image, not a stage
    assertNull(stageTarget(text.replace("eclipse-temurin:21", "eclipse-<caret>temurin:21")))
  }

  fun testStageUsageMarkers() {
    myFixture.configureByText("Dockerfile", """
      FROM gradle:8 AS build
      RUN gradle bootJar
      FROM build AS test
      FROM alpine AS unused
      FROM eclipse-temurin:21
      COPY --from=build /app.jar /app.jar
      RUN --mount=type=cache,from=test,target=/cache true
    """.trimIndent())
    val markers = myFixture.findAllGutters().filter { it.tooltipText?.startsWith("Stage ") == true }.map { g ->
      val info = (g as LineMarkerInfo.LineMarkerGutterIconRenderer<*>).lineMarkerInfo as RelatedItemLineMarkerInfo<*>
      val line = myFixture.editor.document.getLineNumber(info.startOffset)
      "$line ${g.tooltipText} -> " + info.createGotoRelatedItems().map { myFixture.editor.document.getLineNumber(it.element!!.textOffset) }
    }.sorted()
    assertEquals(listOf("0 Stage 'build' is used 2 times -> [2, 5]", "2 Stage 'test' is used once -> [6]"), markers)
  }
}
