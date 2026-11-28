// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.openapi

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.NonNls
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Files that look like an OpenAPI/Swagger document but are not read as one, and the top level of documents that are:
 * - `paths` and `info` at the top but the version key misspelled (`opeanpi:`, `swager:`) or missing;
 * - an `openapi`/`swagger` value no tool reads (`openapi: 2.0`, `swagger: "3.0"`);
 * - unknown top-level keys of a spec (extensions `x-*` are fine), as weak warnings.
 */
class OpenApiSpecShapeInspection : LocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  private class TopKey(val name: String, val key: PsiElement, val value: PsiElement?, val valueText: String?)

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val file = holder.file
    if (file !is YAMLFile && file !is JsonFile) return PsiElementVisitor.EMPTY_VISITOR
    if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return PsiElementVisitor.EMPTY_VISITOR
    return object : PsiElementVisitor() {
      override fun visitFile(psiFile: PsiFile) {
        val keys = topKeys(psiFile) ?: return
        val names = keys.map { it.name }
        val version = keys.firstOrNull { it.name == "openapi" || it.name == "swagger" }
        if (version == null) {
          if ("paths" !in names || "info" !in names) return
          val near = keys.firstOrNull { k -> VERSION_KEYS.any { distance(it, k.name.lowercase()) in 1..2 } || k.name.lowercase() in VERSION_KEYS }
          if (near != null) {
            val meant = VERSION_KEYS.minBy { distance(it, near.name.lowercase()) }
            holder.registerProblem(near.key, PhantomOpenApiBundle.message("shape.version.key.typo", near.name, meant))
          }
          else {
            val paths = keys.first { it.name == "paths" }
            holder.registerProblem(paths.key, PhantomOpenApiBundle.message("shape.version.key.missing"), ProblemHighlightType.WEAK_WARNING)
          }
          return
        }
        val text = version.valueText?.trim()
        val ok = when (version.name) {
          "openapi" -> text != null && OPENAPI_VERSION.matches(text)
          else -> text == "2.0"
        }
        if (!ok) holder.registerProblem(version.value ?: version.key, PhantomOpenApiBundle.message("shape.version.value", version.name, text ?: ""))
        val allowed = if (version.name == "openapi") OPENAPI_KEYS else SWAGGER_KEYS
        for (key in keys) {
          if (key.name in allowed || key.name.startsWith("x-")) continue
          holder.registerProblem(key.key, PhantomOpenApiBundle.message("shape.unknown.key", key.name, version.name), ProblemHighlightType.WEAK_WARNING)
        }
      }
    }
  }

  private fun topKeys(file: PsiFile): List<TopKey>? = when (file) {
    is YAMLFile -> {
      // a multi-document YAML file is not a single spec
      val mapping = file.documents.singleOrNull()?.topLevelValue as? YAMLMapping
      mapping?.keyValues?.mapNotNull { kv -> kv.key?.let { TopKey(kv.keyText, it, kv.value, (kv.value as? YAMLScalar)?.textValue) } }
    }
    is JsonFile -> (file.topLevelValue as? JsonObject)?.propertyList?.map { p ->
      TopKey(p.name, p.nameElement, p.value, (p.value as? JsonValue)?.let { v -> (v as? JsonStringLiteral)?.value ?: v.text })
    }
    else -> null
  }

  companion object {
    @NonNls private val VERSION_KEYS = listOf("openapi", "swagger")
    private val OPENAPI_VERSION = Regex("""3\.\d+(\.\d+)?""")
    @NonNls private val OPENAPI_KEYS = setOf("openapi", "info", "jsonSchemaDialect", "servers", "paths", "webhooks", "components", "security", "tags", "externalDocs")
    @NonNls private val SWAGGER_KEYS = setOf("swagger", "info", "host", "basePath", "schemes", "consumes", "produces", "paths", "definitions",
                                             "parameters", "responses", "securityDefinitions", "security", "tags", "externalDocs")

    /** Edit distance counting a swap of neighbours as one edit. */
    internal fun distance(a: String, b: String): Int {
      if (kotlin.math.abs(a.length - b.length) > 2) return 3
      val d = Array(a.length + 1) { IntArray(b.length + 1) }
      for (i in 0..a.length) d[i][0] = i
      for (j in 0..b.length) d[0][j] = j
      for (i in 1..a.length) for (j in 1..b.length) {
        d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
      }
      return d[a.length][b.length]
    }
  }
}
