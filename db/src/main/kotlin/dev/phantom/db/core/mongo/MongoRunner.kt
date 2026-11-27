// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCursor
import org.bson.BsonDocument
import java.util.concurrent.TimeUnit

/** Runs one command on a client that lives only for that command, so nothing stays connected between runs. */
object MongoRunner {
  const val MAX_DOCUMENTS = 500

  fun run(uri: String, database: String, command: MongoCommand): List<BsonDocument> {
    val settings = MongoClientSettings.builder()
      .applyConnectionString(ConnectionString(uri))
      .applyToClusterSettings { it.serverSelectionTimeout(10, TimeUnit.SECONDS) }
      .applyToSocketSettings { it.connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS) }
      .build()
    MongoClients.create(settings).use { client ->
      val collection = client.getDatabase(database).getCollection(command.collection, BsonDocument::class.java)
      val cursor = when (command.operation) {
        MongoOperation.FIND -> collection.find(command.filter).projection(command.projection).limit(MAX_DOCUMENTS).iterator()
        MongoOperation.AGGREGATE -> collection.aggregate(command.pipeline).iterator()
      }
      return cursor.use { take(it) }
    }
  }

  private fun take(cursor: MongoCursor<BsonDocument>): List<BsonDocument> {
    val documents = ArrayList<BsonDocument>()
    while (documents.size < MAX_DOCUMENTS && cursor.hasNext()) documents += cursor.next()
    return documents
  }
}
