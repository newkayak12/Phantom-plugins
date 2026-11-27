// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.redis

/** What the console checks in a typed command before sending it, showing it and keeping it in the history. */
object RedisConsole {
  /** What a command deleting every key of a database or of the server deletes, to confirm before it is sent; null for others. */
  fun wipeScope(args: List<String>, server: String): String? = when (args.firstOrNull()?.uppercase()) {
    "FLUSHDB" -> PhantomRedisBundle.message("console.wipe.database", server)
    "FLUSHALL" -> PhantomRedisBundle.message("console.wipe.all", server)
    else -> null
  }

  /**
   * Why the console does not send [args], or null. Blocking commands would hold the one connection the key list shares.
   * SELECT would switch that connection's database silently: the key list and later commands would then read another
   * database than the server settings name.
   */
  fun refusal(args: List<String>): String? {
    val command = args.firstOrNull()?.uppercase() ?: return null
    return when {
      command == "SELECT" -> PhantomRedisBundle.message("console.select.refused")
      command in BLOCKING -> PhantomRedisBundle.message("console.blocking.refused", args[0])
      else -> null
    }
  }

  /** [text] cut to [maxLines] lines and [maxChars] characters, saying how much was left out. */
  fun clip(text: String, maxLines: Int = MAX_LINES, maxChars: Int = MAX_CHARS): String {
    var end = text.length.coerceAtMost(maxChars)
    var lines = 0
    for (i in 0 until end) if (text[i] == '\n' && ++lines == maxLines) { end = i; break }
    if (end == text.length) return text
    val rest = text.substring(end).count { it == '\n' } + (if (text[end] == '\n') 0 else 1)
    return text.substring(0, end) + "\n" + PhantomRedisBundle.message("console.clipped", text.length - end, rest)
  }

  /**
   * [args] with passwords replaced by ***: AUTH, HELLO … AUTH, MIGRATE … AUTH/AUTH2, ACL SETUSER passwords and hashes
   * (>, <, #, !), CONFIG SET requirepass/masterauth. The output shows this, and a line it changes stays out of the history.
   */
  fun masked(args: List<String>): List<String> {
    val out = args.toMutableList()
    fun hide(i: Int) { if (i in out.indices) out[i] = SECRET }
    fun keywordAt(word: String) = args.indexOfFirst { it.equals(word, ignoreCase = true) }
    when (args.firstOrNull()?.uppercase()) {
      "AUTH" -> hide(out.lastIndex.coerceAtLeast(1))
      "HELLO" -> keywordAt("AUTH").takeIf { it > 0 }?.let { hide(it + 2) }
      "MIGRATE" -> {
        keywordAt("AUTH").takeIf { it > 0 }?.let { hide(it + 1) }
        keywordAt("AUTH2").takeIf { it > 0 }?.let { hide(it + 2) }
      }
      "ACL" -> if (args.getOrNull(1).equals("SETUSER", ignoreCase = true)) {
        for (i in 3 until args.size) if (args[i].firstOrNull() in PASSWORD_RULES && args[i].length > 1) out[i] = args[i][0] + SECRET
      }
      "CONFIG" -> if (args.getOrNull(1).equals("SET", ignoreCase = true)) {
        for (i in 2 until args.size - 1 step 2) if (args[i].lowercase() in SECRET_SETTINGS) hide(i + 1)
      }
    }
    return out
  }

  /** The line as the output shows it: [line] itself, or the masked arguments when [masked] hid something. */
  fun shown(line: String, args: List<String>): String {
    val m = masked(args)
    return if (m == args) line else m.joinToString(" ") { if (it.any(Char::isWhitespace) || it.isEmpty()) "\"$it\"" else it }
  }

  private const val SECRET = "***"
  private const val MAX_LINES = 1_000
  private const val MAX_CHARS = 200_000
  private val BLOCKING = setOf("SUBSCRIBE", "PSUBSCRIBE", "SSUBSCRIBE", "MONITOR", "BLPOP", "BRPOP", "BLMOVE", "BZPOPMIN", "BZPOPMAX", "XREAD", "SYNC",
                               "PSYNC")
  private val PASSWORD_RULES = setOf('>', '<', '#', '!')
  private val SECRET_SETTINGS = setOf("requirepass", "masterauth")
}
