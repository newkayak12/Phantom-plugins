package dev.phantom.ai

import dev.phantom.ai.core.Subagent
import dev.phantom.ai.hooks.AgentTeams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class AgentTeamsTest {
  private fun config(lead: String, vararg members: String): String {
    val lead0 = """{"agentId":"team-lead@t","name":"team-lead","agentType":"team-lead"}"""
    val rest = members.map { """{"agentId":"$it@t","name":"$it","color":"blue","backendType":"in-process"}""" }
    return """{"name":"t","leadAgentId":"team-lead@t","leadSessionId":"$lead","members":[${(listOf(lead0) + rest).joinToString(",")}]}"""
  }

  @Test fun parseLeavesTheLeadOut() {
    val (lead, members) = AgentTeams.parse(config("s1", "test-agent-1", "test-agent-2"))!!
    assertEquals("s1", lead)
    assertEquals(listOf(Subagent("team:test-agent-1@t", "test-agent-1"), Subagent("team:test-agent-2@t", "test-agent-2")), members)
  }

  @Test fun unreadableConfigIsNull() {
    assertNull(AgentTeams.parse("{"))
    assertNull(AgentTeams.parse("""{"members":[]}"""))
  }

  @Test fun mergeKeepsTaskSubagentsAndReplacesTeammates() {
    val task = Subagent("a1", "Explore")
    val old = Subagent("team:x@t", "x")
    val new = Subagent("team:y@t", "y")
    assertEquals(listOf(task, new), AgentTeams.merge(listOf(task, old), listOf(new)))
    assertEquals(listOf(task), AgentTeams.merge(listOf(task, old), emptyList()))
  }

  @Test fun mergeKeepsATeammatesActivity() {
    val busy = Subagent("team:y@t", "y", activity = "Edit Foo.kt")
    assertEquals(listOf(busy), AgentTeams.merge(listOf(busy), listOf(Subagent("team:y@t", "y"))))
  }

  @Test fun scanReadsEveryTeamDirAndRereadsAChangedConfig() {
    val dir = Files.createTempDirectory("claude")
    val team = Files.createDirectories(dir.resolve("teams/session-1"))
    Files.writeString(team.resolve("config.json"), config("s1", "a"))
    Files.createDirectories(dir.resolve("teams/broken"))
    val scanner = AgentTeams.Scanner()
    assertEquals(listOf("a"), scanner.scan(listOf(dir))["s1"]!!.map { it.name })
    Files.writeString(team.resolve("config.json"), config("s1", "a", "b"))
    Files.setLastModifiedTime(team.resolve("config.json"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000))
    assertEquals(listOf("a", "b"), scanner.scan(listOf(dir))["s1"]!!.map { it.name })
    assertEquals(emptyMap<String, List<Subagent>>(), scanner.scan(listOf(dir.resolve("none"))))
  }
}
