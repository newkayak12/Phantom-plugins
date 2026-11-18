// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Writes META-INF/THIRD-PARTY-NOTICES.txt and META-INF/licenses/<SPDX id>.txt of a plugin jar: every library from Maven
 * packed into the plugin's lib/ with its license as its POM (or a parent POM) names it, the license and notice files
 * the library's jar carries (their copyright lines are what MIT and BSD ask to keep), and the full text of each license
 * other than Apache 2.0, which is the jar's META-INF/LICENSE.
 */
abstract class ThirdPartyNoticesTask : DefaultTask() {
  @get:Input
  abstract val notices: Property<String>

  @get:Input
  abstract val licenseTexts: MapProperty<String, String>

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun write() {
    val dir = outputDirectory.get().asFile
    dir.deleteRecursively()
    dir.resolve("THIRD-PARTY-NOTICES.txt").apply { parentFile.mkdirs() }.writeText(notices.get())
    licenseTexts.get().forEach { (name, text) -> dir.resolve("licenses/$name").apply { parentFile.mkdirs() }.writeText(text) }
  }
}

private data class PomInfo(val name: String?, val url: String?, val licenses: List<Pair<String, String?>>)

/** Builds the notices of the libraries [configurations] resolve to; [licensesDir] holds the license texts by SPDX id. */
class ThirdPartyNotices(private val project: Project, private val configurations: List<Configuration>, private val licensesDir: File) {
  private val poms = HashMap<String, PomInfo?>()

  fun build(): Pair<String, Map<String, String>> {
    val jars = sortedMapOf<String, MutableSet<File>>()
    for (configuration in configurations) {
      for (artifact in configuration.incoming.artifacts.artifacts) {
        val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: continue
        jars.getOrPut("${id.group}:${id.module}:${id.version}") { sortedSetOf() }.add(artifact.file)
      }
    }
    val usedLicenses = sortedSetOf<String>()
    val text = StringBuilder()
    text.append("Third-party libraries packaged in this plugin (lib/)\n")
    text.append("=====================================================\n\n")
    if (jars.isEmpty()) text.append("None: this plugin packages no third-party libraries.\n")
    else text.append("Apache License 2.0 is the jar's META-INF/LICENSE; other license texts are in META-INF/licenses/.\n")
    for ((coordinates, files) in jars) appendLibrary(text, coordinates, files, usedLicenses)
    val texts = usedLicenses.associate { id ->
      val file = licensesDir.resolve("$id.txt")
      check(file.isFile) { "No license text $file for a packaged library; add it from https://github.com/spdx/license-list-data" }
      "$id.txt" to file.readText()
    }
    return text.toString() to texts
  }

  private fun appendLibrary(text: StringBuilder, coordinates: String, files: Set<File>, usedLicenses: MutableSet<String>) {
    val pom = pomChain(coordinates)
    val module = coordinates.substringBeforeLast(':')
    val group = module.substringBefore(':')
    val licenses = pom.licenses.ifEmpty { knownLicenses[module] ?: knownLicenses[group].orEmpty() }
    text.append("\n").append("-".repeat(78)).append("\n")
    text.append(coordinates).append("\n")
    pom.name?.let { text.append("Name: ").append(it).append("\n") }
    (pom.url ?: knownUrls[module] ?: knownUrls[group])?.let { text.append("Project: ").append(it).append("\n") }
    if (licenses.isEmpty()) text.append("License: not stated in its POM; see the project page\n")
    for ((name, url) in licenses) appendLicense(text, name, url, usedLicenses)
    for (line in copyrights[module] ?: copyrights[group].orEmpty()) text.append(line).append("\n")
    nativeLicenses[module]?.let { spdx ->
      usedLicenses.addAll(spdx)
      text.append("Native library licenses: ").append(spdx.joinToString { "META-INF/licenses/$it.txt" }).append("\n")
    }
    for ((entry, content) in files.flatMap { licenseEntries(it) }.distinctBy { it.second }) {
      text.append("\n--- ").append(entry).append(" ---\n").append(content.trimEnd()).append("\n")
    }
  }

