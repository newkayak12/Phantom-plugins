// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.theme

import com.google.gson.JsonParser
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** A frame of the sheet: its rectangle and the pixel inside it that sits on the iso point / the feet. */
data class Frame(val name: String, val x: Int, val y: Int, val w: Int, val h: Int, val anchorX: Int, val anchorY: Int)

/** A sprite sheet and its frames. Pure Java2D data, safe headless. */
class Atlas(val image: BufferedImage, val frames: Map<String, Frame>) {
  operator fun get(name: String): Frame = frames[name] ?: throw IllegalArgumentException("No frame '$name' in the atlas")

  fun has(name: String) = name in frames

  /** The frame's pixels as an image that shares the sheet's raster. */
  fun sub(name: String): BufferedImage = get(name).let { image.getSubimage(it.x, it.y, it.w, it.h) }

  /** ARGB pixel at ([x], [y]) inside the frame. */
  fun pixel(name: String, x: Int, y: Int): Int = get(name).let { image.getRGB(it.x + x, it.y + y) }

  /**
   * [name] shrunk by the whole factor [scale]: ceil(w / scale) x ceil(h / scale), the anchor divided. Its pixel (x, y) is the frame's
   * pixel (x * scale + scale / 2, y * scale + scale / 2), clamped: see [scaledPixel]. Only w, h and the anchor of the result are meaningful.
   */
  fun scaled(name: String, scale: Int): Frame = get(name).let {
    if (scale <= 1) it else Frame(it.name, it.x, it.y, (it.w + scale - 1) / scale, (it.h + scale - 1) / scale, it.anchorX / scale, it.anchorY / scale)
  }

  /** ARGB pixel at ([x], [y]) of [name] shrunk by [scale]. */
  fun scaledPixel(name: String, scale: Int, x: Int, y: Int): Int = get(name).let {
    pixel(name, minOf(it.w - 1, x * scale + scale / 2), minOf(it.h - 1, y * scale + scale / 2))
  }

  /** The frame names [theme] asks for that this atlas does not have. */
  fun missing(theme: Theme): List<String> = theme.frameNames().filterNot { it in frames }.sorted()

  /** Fails with every missing frame name when [theme] names a frame the atlas lacks. */
  fun validate(theme: Theme): Atlas {
    val m = missing(theme)
    check(m.isEmpty()) { "Theme ${theme.id} names frames missing from its atlas: $m" }
    return this
  }

  companion object {
    fun parse(index: String, image: BufferedImage): Atlas {
      val frames = LinkedHashMap<String, Frame>()
      for ((name, v) in JsonParser.parseString(index).asJsonObject.getAsJsonObject("frames").entrySet()) {
        val o = v.asJsonObject
        val a = o.getAsJsonArray("anchor")
        frames[name] = Frame(name, o["x"].asInt, o["y"].asInt, o["w"].asInt, o["h"].asInt, a[0].asInt, a[1].asInt)
      }
      return Atlas(image, frames)
    }

    private val cache = ConcurrentHashMap<String, Atlas>()

    /** The validated atlas of [theme], loaded once from the classpath. */
    fun of(theme: Theme): Atlas = cache.computeIfAbsent(theme.id) { load(theme) }

    fun load(theme: Theme, loader: ClassLoader = Atlas::class.java.classLoader): Atlas {
      fun res(path: String) = loader.getResourceAsStream(path.removePrefix("/")) ?: error("Missing resource $path of theme ${theme.id}")
      val image = res(theme.atlasImageResource).use { ImageIO.read(it) } ?: error("Unreadable image ${theme.atlasImageResource}")
      val index = res(theme.atlasIndexResource).use { String(it.readAllBytes(), Charsets.UTF_8) }
      return parse(index, image).validate(theme)
    }
  }
}
