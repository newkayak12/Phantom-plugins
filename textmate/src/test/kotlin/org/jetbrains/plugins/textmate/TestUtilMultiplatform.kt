// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.jetbrains.plugins.textmate

import org.jetbrains.plugins.textmate.bundles.TextMateNioResourceReader
import org.jetbrains.plugins.textmate.bundles.TextMateResourceReader
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.pathString

/**
 * This file contains declarations that delegate to NOT multiplatform-ready code.
 *
 * When compiling a multiplatform project, all functions should be replaced with `expect` ones
 * and every target should provide its own implementation.
 */

object TestUtilMultiplatform {
  // Phantom-plugins: test bundles and grammars from the plugin folder, not the IDE home
  fun getBundleDirectoryPath(bundleName: String): String {
    val bundleDirectory = Path.of("testData/bundles").toAbsolutePath().resolve(bundleName)
    return if (bundleDirectory.exists()) {
      bundleDirectory
    }
    else {
      Path.of("bundles").toAbsolutePath().resolve(bundleName)
    }.pathString
  }

  fun getResourceReader(bundleName: String): TextMateResourceReader {
    val bundleDirectory = getBundleDirectoryPath(bundleName)
    return TextMateNioResourceReader(Path(bundleDirectory))
  }

  fun threadSleep(millis: Long) = Thread.sleep(millis)
}