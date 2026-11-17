// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.annotations.NonNls

/** Checks of `.http` files; each is its own inspection so it can be turned off on its own. */
abstract class HttpInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    if (holder.file !is HttpRequestFile) return PsiElementVisitor.EMPTY_VISITOR
    return object : PsiElementVisitor() {
      override fun visitElement(element: PsiElement) = check(element, holder)
    }
  }

  protected abstract fun check(element: PsiElement, holder: ProblemsHolder)

  protected fun ProblemsHolder.report(element: PsiElement, range: TextRange?, key: String, vararg params: Any,
                                      type: ProblemHighlightType = ProblemHighlightType.GENERIC_ERROR_OR_WARNING) {
    registerProblem(element, PhantomHttpBundle.message(key, *params), type, range)
  }
}

/** `Content-Length`, `Host`, `Connection`, `Expect`, `Upgrade`: the HTTP client sets them itself and drops what the file says. */
class HttpRestrictedHeaderInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    if (element !is HttpHeader) return
    val name = element.headerName
    if (RESTRICTED.any { it.equals(name, ignoreCase = true) }) holder.report(element, nameRange(element), "http.inspection.restricted.header", name)
  }

  companion object {
    @NonNls private val RESTRICTED = listOf("Content-Length", "Host", "Connection", "Expect", "Upgrade")
  }
}

/**
 * A request line whose first word is not one of the methods the file format knows (`FETCH /x`, `get /x`): it is read as
 * part of the URL and the request goes out as GET.
 */
class HttpCustomMethodInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    if (element !is HttpRequestLine || element.node.findChildByType(HttpTokens.METHOD) != null) return
    val target = element.target ?: return
    val word = firstLineWords(target).takeIf { it.size > 1 }?.first() ?: return
    if (!word.second.matches(Regex("[A-Za-z][A-Za-z-]*")) || word.second.contains("{{")) return
    val upper = word.second.uppercase()
    val key = if (upper in HttpTokens.METHODS) "http.inspection.method.case" else "http.inspection.method.custom"
    holder.report(target, word.first, key, word.second, upper)
  }
}

/** Spaces inside the URL of the request line: the parts are joined without them, so `/a b` is sent as `/ab`. */
class HttpWhitespaceInTargetInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    if (element !is HttpRequestTarget) return
    val line = element.parent as? HttpRequestLine ?: return
    val customMethod = line.node.findChildByType(HttpTokens.METHOD) == null
    var lineStart = 0
    for ((index, lineText) in maskVariableSpaces(element.text).split('\n').withIndex()) {
      val words = WORD.findAll(lineText).toList()
      // the first word of a request line without a known method is the method (reported by the custom method check)
      val from = if (index == 0 && customMethod && words.size > 1) 1 else 0
      for (i in from + 1 until words.size) {
        val gap = TextRange(lineStart + words[i - 1].range.last + 1, lineStart + words[i].range.first)
        holder.report(element, gap, "http.inspection.target.whitespace")
      }
      lineStart += lineText.length + 1
    }
  }

  private companion object {
    val WORD = Regex("""\S+""")
  }
}

/** A JSON content type with a body that is not JSON. `{{variables}}` count as values. */
class HttpJsonBodyInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    if (element !is HttpBody) return
    val block = element.parent as? HttpRequestBlock ?: return
    val contentType = block.headers.lastOrNull { it.headerName.equals("Content-Type", ignoreCase = true) }?.let(::headerValue) ?: return
    if (contentType.contains("{{") || !isJson(contentType)) return
    if (element.node.findChildByType(HttpTokens.INPUT_FILE) != null) return
    val text = element.text
    if (text.isBlank()) return
    val error = JsonCheck.firstError(maskVariables(text)) ?: return
    val at = error.first.coerceIn(0, text.length - 1)
    val end = (at + 1).coerceAtMost(text.length)
    holder.report(element, TextRange(at, end), "http.inspection.json.body", error.second)
  }

  private fun isJson(contentType: String): Boolean {
    val mime = contentType.substringBefore(';').trim().lowercase()
    return mime == "application/json" || mime.endsWith("+json")
  }

  /** `{{name}}` becomes `0` padded with spaces, so offsets stay the same and the reference is a valid value, quoted or not. */
  private fun maskVariables(text: String): String = VARIABLE.replace(text) { m -> "0" + " ".repeat(m.value.length - 1) }

  private companion object {
    val VARIABLE = Regex("""\{\{.*?}}""")
  }
}

