// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.remote.QrImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrImageTest {
  @Test fun sizeIsModulesPlusBorderTimesScale() {
    val img = QrImage.render("http://127.0.0.1:47860/pair?token=abc")
    assertEquals(img.width, img.height)
    assertEquals(0, img.width % 6)
  }

  @Test fun borderIsWhiteAndSomethingIsDark() {
    val img = QrImage.render("hello", scale = 2, border = 4)
    assertEquals(0xFFFFFF, img.getRGB(0, 0) and 0xFFFFFF)
    assertTrue((0 until img.width).any { x -> (0 until img.height).any { y -> img.getRGB(x, y) and 0xFFFFFF == 0 } })
  }

  @Test fun scaleScalesTheImage() {
    assertEquals(QrImage.render("x", 1, 4).width * 3, QrImage.render("x", 3, 4).width)
  }

  @Test fun deterministic() {
    val a = QrImage.render("same"); val b = QrImage.render("same")
    assertTrue((0 until a.width).all { x -> (0 until a.height).all { y -> a.getRGB(x, y) == b.getRGB(x, y) } })
  }
}
