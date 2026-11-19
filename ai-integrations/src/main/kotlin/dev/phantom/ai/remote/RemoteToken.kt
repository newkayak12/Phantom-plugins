// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.remote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The pairing token: only its SHA-256 is stored; it is never logged. */
object RemoteToken {
  private val random = SecureRandom()
  private val HEX64 = Regex("^[0-9a-f]{64}$")

  /** 32 random bytes, base64url without padding. */
  fun generate(): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  /** Lowercase hex SHA-256 (64 chars) of the UTF-8 token. */
  fun hash(token: String): String = digest(token).joinToString("") { "%02x".format(it) }

  /** Constant-time comparison of digests; false, never an exception, for a wrong, empty or malformed input. */
  fun matches(candidate: String, hash: String?): Boolean {
    if (candidate.isEmpty() || hash == null || !HEX64.matches(hash)) return false
    val expected = ByteArray(32) { hash.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    return MessageDigest.isEqual(digest(candidate), expected)
  }

  private fun digest(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
}
