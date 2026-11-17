// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.remote.RemoteToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RemoteTokenTest {
  @Test
  fun `generate is 32 bytes base64url without padding and unique`() {
    val t = RemoteToken.generate()
    assertFalse(t.contains('='))
    assertTrue(t.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    assertEquals(32, Base64.getUrlDecoder().decode(t).size)
    assertNotEquals(t, RemoteToken.generate())
  }

  @Test
  fun `hash is 64 lowercase hex of sha256`() {
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", RemoteToken.hash("abc"))
    assertTrue(Regex("[0-9a-f]{64}").matches(RemoteToken.hash(RemoteToken.generate())))
  }

  @Test
  fun `matches right token only`() {
    val t = RemoteToken.generate()
    val h = RemoteToken.hash(t)
    assertTrue(RemoteToken.matches(t, h))
    assertFalse(RemoteToken.matches(t + "x", h))
    assertFalse(RemoteToken.matches("", h))
    assertFalse(RemoteToken.matches(t, null))
  }

  @Test
  fun `matches is false without throwing for wrong length or non-hex hash`() {
    val t = "abc"
    assertFalse(RemoteToken.matches(t, "")) // wrong length
    assertFalse(RemoteToken.matches(t, RemoteToken.hash(t).take(62))) // wrong length
    assertFalse(RemoteToken.matches(t, RemoteToken.hash(t) + "00")) // wrong length
    assertFalse(RemoteToken.matches(t, "z".repeat(64))) // non-hex
    assertFalse(RemoteToken.matches(t, RemoteToken.hash(t).uppercase())) // not lowercase hex
  }
}
