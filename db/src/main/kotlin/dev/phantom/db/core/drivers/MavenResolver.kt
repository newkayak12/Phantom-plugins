// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.drivers

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Fetches a repository URL; `null` means not found (404). */
fun interface ArtifactFetcher {
  fun fetch(url: String): ByteArray?
}

data class MavenCoordinate(val group: String, val artifact: String, val version: String?) {
  companion object {
    /** Parses `group:artifact` or `group:artifact:version`. */
    fun parse(s: String): MavenCoordinate {
      val parts = s.trim().split(':')
      require(parts.size in 2..3 && parts.all { it.isNotBlank() }) { "Not a Maven coordinate (group:artifact[:version]): $s" }
      return MavenCoordinate(parts[0], parts[1], parts.getOrNull(2))
    }
  }
}

data class ResolvedArtifact(val group: String, val artifact: String, val version: String, val jarUrl: String, val sha1Url: String)

/**
 * A minimal Maven resolver: walks POMs (parents, properties, dependencyManagement, BOM imports, exclusions) breadth-first
 * and returns the jars to download. Only produces URLs; downloading and checksum verification are the caller's job.
 */
class MavenResolver(private val repositories: List<String>, val fetcher: ArtifactFetcher) {
  private class Dep(
    val group: String, val artifact: String, val version: String?, val scope: String?, val type: String?,
    val optional: Boolean, val exclusions: List<Pair<String, String>>,
  ) {
    val key get() = "$group:$artifact"
  }

  private class RawPom(
    val parent: Triple<String, String, String>?, val packaging: String?, val properties: Map<String, String>,
    val managed: List<Dep>, val deps: List<Dep>,
  )

  private class Pom(val repository: String, val packaging: String, val managed: Map<String, Dep>, val deps: List<Dep>)

  private val rawCache = HashMap<String, Pair<String, RawPom>>()
  private val pomCache = HashMap<String, Pom>()

  /** `<release>` from maven-metadata.xml, else `<latest>`, else the highest `<version>`. */
  fun latestVersion(group: String, artifact: String): String {
    val root = metadata(group, artifact)
    val versioning = root.child("versioning")
    return versioning?.text("release")
      ?: versioning?.text("latest")
      ?: versions(root).maxWithOrNull(VERSION_ORDER)
      ?: error("No versions of $group:$artifact in maven-metadata.xml")
  }

