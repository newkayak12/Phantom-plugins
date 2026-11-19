// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.remote.ClientMessage
import dev.phantom.ai.remote.LaunchRequest
import dev.phantom.ai.remote.ProjectInfo
import dev.phantom.ai.remote.RemoteProtocol
import dev.phantom.ai.remote.RemoteState
import dev.phantom.ai.remote.RepoInfo
import dev.phantom.ai.remote.ResumeRequest
import dev.phantom.ai.remote.ServerMessage
import dev.phantom.ai.remote.SessionInfo
import dev.phantom.ai.remote.WorktreeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteProtocolTest {
  @Test
  fun `client messages round trip`() {
    assertEquals("""{"t":"auth","token":"x"}""", RemoteProtocol.encode(ClientMessage.Auth("x")))
    assertEquals(ClientMessage.Auth("x"), RemoteProtocol.decodeClient("""{"t":"auth","token":"x"}"""))
    val input = ClientMessage.Input("ls\r\u001b[A\"")
    assertEquals(input, RemoteProtocol.decodeClient(RemoteProtocol.encode(input)))
  }

  @Test
  fun `server messages round trip`() {
    val s = ServerMessage.Screen(80, 24, 3, 4, "\u001b[Hhi")
    assertEquals(s, RemoteProtocol.decodeServer(RemoteProtocol.encode(s)))
    assertEquals("""{"t":"closed"}""", RemoteProtocol.encode(ServerMessage.Closed))
    assertEquals(ServerMessage.Closed, RemoteProtocol.decodeServer("""{"t":"closed"}"""))
    assertEquals(ServerMessage.Error("bad"), RemoteProtocol.decodeServer(RemoteProtocol.encode(ServerMessage.Error("bad"))))
  }

  @Test
  fun `rest shapes round trip`() {
    val st = RemoteState(listOf(ProjectInfo("p", "/p", listOf(RepoInfo("r", "/p/r", listOf(WorktreeInfo("/p/r", "main", null, listOf(SessionInfo("t1", "hi", "CLAUDE", "RUNNING", true)))))))))
    assertEquals(st, RemoteProtocol.decodeState(RemoteProtocol.encode(st)))
    assertEquals(LaunchRequest("/w", "claude"), RemoteProtocol.decodeLaunch(RemoteProtocol.encode(LaunchRequest("/w", "claude"))))
    assertEquals(ResumeRequest("t"), RemoteProtocol.decodeResume("""{"tabId":"t"}"""))
  }

  @Test
  fun `malformed input decodes to null and never throws`() {
    val junk = listOf(null, "", "junk", "{", "[]", "null", "42", "{}", """{"t":1}""", """{"t":"auth"}""", """{"t":"auth","token":5}""", """{"t":"nope"}""", """{"t":"input","data":null}""", """{"t":"screen","cols":"a"}""", """{"projects":3}""", """{"projects":[1]}""", """{"worktree":"w"}""", """{"tabId":[]}""")
    junk.forEach {
      assertNull(it, RemoteProtocol.decodeClient(it))
      assertNull(it, RemoteProtocol.decodeServer(it))
      assertNull(it, RemoteProtocol.decodeState(it))
      assertNull(it, RemoteProtocol.decodeLaunch(it))
      assertNull(it, RemoteProtocol.decodeResume(it))
    }
  }
}
