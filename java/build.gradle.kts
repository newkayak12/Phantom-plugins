// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
// Support for Java (dev.phantom.java): IntelliJ Community's Java plugin, which Phantom builds from its sources and
// publishes as a release of newkayak12/phantom-libs (Phantom's tools/phantom-java-lib.sh). Nothing is compiled here, so
// this is not the phantom-plugin convention: buildPlugin downloads that zip, checks it against the pinned sha256 and
// ships it unchanged, and test checks its descriptor and notices, so plugin-release.yml releases it as java-<version>.
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
  base
}

group = "dev.phantom"

// TBD: the <version> in java/lib/java-impl.jar's plugin.xml of the phantom-libs release; changes with libTag and libSha256
version = "TBD"

// TBD: the phantom-libs release (its tag, and the sha256 from its java-<version>.zip.sha256 asset) once it exists.
// -PjavaLibTag and -PjavaLibSha256 override them; -PjavaLibZip=<absolute path> takes a local zip instead of the download,
// checked against the sha256 all the same.
val libTag: Provider<String> = providers.gradleProperty("javaLibTag").orElse("java-TBD")
val libSha256: Provider<String> = providers.gradleProperty("javaLibSha256").orElse("TBD")
val libZip: File? = providers.gradleProperty("javaLibZip").orNull?.let { file(it) }
val pluginVersion = version.toString()

// The actions use only local values: a reference to the script itself cannot be stored in the configuration cache
val fetchLib = tasks.register("fetchLib") {
  description = "Downloads the Java plugin zip of the pinned phantom-libs release into build/ and checks its sha256"
  val tag = libTag
  val sha256 = libSha256
  val local = libZip
  val asset = "java-$pluginVersion.zip"
  val target = layout.buildDirectory.file("phantom-libs/$asset")
  inputs.property("tag", tag)
  inputs.property("sha256", sha256)
  if (local != null) inputs.file(local)
  outputs.file(target)
  doLast {
    val expected = sha256.get().lowercase()
    if (!Regex("[0-9a-f]{64}").matches(expected)) {
      throw GradleException("java/build.gradle.kts pins no sha256 of a phantom-libs release (javaLibSha256 = $expected)")
    }
    val out = target.get().asFile
    val part = File(out.path + ".part")
    out.parentFile.mkdirs()
    if (local != null) {
      local.copyTo(part, overwrite = true)
    }
    else {
      val url = "https://github.com/newkayak12/phantom-libs/releases/download/${tag.get()}/$asset"
      val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build()
      val response = client.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofFile(part.toPath()))
      if (response.statusCode() != 200) {
        part.delete()
        throw GradleException("$url: HTTP ${response.statusCode()}")
      }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    part.inputStream().use { input ->
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (actual != expected) {
      part.delete()
      throw GradleException("$asset has sha256 $actual, but java/build.gradle.kts pins $expected")
    }
    Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }
}

// The lib zip already is an installable plugin in the IDE's layout (java/lib, java/lib/modules, LICENSE.txt, NOTICE.txt)
val buildPlugin = tasks.register<Copy>("buildPlugin") {
  description = "Puts the checked Java plugin zip, unchanged, into build/distributions"
  group = "build"
  from(fetchLib)
  into(layout.buildDirectory.dir("distributions"))
}

tasks.register("test") {
  description = "Checks the plugin zip: id dev.phantom.java with its IntelliJ aliases, the pinned version, LICENSE.txt and NOTICE.txt"
  group = "verification"
  dependsOn(buildPlugin)
  val zip = layout.buildDirectory.file("distributions/java-$pluginVersion.zip")
  val expectedVersion = pluginVersion
  doLast {
    val errors = mutableListOf<String>()
    ZipFile(zip.get().asFile).use { archive ->
      val names = archive.entries().asSequence().map { it.name }.toList()
      names.filterNot { it.startsWith("java/") }.forEach { errors += "$it is outside java/" }
      for (notice in listOf("java/LICENSE.txt", "java/NOTICE.txt")) {
        val entry = archive.getEntry(notice)
        if (entry == null || archive.getInputStream(entry).use { it.readBytes() }.decodeToString().isBlank()) {
          errors += "missing or empty $notice"
        }
      }
      val impl = archive.getEntry("java/lib/java-impl.jar")
      val descriptor = impl?.let {
        ZipInputStream(archive.getInputStream(it)).use { jar ->
          generateSequence { jar.nextEntry }.firstOrNull { entry -> entry.name == "META-INF/plugin.xml" }?.let { jar.readBytes().decodeToString() }
        }
      }
      if (descriptor == null) {
        errors += "no META-INF/plugin.xml in java/lib/java-impl.jar"
      }
      else {
        if (!Regex("<id>\\s*dev\\.phantom\\.java\\s*</id>").containsMatchIn(descriptor)) errors += "plugin.xml: id is not dev.phantom.java"
        for (alias in listOf("com.intellij.java", "com.intellij.modules.java-capable")) {
          if (!Regex("<module\\s+value=\"${Regex.escape(alias)}\"\\s*/>").containsMatchIn(descriptor)) errors += "plugin.xml: no alias $alias"
        }
        val version = Regex("<version>\\s*([^<\\s]+)\\s*</version>").find(descriptor)?.groupValues?.get(1)
        if (version != expectedVersion) errors += "plugin.xml: version $version, but java/build.gradle.kts has $expectedVersion"
      }
    }
    if (errors.isNotEmpty()) throw GradleException("java-$expectedVersion.zip:\n" + errors.joinToString("\n"))
  }
}

tasks.named("assemble") { dependsOn(buildPlugin) }
tasks.named("check") { dependsOn("test") }