/** A body line that reads like a request line: the `###` before the next request is probably missing. */
class HttpMissingSeparatorInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    if (element !is HttpBody) return
    var offset = 0
    for (line in element.text.split('\n')) {
      val trimmed = line.trim()
      if (REQUEST_LINE.matches(trimmed)) {
        val start = offset + line.indexOf(trimmed)
        holder.report(element, TextRange(start, start + trimmed.length), "http.inspection.missing.separator")
      }
      offset += line.length + 1
    }
  }

  private companion object {
    val REQUEST_LINE = Regex("""(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|CONNECT)\s+(https?://|/|\{\{)\S*(\s+HTTP/[\d.]+)?""")
  }
}

/** `{{}}` names nothing, and a `{{` without `}}` on its line is sent as written. */
class HttpPlaceholderInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    when {
      element is HttpVariableRef -> if (element.variableName.isEmpty()) holder.report(element, null, "http.inspection.placeholder.empty")
      element.node.elementType in TEXT_TOKENS -> {
        var i = element.text.indexOf("{{")
        while (i >= 0) {
          holder.report(element, TextRange(i, i + 2), "http.inspection.placeholder.unclosed", "{{", "}}")
          i = element.text.indexOf("{{", i + 2)
        }
      }
    }
  }

  private companion object {
    val TEXT_TOKENS = setOf(HttpTokens.URL_TEXT, HttpTokens.HEADER_VALUE, HttpTokens.VALUE_TEXT)
  }
}

/** A header line without `:`, or a name with characters a header name cannot have. */
class HttpInvalidHeaderInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    if (element !is HttpHeader) return
    val name = element.headerName
    when {
      element.node.findChildByType(HttpTokens.COLON) == null -> holder.report(element, null, "http.inspection.header.no.colon")
      name.isEmpty() -> holder.report(element, null, "http.inspection.header.no.name")
      !name.contains("{{") && !name.all(::isTokenChar) -> holder.report(element, nameRange(element), "http.inspection.header.invalid.name", name)
    }
  }

}

/** A header name none of the HTTP specifications or common extensions define: often a typo (`Content-Tyep`). */
class HttpUnknownHeaderInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    if (element !is HttpHeader || element.node.findChildByType(HttpTokens.COLON) == null) return
    val name = element.headerName
    if (name.isEmpty() || name.contains("{{") || name.startsWith("X-", ignoreCase = true) || !name.all(::isTokenChar)) return
    if (name.lowercase() in KNOWN) return
    holder.report(element, nameRange(element), "http.inspection.header.unknown", name, type = ProblemHighlightType.WEAK_WARNING)
  }

  companion object {
    @NonNls val KNOWN: Set<String> = """
      Accept Accept-Charset Accept-Encoding Accept-Language Accept-Datetime Accept-Patch Accept-Ranges Access-Control-Request-Method
      Access-Control-Request-Headers Access-Control-Allow-Origin Access-Control-Allow-Credentials Access-Control-Allow-Headers
      Access-Control-Allow-Methods Access-Control-Expose-Headers Access-Control-Max-Age Age Allow Alt-Svc Authorization Cache-Control
      Connection Content-Disposition Content-Encoding Content-Language Content-Length Content-Location Content-MD5 Content-Range
      Content-Security-Policy Content-Type Cookie Date DNT Digest ETag Early-Data Expect Expires Forwarded From Host If-Match
      If-Modified-Since If-None-Match If-Range If-Unmodified-Since Idempotency-Key Keep-Alive Last-Modified Link Location
      Max-Forwards Origin Pragma Prefer Preference-Applied Priority Proxy-Authenticate Proxy-Authorization Range Referer
      Referrer-Policy Retry-After Sec-Fetch-Dest Sec-Fetch-Mode Sec-Fetch-Site Sec-Fetch-User Sec-WebSocket-Key
      Sec-WebSocket-Version Sec-WebSocket-Protocol Sec-WebSocket-Extensions Server Set-Cookie Strict-Transport-Security TE
      Trailer Transfer-Encoding Upgrade Upgrade-Insecure-Requests User-Agent Vary Via WWW-Authenticate Warning Want-Digest
      Repr-Digest Content-Digest Want-Content-Digest Signature Signature-Input Traceparent Tracestate Baggage Request-Id
      Correlation-Id Front-End-Https Proxy-Connection Save-Data Device-Memory Downlink ECT RTT Viewport-Width Width DPR
      Sec-CH-UA Sec-CH-UA-Mobile Sec-CH-UA-Platform Timing-Allow-Origin Last-Event-ID Ping-From Ping-To Purpose Sec-Purpose
      Service-Worker Accept-Post Clear-Site-Data Cross-Origin-Embedder-Policy Cross-Origin-Opener-Policy
      Cross-Origin-Resource-Policy Permissions-Policy NEL Report-To Reporting-Endpoints Server-Timing SourceMap
      Content-Security-Policy-Report-Only Expect-CT Large-Allocation Refresh Status Grpc-Timeout Grpc-Encoding
      Grpc-Accept-Encoding Api-Key Apikey B3 Ocp-Apim-Subscription-Key
    """.trim().split(Regex("""\s+""")).map { it.lowercase() }.toSet()
  }
}

