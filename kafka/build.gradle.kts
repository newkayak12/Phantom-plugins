plugins {
  id("phantom-plugin")
}

version = "0.1.2"

dependencies {
  // the platform has its own slf4j; two copies in one class loader chain break logging
  implementation("org.apache.kafka:kafka-clients:3.9.1") {
    exclude(group = "org.slf4j")
    exclude(group = "com.github.luben")
    exclude(group = "org.xerial.snappy")
    exclude(group = "org.lz4")
  }
}

// kafka-clients' compression codecs carry native libraries for 17 platforms (about 20 MB). The plugin keeps the ones
// Phantom runs on (macOS, Windows x64) and Linux x64/arm64 for development. On any other platform the codecs behave as
// when their library is missing: zstd and snappy fail only for messages compressed with them, lz4 uses its Java code.
val keptNative = Regex("""(?i)/(darwin|mac)/(aarch64|x86_64)/|/(win|windows|win32)/(amd64|x86_64)/|/linux/(amd64|x86_64|aarch64)/""")
val native = Regex("""\.(so|dylib|dll|jnilib)$""")

// the versions kafka-clients 3.9.1 depends on
for (coordinates in listOf("com.github.luben:zstd-jni:1.5.6-4", "org.xerial.snappy:snappy-java:1.1.10.5", "org.lz4:lz4-java:1.8.0")) {
  val (_, artifact, version) = coordinates.split(":")
  val original = configurations.detachedConfiguration(dependencies.create(coordinates)).apply { isTransitive = false }
  val stripped = tasks.register<Jar>("strip-$artifact") {
    archiveFileName = "$artifact-$version.jar"
    destinationDirectory = layout.buildDirectory.dir("codecs")
    from(original.elements.map { files -> files.map { zipTree(it.asFile) } }) {
      exclude { !it.isDirectory && native.containsMatchIn(it.path) && !keptNative.containsMatchIn("/" + it.path) }
      exclude("META-INF/MANIFEST.MF")
    }
  }
  dependencies.add("implementation", files(stripped))
}
