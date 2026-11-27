// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker

import junit.framework.TestCase

class ContainersPanelTest : TestCase() {
  fun testPollingBacksOffAfterFailures() {
    assertEquals(3_000, ContainersPanel.nextDelay(0))
    assertEquals(6_000, ContainersPanel.nextDelay(1))
    assertEquals(48_000, ContainersPanel.nextDelay(4))
    assertEquals(60_000, ContainersPanel.nextDelay(5))
    assertEquals(60_000, ContainersPanel.nextDelay(100))
  }
}
