// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.usage

import dev.phantom.ai.core.bestEffort
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Performs GET and returns (status, body). Injected in tests; no network. */
fun interface UsageHttp {
  fun get(url: String, headers: Map<String, String>): HttpReply
}

data class HttpReply(val status: Int, val body: String)

object JdkUsageHttp : UsageHttp {
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override fun get(url: String, headers: Map<String, String>): HttpReply {
    val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET()
    headers.forEach { (k, v) -> b.header(k, v) }
    val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    return HttpReply(r.statusCode(), r.body())
  }
}

/** Polls https://api.anthropic.com/api/oauth/usage with the OAuth bearer token. */
class ClaudeUsageClient(private val http: UsageHttp = JdkUsageHttp, private val now: () -> Long = System::currentTimeMillis) {
  /** Null on any failure (HTTP error, network, bad JSON). The token is never logged. */
  fun fetch(token: String): ClaudeUsage? {
    val reply = try {
      http.get(URL, mapOf("Authorization" to "Bearer $token", "anthropic-beta" to BETA, "User-Agent" to "claude-code/2.1.0"))
    } catch (e: java.io.IOException) {
      return null
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      return null
    }
    if (reply.status !in 200..299) return null
    return parse(reply.body, now())
  }

  companion object {
    const val URL = "https://api.anthropic.com/api/oauth/usage"
    const val BETA = "oauth-2025-04-20"

    fun parse(json: String, nowMs: Long): ClaudeUsage? {
      val o = bestEffort { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
      return ClaudeUsage(
        parseWindow(o.get("five_hour"), SESSION_MINUTES),
        parseWindow(o.get("seven_day"), WEEK_MINUTES),
        fable(o),
        nowMs,
      )
    }

    private fun fable(o: JsonObject): UsageWindow? {
      val scoped = o.get("limits")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it }?.firstOrNull { l ->
        val lo = l.takeIf { it.isJsonObject }?.asJsonObject ?: return@firstOrNull false
        lo.get("kind")?.takeIf { it.isJsonPrimitive }?.asString == "weekly_scoped" && number(lo, "percent") != null &&
          lo.get("scope")?.takeIf { it.isJsonObject }?.asJsonObject?.get("model")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("display_name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase() == "fable"
      }?.asJsonObject
      if (scoped != null) {
        val w = JsonObject().apply { add("used_percentage", scoped.get("percent")); add("resets_at", scoped.get("resets_at")) }
        parseWindow(w, WEEK_MINUTES)?.let { return it }
      }
      return listOf("fable_weekly", "fable_seven_day", "seven_day_fable").firstNotNullOfOrNull { parseWindow(o.get(it), WEEK_MINUTES) }
    }
  }
}
