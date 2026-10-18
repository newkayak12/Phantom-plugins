// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.InheritanceUtil
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.SpringMvcEndpoints
import dev.phantom.spring.core.SpringProjectGate
import org.jetbrains.annotations.NonNls
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * HTTP header names and media types written as string literals in Spring code: mapping annotations, `@RequestHeader`,
 * `@CrossOrigin`, and the header/content-type methods of `HttpHeaders`, `RestClient`, `WebClient`, `ResponseEntity`,
 * MockMvc and the servlet API. Only clear mistakes are reported: a name or type that cannot be valid, or one a single
 * typo away from a standard one. Custom names (`X-Tenant`) and vendor types (`application/vnd.acme+json`) pass.
 */
@NonNls
object HttpLiterals {
  /** Standard and widely used header names, lower case. */
  val HEADERS: Set<String> = """
    accept accept-charset accept-encoding accept-language accept-patch accept-ranges access-control-allow-credentials
    access-control-allow-headers access-control-allow-methods access-control-allow-origin access-control-expose-headers
    access-control-max-age access-control-request-headers access-control-request-method age allow alt-svc authorization
    cache-control clear-site-data connection content-digest content-disposition content-encoding content-language
    content-length content-location content-range content-security-policy content-security-policy-report-only content-type
    cookie cross-origin-embedder-policy cross-origin-opener-policy cross-origin-resource-policy date digest dnt etag expect
    expires forwarded from host idempotency-key if-match if-modified-since if-none-match if-range if-unmodified-since
    keep-alive last-event-id last-modified link location max-forwards origin permissions-policy pragma prefer
    preference-applied proxy-authenticate proxy-authorization range referer referrer-policy refresh retry-after
    sec-websocket-accept sec-websocket-extensions sec-websocket-key sec-websocket-protocol sec-websocket-version server
    server-timing set-cookie sourcemap strict-transport-security te timing-allow-origin traceparent tracestate baggage
    trailer transfer-encoding upgrade upgrade-insecure-requests user-agent vary via warning www-authenticate
    x-content-type-options x-correlation-id x-csrf-token x-xsrf-token x-forwarded-for x-forwarded-host x-forwarded-port
    x-forwarded-prefix x-forwarded-proto x-frame-options x-http-method-override x-real-ip x-request-id x-requested-with
    x-xss-protection x-api-key x-auth-token x-b3-traceid x-b3-spanid x-b3-sampled
  """.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

  /** Canonical spelling for suggestions. */
  private fun canonical(lower: String): String = when (lower) {
    "etag" -> "ETag"
    "te" -> "TE"
    "www-authenticate" -> "WWW-Authenticate"
    "dnt" -> "DNT"
    else -> lower.split('-').joinToString("-") { part -> part.replaceFirstChar(Char::uppercaseChar) }
  }

  /** Registered top-level media types. */
  private val TOP_LEVEL = setOf("application", "audio", "font", "example", "image", "message", "model", "multipart", "text", "video", "*")

  /** Common full types, for one-typo suggestions. */
  private val MEDIA_TYPES = setOf(
    "application/json", "application/xml", "application/x-www-form-urlencoded", "application/octet-stream", "application/pdf",
    "application/problem+json", "application/problem+xml", "application/x-ndjson", "application/hal+json", "application/stream+json",
    "application/merge-patch+json", "application/json-patch+json", "application/vnd.api+json", "application/javascript",
    "application/graphql-response+json", "application/zip", "application/gzip", "application/yaml", "application/cbor",
    "application/x-protobuf", "application/rss+xml", "application/atom+xml", "application/xhtml+xml",
    "multipart/form-data", "multipart/mixed", "multipart/related", "text/plain", "text/html", "text/csv", "text/event-stream",
    "text/xml", "text/javascript", "text/css", "text/markdown", "image/png", "image/jpeg", "image/gif", "image/svg+xml",
    "image/webp", "image/avif", "image/bmp", "image/x-icon", "audio/mpeg", "video/mp4",
  )

  private const val TOKEN_CHARS = "!#$%&'*+-.^_`|~"
  private fun isToken(s: String) = s.isNotEmpty() && s.all { it.isLetterOrDigit() && it.code < 128 || it in TOKEN_CHARS }

  /** A problem with a header name, or null. */
  fun headerProblem(name: String): String? {
    val n = name.trim()
    if (n.isEmpty() || n == "*" || n.contains("\${") || n.contains("#{")) return null
    if (!isToken(n)) return PhantomSpringBundle.message("inspection.http.header.malformed", n)
    val lower = n.lowercase()
    if (lower in HEADERS) return null
    nearHeader(lower)?.let { return PhantomSpringBundle.message("inspection.http.header.typo", n, canonical(it)) }
    return null
  }

