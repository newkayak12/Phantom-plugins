package dev.phantom.ai

import java.awt.GraphicsEnvironment
import org.junit.Test
import org.junit.Assert.assertTrue

/** Shows whether the test JVM is headless and that its home is the sandbox under build/test-home, never the developer's. */
class HeadlessEnvTest {
  @Test
  fun testHomeIsSandboxed() {
    val home = System.getProperty("user.home")
    println("phantom.headless=${GraphicsEnvironment.isHeadless()}")
    println("phantom.home=$home")
    assertTrue("user.home was $home", home.endsWith("build/test-home"))
  }
}
