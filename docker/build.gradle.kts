plugins {
  id("phantom-plugin")
}

version = "0.4.0"

dependencies {
  intellijPlatform {
    bundledPlugin("org.jetbrains.plugins.yaml")
  }
}
