// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core.exec

import com.intellij.openapi.util.text.StringUtil
import dev.phantom.http.core.*
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.Duration

/** A request with variables substituted: no PSI, safe to hand to another thread. */
data class HttpRequestModel(
  val name: String,
  val method: String,
  val uri: URI,
  val version: HttpClient.Version,
  val headers: List<Pair<String, String>>,
  val body: List<BodyPart>,
  val followRedirects: Boolean,
  val useCookies: Boolean,
  val timeout: Duration?,
  val connectTimeout: Duration?,
  val handlers: List<HttpHandlerSource> = emptyList(),
) {
  val hasHandlers: Boolean get() = handlers.isNotEmpty()

  sealed interface BodyPart {
    data class Text(val text: String) : BodyPart
    data class File(val path: Path) : BodyPart
  }

  val requestLine: String get() = "$method $uri"
}

class HttpRequestBuildException(message: String) : Exception(message)

/** PSI → [HttpRequestModel]. Call inside a read action. */
object HttpRequestBuilder {
  fun build(block: HttpRequestBlock, resolver: HttpVariables.Resolver): HttpRequestModel {
    val line = block.requestLine ?: throw HttpRequestBuildException(PhantomHttpBundle.message("http.run.no.request"))
    val rawUrl = line.target?.let(::targetText).orEmpty()
    val url = resolver.substitute(rawUrl).trim()
    val headers = block.headers.filter { it.headerName.isNotEmpty() }.map { it.headerName to resolver.substitute(headerValue(it)) }
    val contentType = headers.lastOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second.orEmpty()
    val body = block.body?.let { bodyParts(it, resolver, contentType.startsWith("multipart/", ignoreCase = true)) }.orEmpty()
    if (resolver.unresolved.isNotEmpty()) {
      throw HttpRequestBuildException(PhantomHttpBundle.message("http.run.unresolved", resolver.unresolved.joinToString(", ")))
    }
    if (url.isEmpty()) throw HttpRequestBuildException(PhantomHttpBundle.message("http.run.no.url"))
    val uri = try {
      URI(if (url.contains("://")) url else "http://$url")
    }
    catch (e: Exception) {
      throw HttpRequestBuildException(PhantomHttpBundle.message("http.run.bad.url", url, e.message ?: ""))
    }
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty()) {
      throw HttpRequestBuildException(PhantomHttpBundle.message("http.run.bad.url", url, uri.scheme ?: ""))
    }
    return HttpRequestModel(
      name = block.presentableName,
      method = line.method,
      uri = uri,
      version = if (line.httpVersion?.startsWith("HTTP/2") == true) HttpClient.Version.HTTP_2 else HttpClient.Version.HTTP_1_1,
      headers = headers,
      body = body,
      followRedirects = block.directive("no-redirect") == null,
      useCookies = block.directive("no-cookie-jar") == null,
      timeout = block.directive("timeout")?.let { duration(it.argument) },
      connectTimeout = block.directive("connection-timeout")?.let { duration(it.argument) },
      handlers = block.handlers.mapNotNull(::handlerSource),
    )
  }

  /** The script of `> {% ... %}`, or the text of the file `> handler.js` names (relative to the `.http` file). */
  private fun handlerSource(handler: HttpHandler): HttpHandlerSource? {
    val file = handler.containingFile
    val fileName = file.originalFile.virtualFile?.name ?: file.name
    val fileText = file.text
    val fileRef = handler.node.findChildByType(HttpTokens.HANDLER_FILE)
    if (fileRef != null) {
      val name = fileRef.text.removePrefix(">").trim()
      val dir = file.originalFile.virtualFile?.parent
      val script = (if (java.nio.file.Path.of(name).isAbsolute) com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(name) else dir?.findFileByRelativePath(name))
                   ?: throw HttpRequestBuildException(PhantomHttpBundle.message("http.run.handler.missing", name))
      return HttpHandlerSource(com.intellij.openapi.vfs.VfsUtilCore.loadText(script), 1, script.name)
    }
    val scripts = handler.node.getChildren(null).filter { it.elementType == HttpTokens.SCRIPT }
    val first = scripts.firstOrNull() ?: return null
    val end = handler.node.findChildByType(HttpTokens.HANDLER_END)?.startOffset ?: (scripts.last().startOffset + scripts.last().textLength)
    val text = fileText.substring(first.startOffset, end)
    return HttpHandlerSource(text, StringUtil.offsetToLineNumber(fileText, first.startOffset) + 1, fileName)
  }

  /** URL text without the whitespace that separates continuation lines. */
  private fun targetText(target: HttpRequestTarget): String {
    val sb = StringBuilder()
    var child = target.node.firstChildNode
    while (child != null) {
      if (!StringUtil.isEmptyOrSpaces(child.text)) sb.append(child.text)
      child = child.treeNext
    }
    return sb.toString()
  }

  private fun headerValue(header: HttpHeader): String {
    val colon = header.node.findChildByType(HttpTokens.COLON) ?: return ""
    return header.text.substring(colon.startOffsetInParent + 1).trim()
  }

  private fun bodyParts(body: HttpBody, resolver: HttpVariables.Resolver, multipart: Boolean): List<HttpRequestModel.BodyPart> {
    val parts = ArrayList<HttpRequestModel.BodyPart>()
    val text = StringBuilder()
    fun flush() {
      if (text.isEmpty()) return
      var s = resolver.substitute(text.toString())
      if (multipart) s = s.replace("\r\n", "\n").replace("\n", "\r\n")
      parts.add(HttpRequestModel.BodyPart.Text(s))
      text.setLength(0)
    }
    val dir = body.containingFile.originalFile.virtualFile?.parent?.let { runCatching { it.toNioPath() }.getOrNull() }
    var child = body.node.firstChildNode
    while (child != null) {
      if (child.elementType == HttpTokens.INPUT_FILE) {
        flush()
        val name = resolver.substitute(child.text.removePrefix("<").trim())
        val path = Path.of(name).let { if (it.isAbsolute || dir == null) it else dir.resolve(it).normalize() }
        parts.add(HttpRequestModel.BodyPart.File(path))
        // the line break after `< file` belongs to the file part, not to the next text
        child = child.treeNext
        if (child != null && StringUtil.isEmptyOrSpaces(child.text)) {
          val rest = child.text.substringAfter('\n', "")
          if (rest.isNotEmpty()) text.append(rest)
          if (multipart) text.insert(0, "\n")
          child = child.treeNext
        }
        continue
      }
      text.append(child.text)
      child = child.treeNext
    }
    flush()
    return parts
  }

  /** `5`, `5 s`, `500 ms`, `2 m`. Plain numbers are seconds. */
  internal fun duration(text: String): Duration? {
    val m = Regex("""^(\d+)\s*(ms|s|m)?$""").find(text.trim()) ?: return null
    val n = m.groupValues[1].toLong()
    return when (m.groupValues[2]) {
      "ms" -> Duration.ofMillis(n)
      "m" -> Duration.ofMinutes(n)
      else -> Duration.ofSeconds(n)
    }
  }
}
