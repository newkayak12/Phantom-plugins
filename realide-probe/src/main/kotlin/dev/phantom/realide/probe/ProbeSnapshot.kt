// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JFrame

/** Paints a project frame's root pane in-process: 8-bit RGB PNG at scale 1, root-pane coordinates. Call on the EDT. */
object ProbeSnapshot {
  fun frame(project: Project): JFrame? = WindowManager.getInstance().getFrame(project)

  fun paint(project: Project, out: Path, fileName: String): Map<String, Any?> {
    val pane = frame(project)?.rootPane ?: return mapOf("written" to false, "reason" to "no frame")
    val w = pane.width
    val h = pane.height
    if (w <= 0 || h <= 0) return mapOf("written" to false, "reason" to "empty root pane ${w}x$h")
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
      pane.paint(g)
    }
    finally {
      g.dispose()
    }
    Files.createDirectories(out)
    val file = out.resolve(fileName)
    val written = ImageIO.write(image, "png", file.toFile())
    return mapOf("written" to written, "png" to fileName, "width" to w, "height" to h)
  }

  fun bounds(project: Project): Map<String, Any?> {
    val f = frame(project) ?: return mapOf("frame" to false)
    val b: Rectangle = f.bounds
    return mapOf(
      "frame" to true, "x" to b.x, "y" to b.y, "w" to b.width, "h" to b.height,
      "extendedState" to f.extendedState, "showing" to f.isShowing,
    )
  }
}
