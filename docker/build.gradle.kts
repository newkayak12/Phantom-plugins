plugins {
  id("phantom-plugin")
}

version = "0.2.0"

dependencies {
  intellijPlatform {
    bundledPlugin("org.jetbrains.plugins.yaml")
  }
}
