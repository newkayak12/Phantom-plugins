// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.webp

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.intellij.images.fileTypes.impl.ImageFileType
import java.awt.Color
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageReaderSpi

class WebpInIdeTest : BasePlatformTestCase() {
  fun `test webp files are images`() {
    assertEquals(ImageFileType.INSTANCE, FileTypeManager.getInstance().getFileTypeByFileName("a.webp"))
  }

  fun `test the TwelveMonkeys reader is registered`() {
    val readers = ImageIO.getImageReadersBySuffix("webp").asSequence().map { it.originatingProvider.javaClass.name }.toList()
    assertTrue(readers.toString(), "com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi" in readers)
  }

  fun `test the provider registered at startup is the lazy one`() {
    val registered = IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi::class.java, false).asSequence().map { it.javaClass.name }.toList()
    assertTrue(registered.toString(), LazyWebPImageReaderSpi::class.java.name in registered)
  }

  fun `test lossless webp decodes through ImageIO`() {
    val image = WebpInIdeTest::class.java.getResourceAsStream("/lossless.webp")!!.use { ImageIO.read(it) }
    assertNotNull(image)
    assertEquals(3, image.width)
    assertEquals(2, image.height)
    assertEquals(Color(255, 0, 0).rgb, image.getRGB(0, 0) or (0xFF shl 24))
    assertEquals(Color(255, 0, 255).rgb, image.getRGB(2, 1) or (0xFF shl 24))
  }

  fun `test lossy webp decodes`() {
    val image = WebpInIdeTest::class.java.getResourceAsStream("/lossy.webp")!!.use { ImageIO.read(it) }
    assertEquals(3, image.width)
    assertEquals(2, image.height)
  }
}
