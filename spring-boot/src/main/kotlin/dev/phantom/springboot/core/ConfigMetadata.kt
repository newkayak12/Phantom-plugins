// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class Deprecation(val level: String?, val reason: String?, val replacement: String?)

/** One entry of spring-configuration-metadata.json (Spring Boot reference, "Configuration Metadata" appendix). */
class ConfigProperty(
  val name: String,
  val type: String?,
  val description: String?,
  val defaultValue: String?,
  val sourceType: String?,
  val deprecation: Deprecation?,
) {
  val isMap: Boolean get() = type?.startsWith("java.util.Map") == true

  /** `java.util.Map<java.lang.String,java.lang.String>` -> `Map<String,String>` */
  val shortType: String? get() = type?.replace(PACKAGE_PREFIX, "")?.replace('$', '.')

  fun merged(other: ConfigProperty): ConfigProperty = ConfigProperty(
    name, type ?: other.type, description ?: other.description, defaultValue ?: other.defaultValue,
    sourceType ?: other.sourceType, deprecation ?: other.deprecation,
  )

  private companion object {
    val PACKAGE_PREFIX = Regex("""\b[a-z]\w*(\.[a-z]\w*)*\.(?=[A-Z])""")
  }
}

class HintValue(val value: String, val description: String?)

class ParsedMetadata(
  val properties: List<ConfigProperty>,
  val hints: Map<String, List<HintValue>>,
  val groups: Map<String, String?>,
) {
  companion object {
    val EMPTY: ParsedMetadata = ParsedMetadata(emptyList(), emptyMap(), emptyMap())
  }
}

object ConfigNames {
  /** Relaxed binding canonical form: lower-case kebab, no list indexes. `spring.jpa.openInView` -> `spring.jpa.open-in-view`. */
  fun normalize(name: String): String {
    val sb = StringBuilder(name.length + 8)
    var i = 0
    while (i < name.length) {
      val c = name[i]
      when {
        c == '[' -> {
          val close = name.indexOf(']', i)
          if (close < 0) break
          i = close
        }
        c == '_' -> sb.append('-')
        c.isUpperCase() -> {
          val previous = sb.lastOrNull()
          if (previous != null && previous != '.' && previous != '-' && !name[i - 1].isUpperCase()) sb.append('-')
          sb.append(c.lowercaseChar())
        }
        else -> sb.append(c)
      }
      i++
    }
    return sb.toString()
  }

  fun join(parent: String, child: String): String = when {
    parent.isEmpty() -> child
    child.isEmpty() -> parent
    else -> "$parent.$child"
  }
}

object MetadataParser {
  fun parse(text: CharSequence): ParsedMetadata {
    val root = try {
      JsonParser.parseString(text.toString()) as? JsonObject
    }
    catch (_: Exception) {
      null
    } ?: return ParsedMetadata.EMPTY

    val properties = root.array("properties").mapNotNull { e ->
      val o = e as? JsonObject ?: return@mapNotNull null
      val name = o.str("name") ?: return@mapNotNull null
      val deprecation = (o.get("deprecation") as? JsonObject)?.let { Deprecation(it.str("level"), it.str("reason"), it.str("replacement")) }
                        ?: if (o.get("deprecated")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) Deprecation(null, null, null) else null
      ConfigProperty(ConfigNames.normalize(name), o.str("type"), o.str("description"), displayValue(o.get("defaultValue")),
                     o.str("sourceType"), deprecation)
    }
    val hints = root.array("hints").mapNotNull { e ->
      val o = e as? JsonObject ?: return@mapNotNull null
      val name = o.str("name") ?: return@mapNotNull null
      val values = o.array("values").mapNotNull { v ->
        val vo = v as? JsonObject ?: return@mapNotNull null
        val value = displayValue(vo.get("value")) ?: return@mapNotNull null
        HintValue(value, vo.str("description"))
      }
      if (values.isEmpty()) null else ConfigNames.normalize(name) to values
    }.toMap()
    val groups = root.array("groups").mapNotNull { e ->
      val o = e as? JsonObject ?: return@mapNotNull null
      val name = o.str("name") ?: return@mapNotNull null
      ConfigNames.normalize(name) to o.str("description")
    }.toMap()
    return ParsedMetadata(properties, hints, groups)
  }

  private fun JsonObject.array(name: String): List<JsonElement> =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList()

  private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

