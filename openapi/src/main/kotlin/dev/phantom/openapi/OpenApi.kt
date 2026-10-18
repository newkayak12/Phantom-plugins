// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.openapi

import com.intellij.DynamicBundle
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue
import java.net.URLDecoder

@NonNls
private const val BUNDLE = "messages.PhantomOpenApiBundle"

object PhantomOpenApiBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

/** One operation of a spec: `paths./users/{id}.get`, with the method key as its PSI element. */
data class SpecOperation(val path: String, val method: String, val element: PsiElement)

/**
 * OpenAPI 3 and Swagger 2 documents in YAML or JSON. A document is a spec when its top level has `openapi` or
 * `swagger`; files it points to with `$ref` are read the same way but are not specs themselves.
 */
object OpenApiSpecs {
  val HTTP_METHODS: Set<String> = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
  private val COMPONENT_KINDS = listOf("schemas", "responses", "parameters", "examples", "requestBodies", "headers", "securitySchemes", "links", "callbacks", "pathItems")
  private val SPEC_TEXT = Regex("""(?m)^\s*(openapi|swagger)\s*:|"(openapi|swagger)"\s*:""")

  fun isSpec(file: PsiFile?): Boolean {
    if (file !is YAMLFile && file !is JsonFile) return false
    return CachedValuesManager.getCachedValue(file) {
      CachedValueProvider.Result.create(topKeys(file).any { it == "openapi" || it == "swagger" }, file)
    }
  }

  private fun topKeys(file: PsiFile): List<String> = when (file) {
    is YAMLFile -> file.documents.flatMap { (it.topLevelValue as? YAMLMapping)?.keyValues?.map { kv -> kv.keyText }.orEmpty() }
    is JsonFile -> (file.topLevelValue as? JsonObject)?.propertyList?.map { it.name }.orEmpty()
    else -> emptyList()
  }

  /** The project's spec files. Only files whose text mentions `openapi`/`swagger` near the top are parsed. */
  fun specFiles(project: Project): List<PsiFile> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val scope = GlobalSearchScope.projectScope(project)
    val psiManager = PsiManager.getInstance(project)
    val files = listOf("yaml", "yml", "json").flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }
      .filter { it.length < 4 * 1024 * 1024 && looksLikeSpec(it) }
      .mapNotNull { psiManager.findFile(it) }
      .filter { isSpec(it) }
    CachedValueProvider.Result.create(files, PsiModificationTracker.getInstance(project), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
  }

  private fun looksLikeSpec(file: VirtualFile): Boolean {
    val head = runCatching { VfsUtilCore.loadText(file, 4096) }.getOrNull() ?: return false
    return SPEC_TEXT.containsMatchIn(head)
  }

  fun operations(file: PsiFile): List<SpecOperation> = CachedValuesManager.getCachedValue(file) {
    val result = ArrayList<SpecOperation>()
    when (file) {
      is YAMLFile -> file.documents.forEach { doc ->
        val paths = (doc.topLevelValue as? YAMLMapping)?.getKeyValueByKey("paths")?.value as? YAMLMapping ?: return@forEach
        for (path in paths.keyValues) {
          for (op in (path.value as? YAMLMapping)?.keyValues.orEmpty()) {
            if (op.keyText.lowercase() in HTTP_METHODS) result += SpecOperation(path.keyText, op.keyText.uppercase(), op.key ?: op)
          }
        }
      }
      is JsonFile -> {
        val paths = (file.topLevelValue as? JsonObject)?.findProperty("paths")?.value as? JsonObject
        for (path in paths?.propertyList.orEmpty()) {
          for (op in (path.value as? JsonObject)?.propertyList.orEmpty()) {
            if (op.name.lowercase() in HTTP_METHODS) result += SpecOperation(path.name, op.name.uppercase(), op.nameElement)
          }
        }
      }
    }
    CachedValueProvider.Result.create(result.toList(), file)
  }

  /** The element a JSON pointer (`/components/schemas/Pet`) names in [file]: the key-value or property, or the file for an empty pointer. */
  fun resolvePointer(file: PsiFile, pointer: String): PsiElement? {
    val segments = pointer.trim().removePrefix("/").split('/').filter { it.isNotEmpty() }
      .map { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8).replace("~1", "/").replace("~0", "~") }
    if (segments.isEmpty()) return file
    return when (file) {
      is YAMLFile -> {
        var value: YAMLValue? = file.documents.firstOrNull()?.topLevelValue
        var found: PsiElement? = null
        for (segment in segments) {
          when (val current = value) {
            is YAMLMapping -> {
              val kv = current.getKeyValueByKey(segment) ?: return null
              found = kv
              value = kv.value
            }
            is YAMLSequence -> {
              val item = segment.toIntOrNull()?.let { current.items.getOrNull(it) } ?: return null
              found = item
              value = item.value
            }
            else -> return null
          }
        }
        found
      }
      is JsonFile -> {
        var value: JsonValue? = file.topLevelValue
        var found: PsiElement? = null
        for (segment in segments) {
          when (val current = value) {
            is JsonObject -> {
              val property = current.findProperty(segment) ?: return null
              found = property
              value = property.value
            }
            is JsonArray -> {
              val item = segment.toIntOrNull()?.let { current.valueList.getOrNull(it) } ?: return null
              found = item
              value = item
            }
            else -> return null
          }
        }
        found
      }
      else -> null
    }
  }

  /** `#/components/<kind>/<name>` (and Swagger 2's `#/definitions/<name>`) for completion. */
  fun componentPointers(file: PsiFile): List<String> {
    val result = ArrayList<String>()
    for (kind in COMPONENT_KINDS) names(file, "components", kind).forEach { result += "#/components/$kind/$it" }
    for (kind in listOf("definitions", "parameters", "responses")) names(file, kind).forEach { result += "#/$kind/$it" }
    return result
  }

  private fun names(file: PsiFile, vararg path: String): List<String> = when (val target = resolvePointer(file, path.joinToString("/", prefix = "/"))) {
    is YAMLKeyValue -> (target.value as? YAMLMapping)?.keyValues?.map { it.keyText }.orEmpty()
    is JsonProperty -> (target.value as? JsonObject)?.propertyList?.map { it.name }.orEmpty()
    else -> emptyList()
  }

  /** `/users/{id}` and `/users/{userId}/` compare equal: parameter names and trailing slashes do not matter. */
  fun normalizePath(path: String): String = "/" + path.trim().trim('/').replace(Regex("""\{[^}]*}"""), "{}")

  /** Exact match, or one path is the other with a prefix of whole segments (a server base path or context path). */
  fun pathsMatch(specPath: String, codePath: String): Boolean {
    val a = normalizePath(specPath)
    val b = normalizePath(codePath)
    // both start with '/', so a suffix match always falls on a segment boundary
    return a == b || (b != "/" && a.endsWith(b)) || (a != "/" && b.endsWith(a))
  }
}
