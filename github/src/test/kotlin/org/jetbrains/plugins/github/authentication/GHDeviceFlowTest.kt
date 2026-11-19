// phantom-idea: tests that browser login is offered only when an OAuth client id is configured
package org.jetbrains.plugins.github.authentication

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class GHDeviceFlowTest {
  private val property = "phantom.github.oauth.client.id"

  @Test
  fun `browser login is unavailable without a client id`() {
    val old = System.clearProperty(property)
    try {
      // skipped once a client id is built in (PHANTOM_CLIENT_ID), where there is no "without" case
      assumeTrue(GHDeviceFlow.clientId == null)
      assertFalse(GHDeviceFlow.isBrowserLoginAvailable)
    }
    finally {
      if (old != null) System.setProperty(property, old)
    }
  }

  @Test
  fun `browser login is available with a client id`() {
    val old = System.getProperty(property)
    System.setProperty(property, "x")
    try {
      assertTrue(GHDeviceFlow.isBrowserLoginAvailable)
    }
    finally {
      if (old != null) System.setProperty(property, old) else System.clearProperty(property)
    }
  }
}