  private fun nearHeader(lower: String): String? {
    // Content_Type, ContentType: the same name without its separators
    val squeezed = lower.replace("-", "").replace("_", "")
    HEADERS.firstOrNull { it.replace("-", "") == squeezed && it != lower }?.let { return it }
    if (lower.length < 5) return null
    return HEADERS.firstOrNull { it.length >= 5 && distance(it, lower) == 1 }
  }

  /** A problem with a media type (one entry of `produces`/`consumes`/`Accept`), or null. */
  fun mediaTypeProblem(value: String): String? {
    val v = value.trim().removePrefix("!").trim()
    if (v.isEmpty() || v.contains("\${") || v.contains("#{")) return null
    val essence = v.substringBefore(';').trim()
    if (essence == "*") return null
    val slash = essence.indexOf('/')
    if (slash <= 0 || slash == essence.length - 1 || essence.indexOf('/', slash + 1) >= 0 ||
        !isToken(essence.substring(0, slash)) || !isToken(essence.substring(slash + 1))) {
      return PhantomSpringBundle.message("inspection.http.media.malformed", v)
    }
    val lower = essence.lowercase()
    if (lower in MEDIA_TYPES) return null
    val top = lower.substring(0, slash)
    if (top !in TOP_LEVEL) {
      val near = TOP_LEVEL.filter { it != "*" }.minByOrNull { distance(it, top) }
      val fixed = near?.takeIf { distance(it, top) <= 2 }?.let { it + lower.substring(slash) }
      return if (fixed != null) PhantomSpringBundle.message("inspection.http.media.typo", essence, MEDIA_TYPES.firstOrNull { it == fixed } ?: fixed)
             else PhantomSpringBundle.message("inspection.http.media.unknown.top", essence, top)
    }
    if (lower.length >= 8) MEDIA_TYPES.firstOrNull { distance(it, lower) == 1 }?.let { return PhantomSpringBundle.message("inspection.http.media.typo", essence, it) }
    return null
  }

  /** Edit distance counting a swap of neighbours as one edit (typos like `jsno`); 3 means "far". */
  internal fun distance(a: String, b: String): Int {
    if (kotlin.math.abs(a.length - b.length) > 2) return 3
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length) {
      val cost = if (a[i - 1] == b[j - 1]) 0 else 1
      d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
      if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
    }
    return d[a.length][b.length]
  }

  // ---- where the literals are

  const val REQUEST_HEADER = "org.springframework.web.bind.annotation.RequestHeader"
  const val CROSS_ORIGIN = "org.springframework.web.bind.annotation.CrossOrigin"

  /** Types whose header methods take a header name first. */
  val HEADER_OWNERS = listOf(
    "org.springframework.http.HttpHeaders",
    "org.springframework.web.client.RestClient.RequestHeadersSpec",
    "org.springframework.web.client.RestClient.ResponseSpec",
    "org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec",
    "org.springframework.http.ResponseEntity.HeadersBuilder",
    "org.springframework.http.RequestEntity.HeadersBuilder",
    "org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder",
    "org.springframework.test.web.reactive.server.WebTestClient.RequestHeadersSpec",
    "org.springframework.web.servlet.function.ServerResponse.HeadersBuilder",
    "org.springframework.web.reactive.function.server.ServerResponse.HeadersBuilder",
    "jakarta.servlet.http.HttpServletResponse", "jakarta.servlet.http.HttpServletRequest",
    "javax.servlet.http.HttpServletResponse", "javax.servlet.http.HttpServletRequest",
  )
  val HEADER_METHODS = setOf("header", "set", "add", "setHeader", "addHeader", "getHeader", "getHeaders", "getFirst", "containsHeader", "setDateHeader", "addDateHeader")

  /** Methods taking a media type string. */
  val MEDIA_TYPE_OWNERS = listOf("org.springframework.http.MediaType", "org.springframework.util.MimeTypeUtils", "org.springframework.util.MimeType") + HEADER_OWNERS
  val MEDIA_TYPE_METHODS = setOf("parseMediaType", "parseMediaTypes", "valueOf", "parseMimeType", "contentType", "accept")

  fun isOwner(psiClass: PsiClass?, owners: List<String>): Boolean {
    psiClass ?: return false
    val fqn = psiClass.qualifiedName ?: return false
    return fqn in owners || owners.any { InheritanceUtil.isInheritor(psiClass, it) }
  }

  /** Each string of an annotation value (a literal or an array of them), with the element to report on. */
  fun strings(value: UExpression?): List<Pair<String, PsiElement>> {
    value ?: return emptyList()
    if (value is UCallExpression && (value.kind == UastCallKind.NESTED_ARRAY_INITIALIZER || value.methodName in setOf("arrayOf", "listOf"))) {
      return value.valueArguments.flatMap { strings(it) }
    }
    if (value is UCallExpression && value.kind == UastCallKind.NEW_ARRAY_WITH_INITIALIZER) return value.valueArguments.flatMap { strings(it) }
    val text = value.evaluateString() ?: return emptyList()
    return listOf(text to (value.sourcePsi ?: return emptyList()))
  }
}

