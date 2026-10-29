// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.render

import dev.phantom.ai.visualization.model.Scene
import dev.phantom.ai.visualization.theme.LightSource
import dev.phantom.ai.visualization.theme.Lighting
import java.awt.image.BufferedImage
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** A light source at its scene pixel. */
data class PlacedLight(val x: Int, val y: Int, val source: LightSource)

/**
 * Applies a theme's [Lighting] to a room image at native resolution: light pools around the scene items that cast light, their
 * falloff quantized to [LEVELS] steps with a 4x4 ordered dither, so the pools stay pixel art at every zoom. Knows no theme or color.
 */
object LightOverlay {
  const val LEVELS = 4

  /** How much flatter a pool is than a circle: the 2:1 floor, a little rounder so it also climbs walls and people. */
  const val ISO_SQUASH = 1.8

  private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

  /** Every light of [lighting] in [scene]: one per item drawn with a source's frame. */
  fun lights(scene: Scene, lighting: Lighting): List<PlacedLight> {
    val byFrame = lighting.sources.groupBy { it.frame }
    return scene.items.flatMap { item -> byFrame[item.frame].orEmpty().map { PlacedLight(item.x + it.dx, item.y + it.dy, it) } }
  }

  /** Lights [img], composed at [k] image pixels per scene pixel: the pools and their dither stay in scene pixels at any [k]. */
  fun apply(img: BufferedImage, lights: List<PlacedLight>, lighting: Lighting, k: Int = 1) {
    val w = img.width / k
    val h = img.height / k
    val lr = FloatArray(w * h)
    val lg = FloatArray(w * h)
    val lb = FloatArray(w * h)
    for (l in lights) {
      val s = l.source
      val rad = s.radius.toDouble()
      val ry = (rad / ISO_SQUASH).toInt() + 1
      for (y in maxOf(0, l.y - ry) until minOf(h, l.y + ry + 1)) {
        val dy = (y - l.y) * ISO_SQUASH
        for (x in maxOf(0, l.x - s.radius) until minOf(w, l.x + s.radius + 1)) {
          val dx = (x - l.x).toDouble()
          val d = sqrt(dx * dx + dy * dy) / rad
          if (d >= 1) continue
          val f = (1 - d).pow(1.6) * s.strength * LEVELS
          val th = (BAYER[(y and 3) * 4 + (x and 3)] + 0.5) / 16.0
          val q = ((floor(f) + if (f - floor(f) > th) 1 else 0) / LEVELS).toFloat()
          if (q == 0f) continue
          val i = y * w + x
          lr[i] += s.color.r * q
          lg[i] += s.color.g * q
          lb[i] += s.color.b * q
        }
      }
    }
    val a = lighting.ambient
    // the brightest channel values an unlit pixel of the sheet can have; anything above glows on its own
    val maxR = (255 * a.r).toInt()
    val maxG = (255 * a.g).toInt()
    val maxB = (255 * a.b).toInt()
    val iw = img.width
    val px = img.getRGB(0, 0, iw, img.height, null, 0, iw)
    for (j in px.indices) {
      val i = (j / iw / k) * w + (j % iw) / k
      if (i >= lr.size || lr[i] == 0f && lg[i] == 0f && lb[i] == 0f) continue
      val p = px[j]
      if (p ushr 24 == 0) continue
      val r = p shr 16 and 255
      val g = p shr 8 and 255
      val b = p and 255
      if (r > maxR || g > maxG || b > maxB) continue
      val nr = minOf(255, (r * (a.r + lr[i]) / a.r).toInt())
      val ng = minOf(255, (g * (a.g + lg[i]) / a.g).toInt())
      val nb = minOf(255, (b * (a.b + lb[i]) / a.b).toInt())
      px[j] = (p and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
    }
    img.setRGB(0, 0, iw, img.height, px, 0, iw)
  }
}
