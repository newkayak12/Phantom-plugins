// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.webp

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import java.nio.ByteOrder
import java.util.Locale
import javax.imageio.ImageReader
import javax.imageio.spi.ImageReaderSpi
import javax.imageio.stream.ImageInputStream

// four-character codes read as little-endian ints; top-level, so no companion class is loaded with the provider
private const val RIFF = 0x46464952
private const val WEBP = 0x50424557
private const val VP8 = 0x20385056
private const val VP8L = 0x4C385056
private const val VP8X = 0x58385056

/**
 * Registered in place of TwelveMonkeys' [WebPImageReaderSpi]. The platform registers every ImageIO provider while the
 * IDE starts, and creating theirs loads its provider-info classes from two more jars. This one has the same format
 * names, suffixes and MIME types, checks the WebP header the same way theirs does, and creates their provider only when
 * a WebP image is read; readers come from it, so a reader's originating provider is still theirs.
 */
class LazyWebPImageReaderSpi : ImageReaderSpi(
  // vendor and version are theirs, read when asked for (getVendorName, getVersion)
  "TwelveMonkeys",
  "",
  arrayOf("webp", "WEBP", "wbp", "WBP"),
  arrayOf("wbp", "webp"),
  arrayOf("image/webp", "image/x-webp"),
  "com.twelvemonkeys.imageio.plugins.webp.WebPImageReader",
  STANDARD_INPUT_TYPE,
  null,
  false, null, null, null, null,
  true, null, null, null, null,
) {
  // created on first use only: a nested object is loaded when it is first touched
  private object Real {
    val spi = WebPImageReaderSpi()
  }

  override fun getVendorName(): String = Real.spi.vendorName

  override fun getVersion(): String = Real.spi.version

  override fun getDescription(locale: Locale?): String = Real.spi.getDescription(locale)

  override fun createReaderInstance(extension: Any?): ImageReader = Real.spi.createReaderInstance(extension)

  /** A RIFF file of form WEBP whose first chunk is VP8, VP8L or VP8X, as TwelveMonkeys checks it. */
  override fun canDecodeInput(source: Any?): Boolean {
    if (source !is ImageInputStream) return false
    val order = source.byteOrder
    source.mark()
    try {
      source.byteOrder = ByteOrder.LITTLE_ENDIAN
      if (source.readInt() != RIFF) return false
      source.readInt() // file size
      if (source.readInt() != WEBP) return false
      return when (source.readInt()) {
        VP8, VP8L, VP8X -> true
        else -> false
      }
    }
    finally {
      source.byteOrder = order
      source.reset()
    }
  }
}
