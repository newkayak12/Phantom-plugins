plugins {
  id("phantom-plugin")
}

version = "0.6.0"

dependencies {
  intellijPlatform {
    bundledPlugin("org.jetbrains.plugins.yaml")
  }
}
