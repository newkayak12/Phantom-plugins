// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.mongo

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.bson.BsonDocument
import org.bson.types.ObjectId

class MongoQueryTest : BasePlatformTestCase() {
  fun testFindWithFilterAndProjection() {
    val command = MongoQuery.parse("""db.users.find({"age": {"${'$'}gt": 30}, "name": "a,b"}, {"name": 1});""")
    assertEquals("users", command.collection)
    assertEquals(MongoOperation.FIND, command.operation)
    assertEquals(BsonDocument.parse("""{"age": {"${'$'}gt": 30}, "name": "a,b"}"""), command.filter)
    assertEquals(BsonDocument.parse("""{"name": 1}"""), command.projection)
  }

  fun testFindWithoutArgumentsMatchesAll() {
    val command = MongoQuery.parse("db.users.find()")
    assertEquals(BsonDocument(), command.filter)
    assertNull(command.projection)
  }

  fun testShellValuesAndDottedOrQuotedCollections() {
    val id = ObjectId()
    assertEquals(id, MongoQuery.parse("""db.system.users.find({_id: ObjectId("$id")})""").filter["_id"]!!.asObjectId().value)
    assertEquals("system.users", MongoQuery.parse("db.system.users.find({})").collection)
    assertEquals("my-coll", MongoQuery.parse("""db.getCollection('my-coll').find({})""").collection)
  }

  fun testAggregate() {
    val command = MongoQuery.parse("""db.orders.aggregate([{"${'$'}match": {"x": 1}}, {"${'$'}count": "n"}])""")
    assertEquals(MongoOperation.AGGREGATE, command.operation)
    assertEquals(listOf(BsonDocument.parse("""{"${'$'}match": {"x": 1}}"""), BsonDocument.parse("""{"${'$'}count": "n"}""")), command.pipeline)
  }

  fun testErrors() {
    for (text in listOf("users.find({})", "db.users.insertOne({})", "db.users.find({a: })", "db.users.find({}, {}, {})",
                        "db.users.aggregate({})", "db.users.aggregate([1])")) {
      try {
        MongoQuery.parse(text)
        fail(text)
      }
      catch (_: MongoQueryException) {
      }
    }
  }

  fun testTableColumnsAndValues() {
    val docs = listOf(
      BsonDocument.parse("""{"_id": {"${'$'}oid": "65a000000000000000000001"}, "name": "a", "tags": ["x"]}"""),
      BsonDocument.parse("""{"name": "b", "n": 5, "nested": {"k": true}, "none": null}"""),
    )
    val table = MongoQuery.table(docs)
    assertEquals(listOf("_id", "name", "tags", "n", "nested", "none"), table.columns)
    assertEquals(listOf("65a000000000000000000001", "a", "[\"x\"]", "", "", ""), table.rows[0])
    assertEquals(listOf("", "b", "", "5", "{\"k\": true}", "null"), table.rows[1])
  }

  fun testPasswordIsKeptOutOfTheConnectionString() {
    assertEquals("mongodb://bob@h:27017/db" to "p@ss word", MongoUris.splitPassword("mongodb://bob:p%40ss%20word@h:27017/db"))
    assertEquals("mongodb://h/db" to null, MongoUris.splitPassword("mongodb://h/db"))
    assertEquals("mongodb+srv://bob:p%40ss%20word@h/db", MongoUris.withPassword("mongodb+srv://bob@h/db", "p@ss word"))
    assertEquals("mongodb://h/db", MongoUris.withPassword("mongodb://h/db", "secret"))
  }
}
