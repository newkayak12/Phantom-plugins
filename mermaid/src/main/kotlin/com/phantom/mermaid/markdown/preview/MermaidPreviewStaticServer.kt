// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.phantom.mermaid.markdown.preview

import com.phantom.mermaid.markdown.jcef.MermaidBrowserExtension
import com.phantom.mermaid.markdown.jcef.determineMermaidTheme
import com.phantom.mermaid.markdown.preview.MermaidPreviewStaticServer.Companion.guessContentType
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.Urls
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.FileResponses
import org.jetbrains.io.send
import java.nio.ByteBuffer
import java.lang.ref.SoftReference
import java.nio.file.Files
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

internal class MermaidPreviewStaticServer : HttpRequestHandler() {
  private fun obtainStaticPath(path: String): String {
    return path.split('/').drop(2).joinToString(separator = "/")
  }

  override fun isSupported(request: FullHttpRequest): Boolean {
    if (!super.isSupported(request)) {
      return false
    }
    val path = request.uri()
    return path.startsWith(prefixPath)
  }

  // TODO: Add CSP
  private fun buildIndexContent(): String {
    // language=HTML
    val content = """
    <!--suppress HtmlUnknownTarget -->
    <html lang="en">
      <head>
        <title>Mermaid Diagram Preview</title>
        <link rel="stylesheet" href="index.css">
        <link rel="stylesheet" href="mermaid.css">
        <script src="mermaid-theme.js"></script>
        <script src="mermaid.js"></script>
      </head>
      <body>
        <div class="language-mermaid">
          <div id="diagram-container" class="mermaid"></div>
        </div>
      </body>
    </html>
    """.trimIndent()
    return content
  }

  /** The page and the theme files, made for the current theme on each request; null for the files in the plugin. */
  private fun generatedResource(name: String): ByteArray? {
    return when (name) {
      "index.html" -> buildIndexContent().toByteArray()
      "index.css" -> PreviewThemeStyles.createStylesheet().toByteArray()
      "mermaid-theme.js" -> """window["mermaidTheme"] = "${determineMermaidTheme()}";""".toByteArray()
      else -> null
    }
  }

  override fun process(
    urlDecoder: QueryStringDecoder,
    request: FullHttpRequest,
    context: ChannelHandlerContext
  ): Boolean {
    val path = urlDecoder.path()
    check(path.startsWith(prefixPath)) { "prefix should have been checked by #isSupported" }
    val resourceName = obtainStaticPath(path)
    val generated = generatedResource(resourceName)
    // Phantom: a file of the plugin the browser already has is answered before it is read (mermaid.js is 5 MB), and
    // the theme files, which change with the IDE theme, are never answered from the browser's cache
    if (generated == null && !isDevelopmentBuild() && FileResponses.checkCache(request, context.channel(), staticResourcesStamp)) {
      return true
    }
    val content = generated ?: obtainWebApplicationResource(resourceName)
    if (content == null) {
      thisLogger().warn("Failed to provide $path")
      return false
    }
    sendResource(
      request,
      context.channel(),
      ByteBuffer.wrap(content),
      guessContentType(resourceName),
      lastModified = if (generated == null) staticResourcesStamp else null,
    )
    return true
  }

  companion object {
    @Suppress("ConstPropertyName")
    private const val endpointPrefix = "mermaidPreview"

    @Suppress("ConstPropertyName")
    private const val prefixPath = "/$endpointPrefix"

    @JvmStatic
    fun getInstance(): MermaidPreviewStaticServer {
      val point = EP_NAME
      return point.findExtension(MermaidPreviewStaticServer::class.java) ?: error("Could not get server instance!")
    }

    @JvmStatic
    fun obtainStaticIndexUrl(): String {
      val port = BuiltInServerManager.getInstance().port
      val raw = "http://localhost:$port/$endpointPrefix/index.html?isStandaloneViewer=true"
      val url = Urls.parseEncoded(raw)
      requireNotNull(url) { "Could not parse url!" }
      return BuiltInServerManager.getInstance().addAuthToken(url).toExternalForm()
    }

    /**
     * The types for which `";charset=utf-8"` will be appended (only if guessed by [guessContentType]).
     */
    private val typesForExplicitUtfCharset = arrayOf(
      "application/javascript",
      "text/html",
      "text/css",
      "image/svg+xml"
    )

    private fun guessContentType(resourceName: String): String {
      val type = FileResponses.getContentType(resourceName)
      return when (type) {
        in typesForExplicitUtfCharset -> "$type; charset=utf-8"
        else -> type
      }
    }

    /** Phantom: read from the plugin once and kept while memory allows, not read again for every preview. */
    private val webApplicationResources = ConcurrentHashMap<String, SoftReference<ByteArray>>()

    internal fun obtainWebApplicationResource(name: String): ByteArray? {
      webApplicationResources[name]?.get()?.let { return it }
      val cls = MermaidBrowserExtension::class.java
      val bytes = cls.getResourceAsStream(name)?.use { it.readAllBytes() } ?: return null
      webApplicationResources[name] = SoftReference(bytes)
      return bytes
    }

    /**
     * Phantom: when the plugin's files last changed. The IDE build date (used before) stays the same when the plugin is
     * updated, so the browser kept the old mermaid.js.
     */
    private val staticResourcesStamp: Long by lazy {
      val plugin = PluginManagerCore.getPlugin(PluginId.getId("com.phantom.mermaid"))
      plugin?.pluginPath?.let { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrNull() }
        ?: ApplicationInfo.getInstance().buildDate.timeInMillis
    }

    private fun isDevelopmentBuild(): Boolean {
      return System.getProperty("developmentBuild").toBoolean()
    }

    private fun sendResource(request: HttpRequest, channel: Channel, resource: ByteBuffer?, contentType: String, lastModified: Long?) {
      if (resource == null) {
        HttpResponseStatus.NOT_FOUND.send(channel, request)
        return
      }
      val response = DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1,
        HttpResponseStatus.OK,
        Unpooled.wrappedBuffer(resource)
      )
      with(response) {
        headers()[HttpHeaderNames.CONTENT_TYPE] = contentType
        if (lastModified != null) {
          headers()[HttpHeaderNames.CACHE_CONTROL] = "no-cache"
          headers()[HttpHeaderNames.LAST_MODIFIED] = Date(lastModified)
        }
        else {
          headers()[HttpHeaderNames.CACHE_CONTROL] = "no-store"
        }
        send(channel, request)
      }
    }
  }
}