// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider
import java.net.URI
import java.security.MessageDigest

/**
 * IntelliJ's mock JDKs 1.7 and 1.8 (java/mockJDK-1.x in intellij-community), which the light Java test projects use as their
 * SDK. They are stripped JDK class files under the GPL v2 with the Classpath Exception, so this repository does not keep
 * copies: the tests download them from the IntelliJ release the plugins build against and check their SHA-256. The files
 * land in build/mockjdk/java/mockJDK-1.x, the layout IntelliJ keeps under its home.
 */
abstract class DownloadMockJdks : DefaultTask() {
  @get:OutputDirectory
  abstract val home: DirectoryProperty

  @TaskAction
  fun download() {
    for ((path, sha256) in FILES) {
      val target = home.file("java/$path").get().asFile
      if (target.isFile && sha256(target.readBytes()) == sha256) continue
      val bytes = URI("$BASE/$path").toURL().openStream().use { it.readBytes() }
      check(sha256(bytes) == sha256) { "SHA-256 mismatch for $BASE/$path" }
      target.parentFile.mkdirs()
      target.writeBytes(bytes)
    }
  }

  private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  companion object {
    const val BASE = "https://raw.githubusercontent.com/JetBrains/intellij-community/idea/262.10968.63/java"
    val FILES = mapOf(
      "mockJDK-1.7/jre/lib/rt.jar" to "1091b85962d637a462541e186800c87e3187e8ed6b2bc21b8cf98845acd500aa",
      "mockJDK-1.7/src.zip" to "f279bbbd9249bd6b8f899830861914f5f19827b69a7c8ef657759271219b0b8c",
      "mockJDK-1.8/jre/lib/rt.jar" to "95171c4e778b668bce9b6a143284de35ff039ed7c3f3474eeaa7bc1dfa7a91ae",
    )
  }
}

/** Downloads the mock JDKs before the tests and passes their home as the phantom.mockjdk.home system property. */
fun Project.useMockJdks() {
  val download = tasks.register<DownloadMockJdks>("mockJdks") {
    home.set(layout.buildDirectory.dir("mockjdk"))
  }
  val home = download.flatMap { it.home }
  tasks.named("test", Test::class.java) {
    dependsOn(download)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dphantom.mockjdk.home=${home.get().asFile.absolutePath}") })
  }
}