/**
 * `http://` to a host that is not this machine or a private network: the request and its credentials travel unencrypted.
 * Single-label hosts (`api`, `mysql`) count as local, as they are usually container or service names.
 */
class HttpInsecureUrlInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    val text = when (element) {
      is HttpRequestTarget -> element.text
      is HttpFileVariable -> element.text
      else -> return
    }
    val m = HTTP_URL.find(text) ?: return
    val host = m.groupValues[1]
    if (host.contains("{{") || isLocal(host.lowercase())) return
    holder.report(element, TextRange(m.range.first, m.range.first + "http://".length), "http.inspection.insecure.url", host,
                  type = ProblemHighlightType.WEAK_WARNING)
  }

  private fun isLocal(host: String): Boolean {
    if (host.isEmpty() || host.startsWith("[")) return true // IPv6 literals: leave them
    if (!host.contains('.')) return true
    if (host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".internal") || host.endsWith(".test") ||
        host.endsWith(".lan") || host.endsWith(".home.arpa") || host.endsWith(".svc") || host.contains(".svc.")) return true
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }
    if (octets.size != 4) return false
    val (a, b) = octets
    return a == 127 || a == 10 || a == 0 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
  }

  private companion object {
    val HTTP_URL = Regex("""(?<![\w])http://(\[[^\]]*]|[^/:?#\s]*)""", RegexOption.IGNORE_CASE)
  }
}

/** RFC 9110 token characters. */
private fun isTokenChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'*+-.^_`|~"

private fun nameRange(header: HttpHeader): TextRange? =
  header.node.findChildByType(HttpTokens.HEADER_NAME)?.let { TextRange.from(it.startOffsetInParent, it.text.trimEnd().length) }

private fun headerValue(header: HttpHeader): String {
  val colon = header.node.findChildByType(HttpTokens.COLON) ?: return ""
  return header.text.substring(colon.startOffsetInParent + 1).trim()
}

/** Words of the first line of a request target, with their ranges inside it. */
private fun firstLineWords(target: HttpRequestTarget): List<Pair<TextRange, String>> {
  val firstLine = maskVariableSpaces(target.text).substringBefore('\n')
  return Regex("""\S+""").findAll(firstLine).map { TextRange(it.range.first, it.range.last + 1) to it.value }.toList()
}

/** `{{ name }}` with its inner spaces replaced, so the reference counts as one word. */
private fun maskVariableSpaces(text: String): String = Regex("""\{\{.*?}}""").replace(text) { m -> m.value.replace(' ', '_').replace('\t', '_') }

/** A strict JSON (RFC 8259) syntax check that reports the offset of the first error. */
object JsonCheck {
  fun firstError(text: String): Pair<Int, String>? = try {
    val p = Parser(text)
    p.skipSpace()
    p.value()
    p.skipSpace()
    if (p.i < text.length) throw Failure(p.i, "Unexpected text after the JSON value")
    null
  }
  catch (f: Failure) {
    f.offset to (f.message ?: "")
  }

  private class Failure(val offset: Int, message: String) : Exception(message, null, false, false)

  private class Parser(val s: String) {
    var i = 0

