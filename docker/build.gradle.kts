plugins {
  id("phantom-plugin")
}

version = "0.6.1"

dependencies {
  intellijPlatform {
    bundledPlugin("org.jetbrains.plugins.yaml")
  }
}
