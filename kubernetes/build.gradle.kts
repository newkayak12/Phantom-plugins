plugins {
  id("phantom-plugin")
}

version = "0.6.2"

dependencies {
  intellijPlatform {
    bundledPlugin("org.jetbrains.plugins.yaml")
  }
}
