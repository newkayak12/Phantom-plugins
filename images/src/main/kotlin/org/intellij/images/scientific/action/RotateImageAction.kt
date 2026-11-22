// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.intellij.images.scientific.action

import com.intellij.openapi.vfs.VirtualFile
import org.intellij.images.scientific.utils.ImageTransformationData
import org.intellij.images.scientific.utils.ScientificUtils.rotateImage
import java.awt.image.BufferedImage

class RotateImageAction : BaseImageAction() {
  override suspend fun performImageTransformation(originalImage: BufferedImage,
                                                  currentImage: BufferedImage,
                                                  imageFile: VirtualFile,
                                                  transformationData: ImageTransformationData
  ): BufferedImage {
    val newAngle = (transformationData.getRotationAngle() + 90) % 360
    rotateImage(currentImage, 90).also {
      transformationData.setRotationAngle(newAngle)
      return it
    }
  }
}