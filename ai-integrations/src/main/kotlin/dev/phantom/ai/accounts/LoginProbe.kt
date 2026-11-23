// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.accounts

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.SystemDirs
import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.launch.LaunchCommand
import dev.phantom.ai.usage.CodexLauncher
import dev.phantom.ai.usage.ProcessCodexLauncher
import dev.phantom.ai.usage.codexRequest
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The login of an account dir: [name] is its email address, "" when logged in without a known address; [identity] tells
 * one account added twice apart (the same email in two organizations is two accounts), null when unknown.
 */
data class Login(val name: String, val identity: String?)

/** Asks the CLI who is logged in, in an account dir. Null: not logged in, or the CLI could not say. */
fun interface LoginProbe {
  fun read(kind: AgentKind, dir: Path): Login?
}

/**
 * The real probe: asks each CLI through its own interface and never opens its files. Claude: `claude auth status --json`
 * with CLAUDE_CONFIG_DIR; Codex: account/read over `codex app-server` with CODEX_HOME, the channel Codex offers its own
 * clients. Neither answer carries a token.
 */
class CliLoginProbe(
  private val codex: CodexLauncher = ProcessCodexLauncher(),
  private val claude: (Path) -> String? = ::claudeAuthStatus,
) : LoginProbe {
  override fun read(kind: AgentKind, dir: Path): Login? = bestEffort {
    when (kind) {
      AgentKind.CLAUDE -> claude(dir)?.let { parseClaude(JsonParser.parseString(it)) }
      AgentKind.CODEX -> parseCodex(codexRequest(codex, dir.toString(), "account/read", TIMEOUT_MS))
    }
  }.getOrNull()

  companion object {
    private const val TIMEOUT_MS = 10_000L

    /** `claude auth status --json`: {loggedIn, email, orgId, ...}. */
    fun parseClaude(status: JsonElement?): Login? {
      val o = status?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
      if (o.get("loggedIn")?.takeIf { it.isJsonPrimitive }?.asBoolean != true) return null
      val email = o.text("email")
      return Login(email.orEmpty(), email?.let { "${it.lowercase()}|${o.text("orgId").orEmpty()}" })
    }

    /** account/read: {account: null | {type: "chatgpt", email} | {type: "apiKey"} | ...}. */
    fun parseCodex(result: JsonElement?): Login? {
      val account = result?.takeIf { it.isJsonObject }?.asJsonObject?.get("account")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
      val email = account.text("email")
      return Login(email.orEmpty(), email?.lowercase())
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    /** The output of `claude auth status --json` for the account dir [dir]; null when the CLI fails or hangs. */
    private fun claudeAuthStatus(dir: Path): String? {
      val pb = ProcessBuilder(executable(SystemDirs.cli(AgentKind.CLAUDE)), "auth", "status", "--json")
        .redirectError(ProcessBuilder.Redirect.DISCARD)
      pb.environment()[LaunchCommand.CLAUDE_CONFIG_DIR] = dir.toString()
      val p = pb.start()
      try {
        p.outputStream.close()
        // The answer is a few hundred bytes, well within the pipe buffer
        if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
        return p.inputStream.readAllBytes().toString(Charsets.UTF_8)
      } finally {
        p.destroyForcibly()
      }
    }

    /** A bare CLI name resolved on PATH and the usual install dirs (an IDE started from the Dock has a short PATH). */
    private fun executable(cli: String): String {
      if (cli.contains(File.separatorChar)) return cli
      val home = System.getProperty("user.home")
      val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() } +
        listOf("/opt/homebrew/bin", "/usr/local/bin", "$home/.local/bin", "$home/.npm-global/bin", "$home/.claude/local")
      return dirs.map { File(it, cli) }.firstOrNull { it.canExecute() }?.path ?: cli
    }
  }
}