    fun skipSpace() {
      while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    fun value() {
      if (i >= s.length) throw Failure(i, "Value expected")
      when (s[i]) {
        '{' -> obj()
        '[' -> array()
        '"' -> string()
        't' -> word("true")
        'f' -> word("false")
        'n' -> word("null")
        else -> if (s[i] == '-' || s[i].isDigit()) number() else throw Failure(i, "Value expected")
      }
    }

    private fun obj() {
      i++
      skipSpace()
      if (i < s.length && s[i] == '}') { i++; return }
      while (true) {
        skipSpace()
        if (i >= s.length || s[i] != '"') throw Failure(i, if (i < s.length && s[i] == '}') "Trailing comma" else "Property name in double quotes expected")
        string()
        skipSpace()
        if (i >= s.length || s[i] != ':') throw Failure(i, "':' expected")
        i++
        skipSpace()
        value()
        skipSpace()
        if (i < s.length && s[i] == ',') { i++; continue }
        if (i < s.length && s[i] == '}') { i++; return }
        throw Failure(i, "',' or '}' expected")
      }
    }

    private fun array() {
      i++
      skipSpace()
      if (i < s.length && s[i] == ']') { i++; return }
      while (true) {
        skipSpace()
        if (i < s.length && s[i] == ']') throw Failure(i, "Trailing comma")
        value()
        skipSpace()
        if (i < s.length && s[i] == ',') { i++; continue }
        if (i < s.length && s[i] == ']') { i++; return }
        throw Failure(i, "',' or ']' expected")
      }
    }

    private fun string() {
      val start = i
      i++
      while (i < s.length) {
        when (s[i]) {
          '"' -> { i++; return }
          '\\' -> {
            if (i + 1 >= s.length) break
            val e = s[i + 1]
            if (e == 'u') {
              if (i + 6 > s.length || !s.substring(i + 2, i + 6).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) throw Failure(i, "Invalid \\u escape")
              i += 6
            }
            else if (e in "\"\\/bfnrt") i += 2
            else throw Failure(i, "Invalid escape '\\$e'")
          }
          '\n' -> throw Failure(i, "Line break inside a string")
          else -> i++
        }
      }
      throw Failure(start, "Unclosed string")
    }

    private fun word(w: String) {
      if (!s.startsWith(w, i)) throw Failure(i, "Value expected")
      i += w.length
    }

    private fun number() {
      val m = NUMBER.find(s, i)?.takeIf { it.range.first == i } ?: throw Failure(i, "Invalid number")
      i = m.range.last + 1
    }

    companion object {
      val NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")
    }
  }
}

/** The same file imported twice: the second line adds nothing. */
class HttpDuplicateImportInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    if (element !is HttpImport) return
    val file = element.containingFile as? HttpRequestFile ?: return
    val key = importKey(element) ?: return
    val first = file.imports.firstOrNull { importKey(it) == key } ?: return
    if (first != element) {
      holder.report(element, element.pathRange, "http.inspection.import.duplicate", element.path, type = ProblemHighlightType.LIKE_UNUSED_SYMBOL)
    }
  }

  /** The file it resolves to, or the written path when it resolves to nothing. */
  private fun importKey(import: HttpImport): String? = import.importedFile?.path ?: import.path.removePrefix("./").takeIf { it.isNotEmpty() }
}

/** `import` or `run` naming a file that does not exist (next to the `.http` file, or as an absolute path). */
class HttpUnresolvedImportInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    when (element) {
      is HttpImport -> if (element.path.isNotEmpty() && element.importedFile == null) {
        holder.report(element, element.pathRange, "http.inspection.import.unresolved", element.path)
      }
      is HttpRun -> {
        val path = element.path ?: return
        if (path.isNotEmpty() && !path.contains("{{") && element.targetFile == null) {
          holder.report(element, element.targetRange, "http.inspection.import.unresolved", path)
        }
      }
    }
  }
}

/** `run #name` where neither this file nor the files it imports has a request of that name. */
class HttpRunRequestNameInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    if (element !is HttpRun) return
    val name = element.requestName ?: return
    val file = element.containingFile as? HttpRequestFile ?: return
    if (name.isEmpty()) {
      holder.report(element, element.targetRange, "http.inspection.run.name.empty")
    }
    else if (HttpImports.findRequest(file, name) == null) {
      holder.report(element, element.targetRange, "http.inspection.run.name.unknown", name)
    }
  }
}

/** `{{$auth.token("id")}}` naming an auth id that no `Security.Auths` of the env files defines for the environment. */
class HttpUnresolvedAuthIdInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    if (element !is HttpVariableRef) return
    val id = HttpVariables.authId(element.variableName) ?: return
    if (id in HttpVariables.authIds(element.containingFile)) return
    holder.report(element, null, "http.inspection.auth.unknown", id)
  }
}

/**
 * A request URL with a scheme this client does not speak: `ws://`/`wss://` (WebSocket) or anything other than
 * `http`/`https`. Such a request fails when it is run.
 */
class HttpProtocolInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    if (element !is HttpRequestTarget) return
    val text = element.text
    val m = SCHEME.find(text) ?: return
    val scheme = m.groupValues[1].lowercase()
    if (scheme == "http" || scheme == "https") return
    val range = TextRange(m.range.first, m.range.first + m.groupValues[1].length)
    if (scheme == "ws" || scheme == "wss") holder.report(element, range, "http.inspection.protocol.websocket", m.groupValues[1])
    else holder.report(element, range, "http.inspection.protocol.unknown", m.groupValues[1])
  }

  private companion object {
    val SCHEME = Regex("""^\s*([A-Za-z][A-Za-z0-9+.-]*)://""")
  }
}