  /** Roots first (always included), then their compile/runtime dependencies breadth-first; nearest declaration wins. */
  fun resolve(roots: List<MavenCoordinate>): List<ResolvedArtifact> {
    class Node(val group: String, val artifact: String, val version: String, val exclusions: List<Pair<String, String>>)

    val seen = HashSet<String>()
    val queue = ArrayDeque<Node>()
    for (root in roots) {
      if (seen.add("${root.group}:${root.artifact}")) {
        queue += Node(root.group, root.artifact, root.version ?: latestVersion(root.group, root.artifact), emptyList())
      }
    }
    val result = ArrayList<ResolvedArtifact>()
    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      val pom = effectivePom(node.group, node.artifact, node.version)
      // jar, bundle, maven-plugin, eclipse-plugin, ...: everything but a POM ships a jar
      if (pom.packaging != "pom") {
        val jarUrl = artifactUrl(pom.repository, node.group, node.artifact, node.version, "jar")
        result += ResolvedArtifact(node.group, node.artifact, node.version, jarUrl, "$jarUrl.sha1")
      }
      for (dep in pom.deps) {
        if ((dep.scope ?: "compile") !in INCLUDED_SCOPES || dep.optional) continue
        if (node.exclusions.any { (g, a) -> (g == "*" || g == dep.group) && (a == "*" || a == dep.artifact) }) continue
        if (!seen.add(dep.key)) continue
        val version = dep.version ?: error("No version for ${dep.key} (needed by ${node.group}:${node.artifact}:${node.version})")
        queue += Node(dep.group, dep.artifact, pickVersion(dep.group, dep.artifact, version), node.exclusions + dep.exclusions)
      }
    }
    return result
  }

  private fun pickVersion(group: String, artifact: String, spec: String): String {
    if (!spec.startsWith("[") && !spec.startsWith("(")) return spec
    val lowerInclusive = spec.startsWith("[")
    val upperInclusive = spec.endsWith("]")
    val bounds = spec.substring(1, spec.length - 1).split(',').map(String::trim)
    val lower = bounds[0].ifEmpty { null }
    val upper = if (bounds.size == 1) lower else bounds[1].ifEmpty { null }
    return versions(metadata(group, artifact))
      .filter { v ->
        (lower == null || VERSION_ORDER.compare(v, lower).let { if (lowerInclusive) it >= 0 else it > 0 }) &&
        (upper == null || VERSION_ORDER.compare(v, upper).let { if (upperInclusive) it <= 0 else it < 0 })
      }
      .maxWithOrNull(VERSION_ORDER)
      ?: error("No version of $group:$artifact matches $spec")
  }

  private fun metadata(group: String, artifact: String): Element {
    val path = "${group.replace('.', '/')}/$artifact/maven-metadata.xml"
    val bytes = repositories.firstNotNullOfOrNull { fetcher.fetch("${it.trimEnd('/')}/$path") }
      ?: error("maven-metadata.xml for $group:$artifact not found in $repositories")
    return parseXml(bytes)
  }

  private fun versions(metadata: Element): List<String> =
    metadata.child("versioning")?.child("versions")?.children("version")?.mapNotNull { it.textContent.trim().ifEmpty { null } }.orEmpty()

  private fun effectivePom(group: String, artifact: String, version: String): Pom = pomCache.getOrPut("$group:$artifact:$version") {
    val (repository, raw) = rawPom(group, artifact, version)
    // Inheritance first, interpolation after (as Maven does), so a child property can steer a parent's managed versions.
    val chain = generateSequence(raw) { pom -> pom.parent?.let { (g, a, v) -> rawPom(g, a, v).second } }.take(MAX_PARENTS).toList().asReversed()
    val properties = HashMap<String, String>()
    chain.forEach { properties += it.properties }
    raw.parent?.let { (g, _, v) ->
      properties["project.parent.groupId"] = g
      properties["project.parent.version"] = v
    }
    properties["project.groupId"] = group
    properties["project.artifactId"] = artifact
    properties["project.version"] = version
    properties["groupId"] = group
    properties["version"] = version
    properties["pom.groupId"] = group
    properties["pom.version"] = version
    val interpolate = { dep: Dep -> interpolate(dep, properties) }

    val managed = LinkedHashMap<String, Dep>()
    val imports = ArrayList<Dep>()
    for (dep in chain.flatMap { it.managed }.map(interpolate)) {
      if (dep.scope == "import" && dep.type == "pom") imports += dep else managed[dep.key] = dep
    }
    for (bom in imports) {
      val bomVersion = bom.version ?: error("BOM ${bom.key} has no version")
      effectivePom(bom.group, bom.artifact, bomVersion).managed.forEach { (key, dep) -> managed.putIfAbsent(key, dep) }
    }

    val deps = LinkedHashMap<String, Dep>()
    for (dep in chain.flatMap { it.deps }.map(interpolate)) {
      val m = managed[dep.key]
      deps[dep.key] = if (m == null) dep else Dep(
        dep.group, dep.artifact, dep.version ?: m.version, dep.scope ?: m.scope, dep.type, dep.optional,
        dep.exclusions.ifEmpty { m.exclusions },
      )
    }
    val packaging = substitute(raw.packaging ?: "jar", properties)
    Pom(repository, packaging, managed, deps.values.toList())
  }

  private fun interpolate(dep: Dep, properties: Map<String, String>): Dep = Dep(
    substitute(dep.group, properties), substitute(dep.artifact, properties), dep.version?.let { substitute(it, properties) },
    dep.scope?.let { substitute(it, properties) }, dep.type, dep.optional,
    dep.exclusions.map { (g, a) -> substitute(g, properties) to substitute(a, properties) },
  )

  private fun substitute(text: String, properties: Map<String, String>): String {
    var current = text
    repeat(MAX_SUBSTITUTION_PASSES) {
      val next = PLACEHOLDER.replace(current) { m -> properties[m.groupValues[1]] ?: m.value }
      if (next == current) return current
      current = next
    }
    return current
  }

  private fun rawPom(group: String, artifact: String, version: String): Pair<String, RawPom> = rawCache.getOrPut("$group:$artifact:$version") {
    for (repository in repositories) {
      val bytes = fetcher.fetch(artifactUrl(repository, group, artifact, version, "pom")) ?: continue
      return@getOrPut repository to parsePom(parseXml(bytes))
    }
    error("$group:$artifact:$version not found in $repositories")
  }

  private fun parsePom(project: Element): RawPom {
    val parent = project.child("parent")?.let { p ->
      Triple(p.text("groupId") ?: error("parent without groupId"), p.text("artifactId") ?: error("parent without artifactId"),
             p.text("version") ?: error("parent without version"))
    }
    val properties = project.child("properties")?.childElements()?.associate { it.tagName to it.textContent.trim() }.orEmpty()
    return RawPom(
      parent, project.text("packaging"), properties,
      parseDeps(project.child("dependencyManagement")?.child("dependencies")), parseDeps(project.child("dependencies")),
    )
  }

  private fun parseDeps(dependencies: Element?): List<Dep> = dependencies?.children("dependency").orEmpty().mapNotNull { d ->
    val group = d.text("groupId") ?: return@mapNotNull null
    val artifact = d.text("artifactId") ?: return@mapNotNull null
    val exclusions = d.child("exclusions")?.children("exclusion").orEmpty().map { (it.text("groupId") ?: "*") to (it.text("artifactId") ?: "*") }
    Dep(group, artifact, d.text("version"), d.text("scope"), d.text("type"), d.text("optional") == "true", exclusions)
  }

  private fun artifactUrl(repository: String, group: String, artifact: String, version: String, extension: String): String =
    "${repository.trimEnd('/')}/${group.replace('.', '/')}/$artifact/$version/$artifact-$version.$extension"

  private companion object {
    val INCLUDED_SCOPES = setOf("compile", "runtime")
    val PLACEHOLDER = Regex("""\$\{([^}]+)}""")
    const val MAX_PARENTS = 32
    const val MAX_SUBSTITUTION_PASSES = 10

    /** Maven-like ordering: numeric segments compare numerically, a release beats its qualifiers (1.0 > 1.0-beta). */
    val VERSION_ORDER = Comparator<String> { a, b ->
      val x = a.lowercase().split('.', '-')
      val y = b.lowercase().split('.', '-')
      for (i in 0 until maxOf(x.size, y.size)) {
        val p = x.getOrNull(i)
        val q = y.getOrNull(i)
        val c = when {
          p == null -> if (q!!.toLongOrNull() != null) -1 else 1
          q == null -> if (p.toLongOrNull() != null) 1 else -1
          else -> {
            val pn = p.toLongOrNull()
            val qn = q.toLongOrNull()
            when {
              pn != null && qn != null -> pn.compareTo(qn)
              pn != null -> 1
              qn != null -> -1
              else -> p.compareTo(q)
            }
          }
        }
        if (c != 0) return@Comparator c
      }
      0
    }

    fun parseXml(bytes: ByteArray): Element {
      val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
      }
      return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
    }

    fun Element.childElements(): List<Element> =
      (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    fun Element.children(name: String): List<Element> = childElements().filter { it.tagName == name }

    fun Element.child(name: String): Element? = childElements().firstOrNull { it.tagName == name }

    fun Element.text(name: String): String? = child(name)?.textContent?.trim()?.ifEmpty { null }
  }
}
