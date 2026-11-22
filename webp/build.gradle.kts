plugins {
  id("phantom-plugin")
}

version = "0.1.3"

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.platform.images")
  }
  // pure Java WebP decoder (BSD-3-Clause), instead of the native libwebp the bundled WebP plugin loads
  implementation("com.twelvemonkeys.imageio:imageio-webp:3.15.3")
}
