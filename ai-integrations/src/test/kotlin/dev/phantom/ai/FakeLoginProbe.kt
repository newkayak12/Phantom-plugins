// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.accounts.Login
import dev.phantom.ai.accounts.LoginProbe
import dev.phantom.ai.core.AgentKind
import java.nio.file.Files
import java.nio.file.Path

/** Stands in for the CLIs: an account dir is logged in while it holds [FILE], whose text is the email ("" for none). */
object FakeLoginProbe : LoginProbe {
  const val FILE = "fake-login"

  fun signIn(dir: Path, email: String = ""): Path = Files.writeString(dir.resolve(FILE), email)

  fun signIn(dir: String, email: String = ""): Path = signIn(Path.of(dir), email)

  override fun read(kind: AgentKind, dir: Path): Login? {
    val file = dir.resolve(FILE)
    if (!Files.exists(file)) return null
    val email = Files.readString(file)
    return Login(email, email.takeIf { it.isNotEmpty() }?.lowercase())
  }
}
