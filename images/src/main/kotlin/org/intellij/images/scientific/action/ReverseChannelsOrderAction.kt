// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.intellij.images.scientific.action

import com.intellij.openapi.vfs.VirtualFile
import org.intellij.images.scientific.utils.ImageTransformationData
import org.intellij.images.scientific.utils.ScientificUtils.applyReverseChannelsOrder
import java.awt.image.BufferedImage

class ReverseChannelsOrderAction : BaseImageAction() {
  override suspend fun performImageTransformation(originalImage: BufferedImage,
                                                  currentImage: BufferedImage,
                                                  imageFile: VirtualFile,
                                                  transformationData: ImageTransformationData
  ): BufferedImage {
    transformationData.setIsNormalized(false)
    val transformedImage = transformationData.applyTransformations(originalImage)
    applyReverseChannelsOrder(transformedImage).also {
      imageFile.putUserData(CURRENT_OPERATION_MODE_KEY, ImageOperationMode.REVERSED_IMAGE)
      return it
    }
  }
}