// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bundled web client: present on the classpath, nothing loaded from elsewhere, the token never touched by script. */
class RemoteWebAssetsTest {
  private fun res(name: String): String =
    RemoteWebAssetsTest::class.java.getResourceAsStream("/remote-web/$name")!!.use { it.readAllBytes().toString(Charsets.UTF_8) }

  @Test
  fun filesArePresent() {
    for (f in listOf("index.html", "app.js", "app.css", "vendor/xterm/xterm.js", "vendor/xterm/xterm.css", "vendor/xterm/addon-fit.js", "vendor/xterm/LICENSE", "vendor/xterm/VERSION")) {
      assertTrue(f, res(f).isNotEmpty())
    }
  }

  @Test
  fun versionRecordsTarballHashes() {
    val v = res("vendor/xterm/VERSION")
    assertTrue(v.contains("6.0.0") && v.contains("0.11.0"))
    assertTrue(Regex("[0-9a-f]{64}").findAll(v).count() >= 2)
  }

  @Test
  fun pageLoadsNothingFromOutside() {
    val html = res("index.html")
    assertTrue(html.contains("<title>Phantom AI Integration — Remote</title>"))
    assertFalse(Regex("(src|href)=\"(https?:)?//").containsMatchIn(html))
    assertFalse(Regex("(fetch|WebSocket|import)\\(?\\s*[\"']https?://").containsMatchIn(res("app.js")))
  }

  @Test
  fun scriptNeverTouchesTheToken() {
    val js = res("app.js")
    for (bad in listOf("localStorage", "sessionStorage", "document.cookie", "#pair")) assertFalse(bad, js.contains(bad))
    assertTrue(js.contains("setInterval(poll, 2000)"))
    assertTrue(res("app.css").contains("700px"))
  }
}
