// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.remote

import io.nayuki.qrcodegen.QrCode
import java.awt.image.BufferedImage

/** A QR code as a plain [BufferedImage]; no Swing component or display needed, so it works headless. */
object QrImage {
  fun render(text: String, scale: Int = 6, border: Int = 4): BufferedImage {
    require(scale > 0 && border >= 0) { "scale must be positive and border not negative" }
    val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
    val side = (qr.size + border * 2) * scale
    val img = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until side) for (x in 0 until side) {
      val mx = x / scale - border
      val my = y / scale - border
      val dark = mx in 0 until qr.size && my in 0 until qr.size && qr.getModule(mx, my)
      img.setRGB(x, y, if (dark) 0x000000 else 0xFFFFFF)
    }
    return img
  }
}
