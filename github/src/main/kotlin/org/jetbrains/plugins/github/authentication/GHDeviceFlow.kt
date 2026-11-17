// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.github.authentication

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.github.authentication.util.GHSecurityUtil
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationException
import org.jetbrains.plugins.github.i18n.GithubBundle.message
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Browser login without a relay server: the OAuth device flow (RFC 8628) talks to the GitHub server directly and
 * needs only the public client id of Phantom's OAuth app, no client secret and no local callback.
 */
internal object GHDeviceFlow {
  private val LOG = logger<GHDeviceFlow>()

  /** The client id of the "Phantom IDEA" OAuth app (device flow enabled); `-Dphantom.github.oauth.client.id` overrides it. */
  private const val PHANTOM_CLIENT_ID = ""

  val clientId: String? get() = System.getProperty("phantom.github.oauth.client.id")?.takeIf { it.isNotBlank() } ?: PHANTOM_CLIENT_ID.ifBlank { null }

  // phantom-idea: browser login entry points are hidden while no OAuth client id is configured
  /** Whether github.com browser login can work in this build: false while no client id is configured, so the UI offers only token login. */
  val isBrowserLoginAvailable: Boolean get() = clientId != null

  class Code(val deviceCode: String, val userCode: String, val verificationUri: String, val intervalSeconds: Int, val expiresInSeconds: Int)

  suspend fun requestCode(server: GithubServerPath, clientId: String): Code {
    val json = post("${server.toUrl()}/login/device/code", "client_id" to clientId, "scope" to GHSecurityUtil.MASTER_SCOPES.joinToString(" "))
    val deviceCode = json["device_code"] as? String ?: throw GithubAuthenticationException(errorText(json, message("phantom.login.device.no.code")))
    return Code(deviceCode, json["user_code"] as String, json["verification_uri"] as? String ?: "${server.toUrl()}/login/device",
                (json["interval"] as? Number)?.toInt() ?: 5, (json["expires_in"] as? Number)?.toInt() ?: 900)
  }

  /** Polls until the user approves the code in the browser; cancelling the coroutine stops it. */
  suspend fun awaitToken(server: GithubServerPath, clientId: String, code: Code): String {
    var interval = code.intervalSeconds.coerceAtLeast(1)
    val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
    while (System.currentTimeMillis() < deadline) {
      delay(interval * 1000L)
      val json = post("${server.toUrl()}/login/oauth/access_token", "client_id" to clientId, "device_code" to code.deviceCode,
                      "grant_type" to "urn:ietf:params:oauth:grant-type:device_code")
      (json["access_token"] as? String)?.let { return it }
      when (json["error"]) {
        "authorization_pending" -> Unit
        "slow_down" -> interval = (json["interval"] as? Number)?.toInt() ?: (interval + 5)
        else -> throw GithubAuthenticationException(errorText(json, message("phantom.login.device.failed")))
      }
    }
    throw GithubAuthenticationException(message("phantom.login.device.expired"))
  }

  private fun errorText(json: Map<String, Any?>, fallback: String): String =
    (json["error_description"] as? String) ?: (json["error"] as? String)?.let { "$fallback: $it" } ?: fallback

  private suspend fun post(url: String, vararg form: Pair<String, String>): Map<String, Any?> = withContext(Dispatchers.IO) {
    val body = form.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
    val text = HttpRequests.post(url, "application/x-www-form-urlencoded").accept("application/json")
      .throwStatusCodeException(false).connect { it.write(body); it.readString() }
    jacksonObjectMapper().readValue<Map<String, Any?>>(text)
  }

  /** The token of an existing `gh auth login` for this host, or null when the GitHub CLI is missing or logged out. */
  suspend fun ghCliToken(server: GithubServerPath): String? = withContext(Dispatchers.IO) { ghCliTokenBlocking(server) }

  fun ghCliTokenBlocking(server: GithubServerPath): String? {
    // apps started from the Dock do not get the shell PATH, so look in the usual install places too
    val gh = PathEnvironmentVariableUtil.findInPath("gh")
             ?: listOf("/opt/homebrew/bin/gh", "/usr/local/bin/gh", "/usr/bin/gh").map(::File).firstOrNull { it.canExecute() }
             ?: return null
    return try {
      val out = ExecUtil.execAndGetOutput(GeneralCommandLine(gh.path, "auth", "token", "--hostname", server.host), 10_000)
      out.stdout.trim().takeIf { out.exitCode == 0 && it.isNotEmpty() && !it.contains(' ') }
    }
    catch (e: Exception) {
      LOG.info("gh auth token failed: ${e.message}")
      null
    }
  }
}
