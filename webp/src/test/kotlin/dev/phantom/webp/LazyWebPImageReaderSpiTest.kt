// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.webp

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import junit.framework.TestCase
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.spi.ImageReaderSpi
import javax.imageio.stream.MemoryCacheImageInputStream

/** The provider registered at startup describes and recognizes WebP exactly as the TwelveMonkeys one it stands for. */
class LazyWebPImageReaderSpiTest : TestCase() {
  private val lazy = LazyWebPImageReaderSpi()
  private val real = WebPImageReaderSpi()

  fun `test it describes WebP as TwelveMonkeys does`() {
    fun describe(spi: ImageReaderSpi) = listOf(
      spi.formatNames.toList(), spi.fileSuffixes.toList(), spi.mimeTypes.toList(), spi.pluginClassName,
      spi.inputTypes.toList(), spi.imageWriterSpiNames?.toList(),
      spi.isStandardStreamMetadataFormatSupported, spi.nativeStreamMetadataFormatName, spi.extraStreamMetadataFormatNames?.toList(),
      spi.isStandardImageMetadataFormatSupported, spi.nativeImageMetadataFormatName, spi.extraImageMetadataFormatNames?.toList(),
      spi.vendorName, spi.version, spi.getDescription(Locale.ROOT),
    )
    assertEquals(describe(real), describe(lazy))
  }

  fun `test it recognizes the same inputs`() {
    val inputs = listOf(
      bytes("/lossless.webp"), bytes("/lossy.webp"),
      byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13, 0, 0, 0, 0),
      "RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1),
      "RIFF\u0000\u0000\u0000\u0000WEBPABCD".toByteArray(Charsets.ISO_8859_1),
      ByteArray(3),
    )
    for ((i, input) in inputs.withIndex()) {
      val expected = runCatching { real.canDecodeInput(MemoryCacheImageInputStream(input.inputStream())) }.getOrElse { it.javaClass }
      val stream = MemoryCacheImageInputStream(input.inputStream())
      val actual = runCatching { lazy.canDecodeInput(stream) }.getOrElse { it.javaClass }
      assertEquals("input $i", expected, actual)
      assertEquals("input $i: stream reset", 0L, stream.streamPosition)
    }
    assertFalse(lazy.canDecodeInput("not a stream"))
  }

  fun `test its readers are TwelveMonkeys readers`() {
    val reader = lazy.createReaderInstance(null)
    assertEquals("com.twelvemonkeys.imageio.plugins.webp.WebPImageReader", reader.javaClass.name)
    assertTrue(lazy.isOwnReader(reader))
    val image = ImageIO.createImageInputStream(bytes("/lossless.webp").inputStream()).use { input ->
      reader.input = input
      reader.read(0)
    }
    assertEquals(3, image.width)
  }

  private fun bytes(resource: String): ByteArray = LazyWebPImageReaderSpiTest::class.java.getResourceAsStream(resource)!!.use { it.readBytes() }
}