/**
 * Characters a URL cannot hold as written (`|`, `"`, `<`, `>`, `\`, `^`, `` ` ``, `{`, `}`) and a `%` that does not start
 * a `%XX` escape. They are not encoded for you, so the request is rejected before it is sent; `{{variables}}` are skipped.
 */
class HttpAmbiguousEncodingInspection : HttpInspection() {
  override fun check(element: PsiElement, holder: ProblemsHolder) {
    if (element !is HttpRequestTarget) return
    val text = VARIABLE.replace(element.text) { m -> "x".repeat(m.value.length) }
    for ((i, c) in text.withIndex()) {
      when {
        c == '%' -> if (!isHex(text.getOrNull(i + 1)) || !isHex(text.getOrNull(i + 2))) {
          holder.report(element, TextRange(i, i + 1), "http.inspection.encoding.percent")
        }
        c in ILLEGAL -> holder.report(element, TextRange(i, i + 1), "http.inspection.encoding.char", c.toString(), "%%%02X".format(c.code))
      }
    }
  }

  private fun isHex(c: Char?): Boolean = c != null && (c.isDigit() || c.lowercaseChar() in 'a'..'f')

  private companion object {
    val VARIABLE = Regex("""\{\{.*?}}""")
    const val ILLEGAL = "|\"<>\\^`{}"
  }
}

/**
 * `Security.Auths` entries of `http-client.env.json` / `http-client.private.env.json` that cannot get a token: no
 * `Type`, an OAuth2 entry without `Grant Type` or with one that does not exist, and settings the grant type needs
 * (`Client ID`, `Token URL`, `Auth URL`, `Device Auth URL`, `Username`/`Password`). Values with `{{variables}}` count as set.
 */
class HttpAuthConfigurationInspection : com.intellij.codeInspection.LocalInspectionTool() {
  override fun checkFile(file: com.intellij.psi.PsiFile, manager: com.intellij.codeInspection.InspectionManager, isOnTheFly: Boolean): Array<com.intellij.codeInspection.ProblemDescriptor>? {
    if (file.name != HttpEnvironmentService.PUBLIC_ENV && file.name != HttpEnvironmentService.PRIVATE_ENV) return null
    val text = file.text
    val root = try { kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject } catch (_: Exception) { null } ?: return null
    val out = ArrayList<com.intellij.codeInspection.ProblemDescriptor>()
    var searchFrom = 0
    for ((_, env) in root) {
      val auths = ((env as? kotlinx.serialization.json.JsonObject)?.get("Security") as? kotlinx.serialization.json.JsonObject)?.get("Auths") as? kotlinx.serialization.json.JsonObject ?: continue
      val authsAt = text.indexOf("\"Auths\"", searchFrom).takeIf { it >= 0 } ?: break
      searchFrom = authsAt + 1
      for ((id, value) in auths) {
        val config = value as? kotlinx.serialization.json.JsonObject ?: continue
        val idAt = text.indexOf("\"" + id + "\"", authsAt)
        val range = if (idAt >= 0) TextRange(idAt, idAt + id.length + 2) else TextRange(authsAt, authsAt + 7)
        for (message in problems(config)) {
          out += manager.createProblemDescriptor(file, range, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly)
        }
      }
    }
    return out.toTypedArray()
  }

  private fun problems(config: kotlinx.serialization.json.JsonObject): List<String> {
    fun value(key: String): String? = (config[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    val type = value("Type") ?: return listOf(PhantomHttpBundle.message("http.inspection.auth.missing", "Type"))
    if (!type.equals("OAuth2", ignoreCase = true)) return emptyList()
    val grant = value("Grant Type") ?: return listOf(PhantomHttpBundle.message("http.inspection.auth.missing", "Grant Type"))
    if (grant.contains("{{")) return emptyList()
    val required = GRANTS.entries.firstOrNull { it.key.equals(grant, ignoreCase = true) }?.value
                   ?: return listOf(PhantomHttpBundle.message("http.inspection.auth.grant", grant, GRANTS.keys.joinToString(", ")))
    return (listOf("Client ID") + required).filter { value(it) == null }.map { PhantomHttpBundle.message("http.inspection.auth.missing.for", it, grant) }
  }

  private companion object {
    val GRANTS = linkedMapOf(
      "Authorization Code" to listOf("Auth URL", "Token URL"),
      "Client Credentials" to listOf("Token URL"),
      "Device Authorization" to listOf("Device Auth URL", "Token URL"),
      "Implicit" to listOf("Auth URL"),
      "Password" to listOf("Token URL", "Username", "Password"),
    )
  }
}
