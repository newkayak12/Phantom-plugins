// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.mongo

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** A MongoDB server. The connection string is kept without its password, which lives in the IDE password safe. */
@Tag("mongo-connection")
class MongoConnection {
  var id: String = UUID.randomUUID().toString()
  var name: String = ""
  var uri: String = "mongodb://localhost:27017/"

  override fun toString(): String = name.ifBlank { uri }
}

@Service(Service.Level.PROJECT)
@State(name = "PhantomMongoConnections", storages = [Storage("phantomMongoConnections.xml")])
class MongoConnections : PersistentStateComponent<MongoConnections.State> {
  class State {
    @XCollection(style = XCollection.Style.v2)
    var connections: MutableList<MongoConnection> = ArrayList()
  }

  private var state = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    this.state = state
  }

  companion object {
    fun getInstance(project: Project): MongoConnections = project.service()

    private fun attributes(id: String) = CredentialAttributes(generateServiceName("Phantom MongoDB", id))

    fun password(id: String): String? = PasswordSafe.instance.getPassword(attributes(id))

    fun setPassword(id: String, password: String?) = PasswordSafe.instance.setPassword(attributes(id), password?.ifEmpty { null })
  }
}

object MongoUris {
  private val userInfo = Regex("""^(mongodb(?:\+srv)?://)([^:@/]+)(?::([^@/]*))?@""")

  /** The connection string without its password, and the password (decoded), if it had one. */
  fun splitPassword(uri: String): Pair<String, String?> {
    val match = userInfo.find(uri.trim()) ?: return uri.trim() to null
    val password = match.groups[3]?.value ?: return uri.trim() to null
    val rest = uri.trim().substring(match.range.last + 1)
    return "${match.groupValues[1]}${match.groupValues[2]}@$rest" to URLDecoder.decode(password, Charsets.UTF_8)
  }

  /** Puts the password back for the driver; a connection string without a user name has no place for it. */
  fun withPassword(uri: String, password: String?): String {
    if (password.isNullOrEmpty()) return uri
    val match = userInfo.find(uri) ?: return uri
    if (match.groups[3] != null) return uri
    val encoded = URLEncoder.encode(password, Charsets.UTF_8).replace("+", "%20")
    return "${match.groupValues[1]}${match.groupValues[2]}:$encoded@" + uri.substring(match.range.last + 1)
  }
}
