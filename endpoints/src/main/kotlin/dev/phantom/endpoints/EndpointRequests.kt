// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.endpoints

import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * "Generate HTTP Request" in the Endpoints window: appends a request for the endpoint to the scratch file
 * `generated-requests.http` and opens it at the new request. The host part comes from the module's Spring Boot or
 * Ktor configuration (`server.port`, `server.servlet.context-path`, `spring.webflux.base-path`, `server.ssl.enabled`,
 * `ktor.deployment.port`), else `http://localhost:8080`.
 */
@org.jetbrains.annotations.ApiStatus.Internal
object EndpointRequests {
  const val SCRATCH_NAME: String = "generated-requests.http"

  /** `/users/{id}` → `/users/{{id}}`, so the HTTP client asks for the value instead of sending the braces. */
  fun pathTemplate(path: String): String = path.replace(Regex("""\{([^{}/:]+)(:[^{}/]*)?}""")) { "{{" + it.groupValues[1].trim() + "}}" }

  fun requestText(methods: List<String>, path: String, baseUrl: String): String {
    val url = baseUrl.trimEnd('/') + "/" + pathTemplate(path).trimStart('/')
    return methods.ifEmpty { listOf("GET") }.map { if (it == "ANY" || it == "*") "GET" else it }.distinct().joinToString("") { method ->
      buildString {
        append("### ").append(method).append(' ').append(path).append('\n')
        append(method).append(' ').append(url).append('\n')
        if (method in BODY_METHODS) append("Content-Type: application/json\n\n{\n  \n}\n")
        append('\n')
      }
    }
  }

  private val BODY_METHODS = setOf("POST", "PUT", "PATCH")

  /** Call in a read action. */
  fun baseUrl(project: Project, module: Module?): String {
    val modules = buildList {
      if (module != null) {
        add(module)
        addAll(ModuleRootManager.getInstance(module).getDependencies(false))
      }
      addAll(ModuleManager.getInstance(project).modules)
    }.distinct()
    for (m in modules) {
      for (root in ModuleRootManager.getInstance(m).getSourceRoots(false)) {
        configFiles(root).firstNotNullOfOrNull(::fromFile)?.let { return it }
      }
    }
    return DEFAULT
  }

  const val DEFAULT: String = "http://localhost:8080"

  private fun configFiles(root: VirtualFile): List<VirtualFile> =
    listOf("application.properties", "application.yml", "application.yaml", "application.conf").mapNotNull { root.findChild(it) }

  private fun fromFile(file: VirtualFile): String? {
    val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return null
    val values = when (file.extension) {
      "properties" -> ServerConfig.properties(text)
      "conf" -> ServerConfig.hocon(text)
      else -> ServerConfig.yaml(text)
    }
    return ServerConfig.baseUrl(values)
  }

  fun appendAndOpen(project: Project, text: String) {
    val file = ScratchFileService.getInstance().findFile(ScratchRootType.getInstance(), SCRATCH_NAME, ScratchFileService.Option.create_if_missing) ?: return
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return
    var offset = 0
    WriteCommandAction.runWriteCommandAction(project, EndpointsBundle.message("command.generate.request"), null, {
      val separator = if (document.textLength == 0 || document.text.endsWith("\n\n")) "" else if (document.text.endsWith("\n")) "\n" else "\n\n"
      offset = document.textLength + separator.length
      document.insertString(document.textLength, separator + text)
    })
    val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, offset), true) ?: return
    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
  }
}

/** Flattened configuration keys (`server.port`) from the formats Spring Boot and Ktor read, without a YAML library. */
@org.jetbrains.annotations.ApiStatus.Internal
object ServerConfig {
  fun baseUrl(values: Map<String, String>): String? {
    val port = values["server.port"] ?: values["ktor.deployment.port"]
    val context = values["server.servlet.context-path"] ?: values["server.servlet.contextPath"]
                  ?: values["spring.webflux.base-path"] ?: values["spring.webflux.basePath"]
    val ssl = values["server.ssl.enabled"]?.equals("true", ignoreCase = true) == true
    if (port == null && context == null && !ssl) return null
    val scheme = if (ssl) "https" else "http"
    val portPart = port?.takeIf { it.toIntOrNull()?.let { p -> p > 0 } == true }?.let { ":$it" } ?: ":8080"
    val path = context?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
    return "$scheme://localhost$portPart$path"
  }

  /** `${PORT:8081}` → `8081`; a placeholder without a default is unknown. */
  private fun clean(raw: String): String? {
    var v = raw.trim()
    if (v.length >= 2 && (v.first() == '"' && v.last() == '"' || v.first() == '\'' && v.last() == '\'')) v = v.substring(1, v.length - 1)
    Regex("""^\$\{[^:}]+:([^}]*)}$""").find(v)?.let { return it.groupValues[1] }
    if (v.contains("\${")) return null
    return v
  }

  fun properties(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (line in text.lineSequence()) {
      val t = line.trim()
      if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue
      val sep = t.indexOfFirst { it == '=' || it == ':' }
      if (sep <= 0) continue
      clean(t.substring(sep + 1))?.let { out.putIfAbsent(t.substring(0, sep).trim(), it) }
    }
    return out
  }

  /** The first YAML document only: later ones are usually profile-specific (`spring.config.activate.on-profile`). */
  fun yaml(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val stack = ArrayList<Pair<Int, String>>()
    var seenContent = false
    for (line in text.lineSequence()) {
      if (line.startsWith("---")) { if (seenContent) break else continue }
      val content = line.substringBefore(" #").trimEnd()
      if (content.isBlank() || content.trimStart().startsWith("#") || content.trimStart().startsWith("-")) continue
      seenContent = true
      val indent = content.length - content.trimStart().length
      val body = content.trim()
      val colon = body.indexOf(':')
      if (colon <= 0) continue
      while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.size - 1)
      val key = body.substring(0, colon).trim().trim('"', '\'')
      val value = body.substring(colon + 1).trim()
      val path = (stack.map { it.second } + key).joinToString(".")
      if (value.isEmpty() || value == "|" || value == ">") stack.add(indent to key)
      else clean(value)?.let { out.putIfAbsent(path, it) }
    }
    return out
  }

  /** Ktor's `application.conf`: `ktor { deployment { port = 8080 } }`, or the dotted form. */
  fun hocon(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val stack = ArrayList<String>()
    for (raw in text.lineSequence()) {
      var line = raw.substringBefore("//").substringBefore("#").trim()
      while (line.isNotEmpty()) {
        when {
          line.startsWith("}") -> { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); line = line.substring(1).trim() }
          line.endsWith("{") -> { stack.add(line.removeSuffix("{").trim().removeSuffix("=").removeSuffix(":").trim()); line = "" }
          else -> {
            val sep = line.indexOfFirst { it == '=' || it == ':' }
            if (sep > 0) {
              val key = (stack + line.substring(0, sep).trim()).joinToString(".")
              val value = line.substring(sep + 1).trim().removeSuffix("}").trim()
              clean(value)?.let { out.putIfAbsent(key, it) }
              if (line.endsWith("}") && stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
            line = ""
          }
        }
      }
    }
    return out
  }
}