// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.hooks

import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import dev.phantom.ai.accounts.AccountsService
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.Subagent
import dev.phantom.ai.core.SystemDirs
import dev.phantom.ai.core.bestEffort
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Claude Code agent teams. Teammates run in the lead's process and send no SubagentStart hook, so they are read off
 * `<claudeDir>/teams/<team>/config.json` (its lead session id and members) and merged into the lead session's subagents,
 * next to the Task tool's. A teammate's id is [PREFIX] + its agentId, so a later scan replaces exactly those.
 */
object AgentTeams {
  const val PREFIX = "team:"

  /** The lead session id of a team config and its teammates (the lead itself left out); null when unreadable. */
  fun parse(json: String): Pair<String, List<Subagent>>? = bestEffort {
    val o = JsonParser.parseString(json).asJsonObject
    val lead = o.get("leadSessionId")?.asString?.takeIf { it.isNotBlank() } ?: return@bestEffort null
    val leadAgent = o.get("leadAgentId")?.asString
    val members = o.getAsJsonArray("members")?.mapNotNull { m ->
      val mo = m.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
      val id = mo.get("agentId")?.asString ?: return@mapNotNull null
      if (id == leadAgent || mo.get("agentType")?.asString == "team-lead") return@mapNotNull null
      Subagent(PREFIX + id, mo.get("name")?.asString ?: id.substringBefore('@'))
    }.orEmpty()
    lead to members
  }.getOrNull()

  /** The Task tool's subagents of [current] kept, the teammates replaced by [teammates]. */
  fun merge(current: List<Subagent>, teammates: List<Subagent>): List<Subagent> =
    current.filterNot { it.id.startsWith(PREFIX) } + teammates

  /** Lead session id -> teammates, over every team dir under each of [claudeDirs]. Reads a config again only when it changed. */
  class Scanner {
    private val cache = HashMap<Path, Pair<Long, Pair<String, List<Subagent>>?>>()

    fun scan(claudeDirs: Collection<Path>): Map<String, List<Subagent>> {
      val seen = HashSet<Path>()
      val result = HashMap<String, List<Subagent>>()
      for (dir in claudeDirs) {
        val teams = dir.resolve("teams")
        if (!teams.isDirectory()) continue
        for (team in bestEffort { teams.listDirectoryEntries() }.getOrNull().orEmpty()) {
          val config = team.resolve("config.json")
          val mtime = bestEffort { Files.getLastModifiedTime(config).toMillis() }.getOrNull() ?: continue
          seen.add(config)
          val parsed = cache[config]?.takeIf { it.first == mtime }?.second
            ?: bestEffort { parse(Files.readString(config)) }.getOrNull().also { cache[config] = mtime to it }
          parsed?.let { (lead, members) -> result[lead] = result[lead].orEmpty() + members }
        }
      }
      cache.keys.retainAll(seen)
      return result
    }
  }
}

/** Polls the team configs every few seconds while a Claude session with a known session id is live. */
@Service(Service.Level.APP)
class AgentTeamsPoller : Disposable {
  private val scanner = AgentTeams.Scanner()
  private var future: ScheduledFuture<*>? = null

  @Synchronized
  fun start() {
    if (future != null) return
    future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ bestEffort { tick() } }, 2, 3, TimeUnit.SECONDS)
  }

  private fun tick() {
    val store = SessionStore.getInstance()
    val claude = store.all().filter { it.kind == AgentKind.CLAUDE && it.sessionId != null }
    if (claude.isEmpty()) return
    val dirs = (listOf(SystemDirs.dir(AgentKind.CLAUDE)) + AccountsService.getInstance().accounts(AgentKind.CLAUDE).map { Path.of(it.dir) })
      .distinct()
    val teams = scanner.scan(dirs)
    for (s in claude) {
      val next = AgentTeams.merge(s.subagents, teams[s.sessionId].orEmpty())
      if (next != s.subagents) store.update(s.tabId) { it.copy(subagents = AgentTeams.merge(it.subagents, teams[it.sessionId].orEmpty())) }
    }
  }

  override fun dispose() {
    future?.cancel(false)
  }
}

/** Starts [AgentTeamsPoller] with the first project. */
class AgentTeamsStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    com.intellij.openapi.application.ApplicationManager.getApplication().getService(AgentTeamsPoller::class.java).start()
  }
}