/** Malformed or misspelled HTTP header names in Spring code. */
class SpringHttpHeaderInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        when (node.qualifiedName) {
          HttpLiterals.REQUEST_HEADER -> {
            val value = node.findDeclaredAttributeValue("value") ?: node.findDeclaredAttributeValue("name") ?: return true
            HttpLiterals.strings(value).forEach { (name, psi) -> check(name, psi) }
          }
          HttpLiterals.CROSS_ORIGIN -> for (attribute in listOf("allowedHeaders", "exposedHeaders")) {
            HttpLiterals.strings(node.findDeclaredAttributeValue(attribute)).forEach { (name, psi) -> check(name, psi) }
          }
          in SpringMvcEndpoints.MAPPING_ANNOTATIONS -> {
            // headers = {"X-Version=2", "!Debug", "Accept!=text/plain"}
            HttpLiterals.strings(node.findDeclaredAttributeValue("headers")).forEach { (expression, psi) ->
              check(expression.trim().removePrefix("!").substringBefore('=').removeSuffix("!").trim(), psi)
            }
          }
        }
        return true
      }

      override fun visitCallExpression(node: UCallExpression): Boolean {
        if (node.methodName !in HttpLiterals.HEADER_METHODS) return true
        val first = node.valueArguments.firstOrNull() ?: return true
        if (first !is ULiteralExpression && first !is UPolyadicExpression) return true
        val name = first.evaluateString() ?: return true
        val method = node.resolve() ?: return true
        if (!HttpLiterals.isOwner(method.containingClass, HttpLiterals.HEADER_OWNERS)) return true
        check(name, first.sourcePsi ?: return true)
        return true
      }

      private fun check(name: String, psi: PsiElement) {
        val problem = HttpLiterals.headerProblem(name) ?: return
        holder.registerProblem(psi, problem, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
      }
    }, arrayOf(UAnnotation::class.java, UCallExpression::class.java), true)
  }
}

/** Media types that cannot be parsed or are one typo away from a common one, in Spring code. */
class SpringMediaTypeInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        if (node.qualifiedName !in SpringMvcEndpoints.MAPPING_ANNOTATIONS) return true
        for (attribute in listOf("produces", "consumes")) {
          HttpLiterals.strings(node.findDeclaredAttributeValue(attribute)).forEach { (type, psi) -> check(type, psi) }
        }
        return true
      }

      override fun visitCallExpression(node: UCallExpression): Boolean {
        val methodName = node.methodName ?: return true
        val headerValue = methodName in HttpLiterals.HEADER_METHODS && methodName.startsWith("get").not()
        if (methodName !in HttpLiterals.MEDIA_TYPE_METHODS && !headerValue) return true
        val arguments = node.valueArguments
        val literal: UExpression = if (headerValue) {
          // header("Content-Type", "aplication/json")
          val name = arguments.firstOrNull()?.evaluateString()?.lowercase() ?: return true
          if (name != "content-type" && name != "accept") return true
          arguments.getOrNull(1) ?: return true
        }
        else arguments.firstOrNull() ?: return true
        if (literal !is ULiteralExpression && literal !is UPolyadicExpression) return true
        val text = literal.evaluateString() ?: return true
        val method = node.resolve() ?: return true
        if (!HttpLiterals.isOwner(method.containingClass, HttpLiterals.MEDIA_TYPE_OWNERS)) return true
        val psi = literal.sourcePsi ?: return true
        // parseMediaTypes, Accept: a comma-separated list
        val many = methodName == "parseMediaTypes" || methodName == "accept" || headerValue
        (if (many) text.split(',') else listOf(text)).forEach { check(it, psi) }
        return true
      }

      private fun check(type: String, psi: PsiElement) {
        val problem = HttpLiterals.mediaTypeProblem(type) ?: return
        holder.registerProblem(psi, problem, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
      }
    }, arrayOf(UAnnotation::class.java, UCallExpression::class.java), true)
  }
}
