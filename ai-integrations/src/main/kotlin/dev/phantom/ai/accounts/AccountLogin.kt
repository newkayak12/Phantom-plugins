// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.accounts

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.bestEffort
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** What the CLI recorded in an account dir about its login. */
internal object AccountLogin {
  /**
   * The login's email address; "" when logged in without a known address; null when not logged in (or not readable).
   * For Claude the file alone proves nothing: any run of the CLI creates .claude.json, only a login adds oauthAccount.
   */
  fun name(kind: AgentKind, dir: Path): String? = bestEffort {
    when (kind) {
      AgentKind.CLAUDE -> read(dir.resolve(".claude.json")).getAsJsonObject("oauthAccount")?.let { it.text("emailAddress").orEmpty() }
      AgentKind.CODEX -> read(dir.resolve("auth.json")).let { auth ->
        val tokens = auth.getAsJsonObject("tokens")
        when {
          tokens != null -> tokens.text("id_token")?.let(::emailOf).orEmpty()
          auth.text("OPENAI_API_KEY") != null -> ""
          else -> null
        }
      }
    }
  }.getOrNull()

  private fun read(file: Path): JsonObject = JsonParser.parseString(Files.readString(file)).asJsonObject

  private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

  /** The email claim of a JWT; its signature does not matter here, this is only a display name. */
  private fun emailOf(jwt: String): String? = bestEffort {
    read(String(Base64.getUrlDecoder().decode(jwt.split('.')[1]))).text("email")
  }.getOrNull()

  private fun read(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
}
