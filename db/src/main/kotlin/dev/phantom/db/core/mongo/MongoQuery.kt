// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.mongo

import dev.phantom.db.core.PhantomDbBundle
import org.bson.BsonDocument
import org.bson.BsonInvalidOperationException
import org.bson.BsonValue
import org.bson.json.JsonMode
import org.bson.json.JsonParseException
import org.bson.json.JsonWriterSettings

enum class MongoOperation { FIND, AGGREGATE }

/** One console statement: `db.<collection>.find(<filter>, <projection>)` or `db.<collection>.aggregate([<stages>])`. */
data class MongoCommand(
  val collection: String,
  val operation: MongoOperation,
  val filter: BsonDocument = BsonDocument(),
  val projection: BsonDocument? = null,
  val pipeline: List<BsonDocument> = emptyList(),
)

class MongoQueryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Documents laid out as a table: one column per top-level field, in the order the fields first appear. */
data class MongoTable(val columns: List<String>, val rows: List<List<String>>)

object MongoQuery {
  private val call = Regex(
    """^db\s*\.\s*(?:getCollection\(\s*(["'])(.+?)\1\s*\)|([A-Za-z_$][\w$-]*(?:\.[A-Za-z_$][\w$-]*)*?))""" +
      """\s*\.\s*(find|aggregate)\s*\((.*)\)\s*;?$""",
    RegexOption.DOT_MATCHES_ALL,
  )
  private val relaxed = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build()

  /** Parses the text with the driver's JSON reader, which also takes shell values such as ObjectId("...") and ISODate("..."). */
  fun parse(text: String): MongoCommand {
    val match = call.matchEntire(text.trim()) ?: fail("mongo.error.syntax")
    val collection = match.groupValues[2].ifEmpty { match.groupValues[3] }
    val args = splitArguments(match.groupValues[5])
    return if (match.groupValues[4] == "find") {
      if (args.size > 2) fail("mongo.error.findArgs")
      MongoCommand(collection, MongoOperation.FIND, args.getOrNull(0)?.let(::document) ?: BsonDocument(), args.getOrNull(1)?.let(::document))
    }
    else {
      if (args.size != 1) fail("mongo.error.aggregateArgs")
      val stages = document("{\"p\": ${args[0]}}")["p"]
      if (stages == null || !stages.isArray || stages.asArray().any { !it.isDocument }) fail("mongo.error.aggregateArgs")
      MongoCommand(collection, MongoOperation.AGGREGATE, pipeline = stages.asArray().map { it.asDocument() })
    }
  }

  fun table(documents: List<BsonDocument>): MongoTable {
    val columns = LinkedHashSet<String>()
    documents.forEach { columns.addAll(it.keys) }
    return MongoTable(columns.toList(), documents.map { doc -> columns.map { doc[it]?.let(::display).orEmpty() } })
  }

  /** Strings and ids as they are, everything else as relaxed extended JSON, which is how the shell prints it. */
  fun display(value: BsonValue): String = when {
    value.isString -> value.asString().value
    value.isObjectId -> value.asObjectId().value.toHexString()
    value.isDocument -> value.asDocument().toJson(relaxed)
    else -> BsonDocument("v", value).toJson(relaxed).let { it.substring(it.indexOf(':') + 1, it.length - 1).trim() }
  }

  private fun document(json: String): BsonDocument = try {
    BsonDocument.parse(json)
  }
  catch (e: JsonParseException) {
    throw MongoQueryException(PhantomDbBundle.message("mongo.error.json", e.message.orEmpty()), e)
  }
  catch (e: BsonInvalidOperationException) {
    throw MongoQueryException(PhantomDbBundle.message("mongo.error.json", e.message.orEmpty()), e)
  }

  private fun fail(key: String): Nothing = throw MongoQueryException(PhantomDbBundle.message(key))

  /** Splits at the commas outside brackets and strings. */
  internal fun splitArguments(text: String): List<String> {
    val result = ArrayList<String>()
    var depth = 0
    var start = 0
    var i = 0
    while (i < text.length) {
      when (text[i]) {
        '"', '\'' -> i = endOfString(text, i)
        '{', '[', '(' -> depth++
        '}', ']', ')' -> depth--
        ',' -> if (depth == 0) {
          result += text.substring(start, i)
          start = i + 1
        }
      }
      i++
    }
    result += text.substring(start)
    return result.map { it.trim() }.let { if (it.size == 1 && it[0].isEmpty()) emptyList() else it }
  }

  /** The index of the quote that closes the string opened at [open], or the last index when it is not closed. */
  private fun endOfString(text: String, open: Int): Int {
    var i = open + 1
    while (i < text.length && text[i] != text[open]) i += if (text[i] == '\\') 2 else 1
    return minOf(i, text.length - 1)
  }
}
