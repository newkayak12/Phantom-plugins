plugins {
  id("phantom-plugin")
}

version = "0.5.0"

dependencies {
  intellijPlatform {
    bundledPlugin("org.jetbrains.plugins.yaml")
  }
}