  private fun displayValue(e: JsonElement?): String? = when {
    e == null || e.isJsonNull -> null
    e.isJsonPrimitive -> e.asString
    e.isJsonArray -> e.asJsonArray.joinToString(",") { displayValue(it) ?: "" }
    else -> e.toString()
  }
}

class KeyNode(val segment: String, val fullName: String, val property: ConfigProperty?, val hasChildren: Boolean) {
  /** A node the user continues under (a group or a map), as opposed to a key that takes a value. */
  val isGroup: Boolean get() = property == null || property.isMap
}

/** All configuration keys visible from one module, sorted for prefix lookups. */
class ConfigKeys(sources: List<ParsedMetadata>) {
  private val byName: Map<String, ConfigProperty>
  private val sortedNames: List<String>
  private val hints: Map<String, List<HintValue>>
  private val groups: Map<String, String?>

  init {
    val props = LinkedHashMap<String, ConfigProperty>()
    val allHints = HashMap<String, List<HintValue>>()
    val allGroups = HashMap<String, String?>()
    // Earlier sources win (project build output before libraries); later ones only fill gaps.
    for (source in sources) {
      for (p in source.properties) props.merge(p.name, p) { old, new -> old.merged(new) }
      for ((k, v) in source.hints) allHints.putIfAbsent(k, v)
      for ((k, v) in source.groups) if (allGroups[k] == null) allGroups[k] = v
    }
    byName = props
    sortedNames = props.keys.sorted()
    hints = allHints
    groups = allGroups
  }

  val size: Int get() = byName.size

  fun property(name: String): ConfigProperty? {
    val n = ConfigNames.normalize(name)
    return byName[n] ?: mapOwner(n)
  }

  /** `logging.level.org.hibernate` is a key of the map property `logging.level`. */
  private fun mapOwner(name: String): ConfigProperty? {
    var dot = name.lastIndexOf('.')
    while (dot > 0) {
      val candidate = byName[name.substring(0, dot)]
      if (candidate != null) return candidate.takeIf { it.isMap }
      dot = name.lastIndexOf('.', dot - 1)
    }
    return null
  }

  fun groupDescription(name: String): String? = groups[ConfigNames.normalize(name)]

  fun isKnownPath(name: String): Boolean {
    val n = ConfigNames.normalize(name)
    return byName.containsKey(n) || groups.containsKey(n) || namesUnder(n).any()
  }

  fun hintValues(name: String): List<HintValue> {
    val n = ConfigNames.normalize(name)
    return hints[n] ?: hints["$n.values"] ?: mapOwner(n)?.let { hints["${it.name}.values"] } ?: emptyList()
  }

  private fun namesUnder(path: String): Sequence<String> {
    val prefix = if (path.isEmpty()) "" else "$path."
    var i = sortedNames.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
    return generateSequence {
      if (i < sortedNames.size && sortedNames[i].startsWith(prefix)) sortedNames[i++] else null
    }
  }

  /** Next path segments under [path] (`""` for the root): `spring` -> `datasource`, `jpa`, ... */
  fun childrenOf(path: String): List<KeyNode> {
    val n = ConfigNames.normalize(path)
    val prefix = if (n.isEmpty()) "" else "$n."
    val deeper = LinkedHashMap<String, Boolean>()
    for (name in namesUnder(n)) {
      val rest = name.substring(prefix.length)
      val dot = rest.indexOf('.')
      val segment = if (dot < 0) rest else rest.substring(0, dot)
      if (segment.isEmpty()) continue
      deeper[segment] = (deeper[segment] ?: false) || dot >= 0
    }
    return deeper.map { (segment, hasChildren) ->
      val full = prefix + segment
      KeyNode(segment, full, byName[full], hasChildren)
    }
  }

  /** Keys at least two segments below [path], for finding `ddl-auto` while standing in `spring`. */
  fun deepLeavesUnder(path: String, limit: Int): List<ConfigProperty> {
    val n = ConfigNames.normalize(path)
    val prefixLength = if (n.isEmpty()) 0 else n.length + 1
    return namesUnder(n).filter { it.indexOf('.', prefixLength) >= 0 }.take(limit).mapNotNull { byName[it] }.toList()
  }

  fun leavesUnder(path: String, limit: Int): List<ConfigProperty> =
    namesUnder(ConfigNames.normalize(path)).take(limit).mapNotNull { byName[it] }.toList()
}
