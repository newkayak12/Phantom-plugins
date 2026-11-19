// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.remote.Pairing
import dev.phantom.ai.remote.PairingLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {
  @Test
  fun `builds ipv4 and bracketed ipv6`() {
    assertEquals("http://100.64.0.1:47860/pair?token=abc_-1", PairingLink.build("100.64.0.1", 47860, "abc_-1"))
    assertEquals("http://[::1]:47860/pair?token=t", PairingLink.build("::1", 47860, "t"))
    assertEquals("http://[::1]:47860/pair?token=t", PairingLink.build("[::1]", 47860, "t"))
  }

  @Test
  fun `round trips`() {
    assertEquals(Pairing("192.168.1.5", 8080, "tok"), PairingLink.parse(PairingLink.build("192.168.1.5", 8080, "tok")))
    assertEquals(Pairing("fe80::1", 47860, "tok"), PairingLink.parse(PairingLink.build("fe80::1", 47860, "tok")))
    assertEquals(Pairing("myhost", 1, "a-b_c"), PairingLink.parse("http://myhost:1/pair?token=a-b_c"))
  }

  @Test
  fun `refuses non-connectable and junk`() {
    assertNull(PairingLink.parse("http://0.0.0.0:47860/pair?token=t"))
    assertNull(PairingLink.parse("http://[::]:47860/pair?token=t"))
    assertNull(PairingLink.parse("http://[::ffff:0.0.0.0]:47860/pair?token=t"))
    assertNull(PairingLink.parse("http://127.0.0.1:0/pair?token=t"))
    assertNull(PairingLink.parse("http://127.0.0.1:70000/pair?token=t"))
    assertNull(PairingLink.parse("https://127.0.0.1:1/pair?token=t"))
    assertNull(PairingLink.parse("http://127.0.0.1:1/#pair=t"))
    assertNull(PairingLink.parse("http://127.0.0.1:1/pair?token="))
    assertNull(PairingLink.parse("http://127.0.0.1/pair?token=t"))
    assertNull(PairingLink.parse("http://127.0.0.1:1/pair?token=a&x=1"))
    assertNull(PairingLink.parse(""))
    assertNull(PairingLink.parse("junk"))
  }
}