  private fun appendLicense(text: StringBuilder, name: String, url: String?, usedLicenses: MutableSet<String>) {
    val spdx = spdxId(name, url)
    if (spdx != null && spdx != "Apache-2.0") usedLicenses.add(spdx)
    // LGPL 3.0 is a set of permissions added to the GPL 3.0, whose text it needs
    if (spdx == "LGPL-3.0-only") usedLicenses.add("GPL-3.0-only")
    text.append("License: ").append(name)
    if (url != null) text.append(" <").append(url).append(">")
    if (spdx != null) text.append(" (").append(if (spdx == "Apache-2.0") "META-INF/LICENSE" else "META-INF/licenses/$spdx.txt").append(")")
    text.append("\n")
  }

  /** The library's POM, with what it leaves out (name, project URL, licenses) taken from its parent POMs. */
  private fun pomChain(coordinates: String): PomInfo {
    var name: String? = null
    var url: String? = null
    var licenses: List<Pair<String, String?>> = emptyList()
    var current: String? = coordinates
    var depth = 0
    while (current != null && depth++ < 8) {
      val info = pom(current) ?: break
      if (name == null) name = info.name
      if (url == null) url = info.url
      if (licenses.isEmpty()) licenses = info.licenses
      if (url != null && licenses.isNotEmpty()) break
      current = parents[current]
    }
    return PomInfo(name, url, licenses)
  }

  private val parents = HashMap<String, String?>()

  private fun pom(coordinates: String): PomInfo? = poms.getOrPut(coordinates) {
    val file = project.configurations.detachedConfiguration(project.dependencies.create("$coordinates@pom"))
      .apply { isTransitive = false }
      .incoming.artifactView { lenient(true) }.files.singleOrNull() ?: return@getOrPut null
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    parents[coordinates] = root.child("parent")?.let { "${it.childText("groupId")}:${it.childText("artifactId")}:${it.childText("version")}" }
    PomInfo(
      name = root.childText("name")?.takeUnless { it.contains("\${") },
      url = (root.childText("url") ?: root.child("scm")?.childText("url"))?.takeUnless { it.contains("\${") },
      licenses = root.child("licenses")?.children("license").orEmpty().mapNotNull { license ->
        license.childText("name")?.let { it to license.childText("url") }
      },
    )
  }

  private fun Element.children(tag: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

  private fun Element.child(tag: String): Element? = children(tag).firstOrNull()

  private fun Element.childText(tag: String): String? = child(tag)?.textContent?.trim()?.replace(Regex("\\s+"), " ")?.ifEmpty { null }

  private fun licenseEntries(jar: File): List<Pair<String, String>> = ZipFile(jar).use { zip ->
    zip.entries().asSequence()
      .filter { !it.isDirectory && licenseFile.matches(it.name) }
      .sortedBy { it.name }
      .map { "${jar.name}!/${it.name}" to zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
      .toList()
  }

  private companion object {
    // META-INF/LICENSE, META-INF/NOTICE.txt, LICENSE.md, META-INF/LICENSE-asm.txt ..., and nothing deeper than META-INF
    val licenseFile = Regex("(META-INF/)?(LICEN[CS]E|NOTICE|COPYING|COPYRIGHT)([-._][^/]*)?", RegexOption.IGNORE_CASE)

    // Libraries (group:artifact, or a whole group) whose POM names no license or project, taken from the project's
    // own LICENSE file
    val knownLicenses = mapOf(
      "org.jetbrains.packagesearch" to listOf("Apache License, Version 2.0" to "https://www.apache.org/licenses/LICENSE-2.0"),
      "com.github.lamba92" to listOf("Apache License, Version 2.0" to "https://www.apache.org/licenses/LICENSE-2.0"),
    )
    val knownUrls = mapOf(
      "org.jetbrains.packagesearch" to "https://github.com/JetBrains/package-search-api-models",
      "com.github.lamba92" to "https://github.com/lamba92/kotlinx-document-store",
    )

    // The copyright lines of the MIT and BSD licensed libraries whose jar carries no license file, from the LICENSE file
    // in each project's repository; MIT and BSD ask that the copyright notice goes with the binaries
    val copyrights = mapOf(
      "org.jruby.joni:joni" to listOf("Copyright (c) 2017 JRuby Team"),
      "org.jruby.jcodings:jcodings" to listOf("Copyright (c) JRuby Team"),
      "com.vdurmont:emoji-java" to listOf("Copyright (c) 2014-present Vincent DURMONT <vdurmont@gmail.com>"),
      "net.loomchild:segment" to listOf("Copyright (c) 2006-2015 Jarek Lipski"),
      "com.soywiz.korlibs.krypto:krypto-jvm" to listOf("Copyright (c) 2017 Carlos Ballesteros Velasco"),
      "org.webjars:jquery" to listOf("Copyright OpenJS Foundation and other contributors, https://openjsf.org/"),
      "org.carrot2" to listOf("Copyright (c) 2006 Dawid Weiss", "Copyright (c) 2007-2015 Dawid Weiss, Marcin Milkowski"),
      "javax.measure:unit-api" to listOf("Copyright (c) 2014-2020, Jean-Marie Dautelle, Werner Keil, Otavio Santana."),
      "tech.units:indriya" to listOf("Copyright (c) 2005-2022, Jean-Marie Dautelle, Werner Keil, Otavio Santana."),
      "tech.uom.lib:uom-lib-common" to listOf("Copyright (c) 2005-2020, Jean-Marie Dautelle, Werner Keil and others."),
      "com.twelvemonkeys.common" to listOf("Copyright (c) 2008-2020, Harald Kuhr"),
      "com.twelvemonkeys.imageio" to listOf("Copyright (c) 2008-2020, Harald Kuhr"),
      "org.ow2.asm" to listOf("Copyright (c) 2000-2011 INRIA, France Telecom"),
      // The jars carry native builds of the C libraries they wrap
      "com.github.luben:zstd-jni" to listOf(
        "Copyright (c) 2015-present, Luben Karavelov",
        "Includes Zstandard (https://github.com/facebook/zstd), BSD 3-Clause License: Copyright (c) Meta Platforms, Inc. and affiliates.",
      ),
      "org.xerial.snappy:snappy-java" to listOf(
        "Includes Snappy (https://github.com/google/snappy), BSD 3-Clause License: Copyright 2011, Google Inc.",
      ),
      "org.lz4:lz4-java" to listOf("Includes LZ4 (https://github.com/lz4/lz4), BSD 2-Clause License: Copyright (c) 2011-2020, Yann Collet"),
    )
    val nativeLicenses = mapOf(
      "com.github.luben:zstd-jni" to listOf("BSD-3-Clause"),
      "org.xerial.snappy:snappy-java" to listOf("BSD-3-Clause"),
      "org.lz4:lz4-java" to listOf("BSD-2-Clause"),
    )

    fun spdxId(name: String, url: String?): String? {
      val text = "$name ${url.orEmpty()}".lowercase()
      // Words, so that "mit" is not found in "permit" nor "mpl" in "simplified"
      val words = Regex("[a-z]+").findAll(text).map { it.value }.toSet()
      return when {
        "apache" in words && "2" in text -> "Apache-2.0"
        "lgpl" in words || "lesser general public" in text || "library general public" in text ->
          // gnu.org/licenses/lgpl.html, without a version, is the current LGPL: 3.0
          if ("3" in name || "lgpl-3" in text || "lgpl.html" in text) "LGPL-3.0-only" else "LGPL-2.1-only"
        "gpl" in words || "general public license" in text -> if ("3" in name) "GPL-3.0-only" else "GPL-2.0-only"
        "mozilla" in words || "mpl" in words -> "MPL-2.0"
        "eclipse public license" in text || "epl" in words -> if ("2.0" in text || "epl-2" in text) "EPL-2.0" else "EPL-1.0"
        "eclipse distribution license" in text || "edl" in words -> "BSD-3-Clause"
        "bsd" in words -> if ("2-clause" in text || "simplified" in words || "bsd-2" in text) "BSD-2-Clause" else "BSD-3-Clause"
        "mit" in words -> "MIT"
        "isc" in words -> "ISC"
        "cddl" in words || "common development and distribution" in text -> if ("1.1" in text) "CDDL-1.1" else "CDDL-1.0"
        else -> null
      }
    }
  }
}
