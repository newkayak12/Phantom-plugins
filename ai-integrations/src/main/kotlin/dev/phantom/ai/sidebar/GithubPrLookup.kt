// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import dev.phantom.ai.core.bestEffort
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.ThrowableConvertor
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.plugins.github.api.GithubApiRequest
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.api.GithubApiResponse
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager

/** A GitHub repository named by a git remote url. */
data class GithubRepoRef(val host: String, val owner: String, val repo: String)

object GithubRemote {
  private val scp = Regex("""^(?:[^@/\s]+@)?([^:/\s]+):(?!//)/?([^/\s]+)/([^/\s]+?)(?:\.git)?/?$""")
  private val hier = Regex("""^(?:https?|ssh|git)://(?:[^@/\s]+@)?([^:/\s]+)(?::\d+)?/([^/\s]+)/([^/\s]+?)(?:\.git)?/?$""")

  /** owner/repo of an https, ssh:// or scp-like (git@host:owner/repo.git) remote url; null for anything else. */
  fun parse(url: String): GithubRepoRef? {
    val u = url.trim()
    val m = hier.matchEntire(u) ?: scp.matchEntire(u) ?: return null
    val (host, owner, repo) = m.destructured
    return GithubRepoRef(host.lowercase(), owner, repo)
  }

  /** Every parsable remote, origin first, then the others in order, without repeats. */
  fun allFromConfigLines(lines: List<String>): List<GithubRepoRef> {
    val urls = lines.mapNotNull { l ->
      val m = Regex("""^remote\.(.+)\.url\s+(.+)$""").matchEntire(l.trim()) ?: return@mapNotNull null
      m.groupValues[1] to m.groupValues[2]
    }
    return (urls.filter { it.first == "origin" } + urls.filter { it.first != "origin" }).mapNotNull { parse(it.second) }.distinct()
  }

  /** Picks origin first, else the first url; lines are `git config --get-regexp` output (`remote.<name>.url <url>`). */
  fun fromConfigLines(lines: List<String>): GithubRepoRef? {
    val urls = lines.mapNotNull { l ->
      val m = Regex("""^remote\.(.+)\.url\s+(.+)$""").matchEntire(l.trim()) ?: return@mapNotNull null
      m.groupValues[1] to m.groupValues[2]
    }
    return (urls.filter { it.first == "origin" } + urls).firstNotNullOfOrNull { parse(it.second) }
  }
}

object PullsResponse {
  /** First pull request of a `GET /repos/{o}/{r}/pulls` body (a JSON array) with a number and a title; null otherwise. */
  fun parse(json: String): PrInfo? = bestEffort {
    val arr = JsonParser.parseString(json).asJsonArray
    arr.firstNotNullOfOrNull { e ->
      val o = e.asJsonObject
      val title = o.get("title")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
      PrInfo(
        o.get("number")?.takeUnless { it.isJsonNull }?.asInt,
        title,
        o.get("html_url")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() },
      )
    }
  }.getOrNull()

  /** Open pull requests of [ref] from [branch] of [headOwner] (the fork's owner; the repo's own by default). */
  fun url(apiUrl: String, ref: GithubRepoRef, branch: String, headOwner: String = ref.owner): String {
    val head = URLEncoder.encode("$headOwner:$branch", Charsets.UTF_8)
    return "${apiUrl.trimEnd('/')}/repos/${ref.owner}/${ref.repo}/pulls?head=$head&state=open"
  }
}

/** Remotes of a repository path. Fakeable; the real one is [GitRemoteSource]. */
fun interface RemoteSource {
  /** GitHub-looking remotes, origin first, then the others (upstream and so on). */
  fun githubRepos(repoPath: String): List<GithubRepoRef>

  fun githubRepo(repoPath: String): GithubRepoRef? = githubRepos(repoPath).firstOrNull()
}

/** git4idea only: `git config --get-regexp` through [Git.runCommand] in the repository path. Blocking. */
class GitRemoteSource(private val project: Project = ProjectManager.getInstance().defaultProject) : RemoteSource {
  override fun githubRepos(repoPath: String): List<GithubRepoRef> = bestEffort {
    val handler = GitLineHandler(project, Path.of(repoPath), GitCommand.CONFIG)
    handler.addParameters("--get-regexp", "^remote\\..*\\.url$")
    handler.setSilent(true)
    val result = Git.getInstance().runCommand(handler)
    if (result.success()) GithubRemote.allFromConfigLines(result.output) else emptyList()
  }.getOrDefault(emptyList())
}

/** Fetches the open pull request of a branch from a GitHub repo; fakeable. */
fun interface PrFetcher {
  fun fetch(ref: GithubRepoRef, branch: String): PrInfo?

  /** All remotes of a repository, origin first; the default asks about the first only. */
  fun fetch(refs: List<GithubRepoRef>, branch: String): PrInfo? = refs.firstOrNull()?.let { fetch(it, branch) }
}

/** Maps a host named in a remote url (maybe an ssh alias) to the real host name; null when unknown. */
fun interface SshHostResolver {
  fun resolve(host: String): String?
}

/** Read-only `~/.ssh/config` parser: `Host` alias -> `HostName`, exact aliases only. [text] gives the file's text, null when absent. */
class SshConfigHosts(private val text: () -> String? = { readUserConfig() }) : SshHostResolver {
  override fun resolve(host: String): String? = bestEffort { parse(text() ?: return null)[host.lowercase()] }.getOrNull()

  companion object {
    private val line = Regex("""^\s*(\w+)\s*(?:=|\s)\s*(.*?)\s*$""")

    /** alias (lower case) -> HostName; the first value wins, wildcard and negated patterns are skipped. */
    fun parse(text: String): Map<String, String> {
      val out = LinkedHashMap<String, String>()
      var aliases = emptyList<String>()
      for (raw in text.lines()) {
        val m = line.matchEntire(raw.substringBefore('#')) ?: continue
        val value = m.groupValues[2].trim().trim('"')
        when (m.groupValues[1].lowercase()) {
          "host" -> aliases = value.split(Regex("\\s+")).filter { it.isNotEmpty() && !it.any { c -> c in "*?!" } }.map { it.lowercase() }
          "match" -> aliases = emptyList()
          "hostname" -> if (value.isNotEmpty()) aliases.forEach { out.putIfAbsent(it, value.lowercase()) }
        }
      }
      return out
    }

    private fun readUserConfig(): String? = bestEffort {
      val f = Path.of(System.getProperty("user.home"), ".ssh", "config")
      if (Files.isRegularFile(f)) Files.readString(f) else null
    }.getOrNull()
  }
}

/** Which GitHub account serves a remote's host. Pure. */
object GithubHosts {
  private val prefixes = listOf("ssh.", "api.", "www.")

  private fun normalize(host: String): String {
    val h = host.trim().lowercase()
    return prefixes.firstOrNull { h.startsWith(it) }?.let { h.removePrefix(it) } ?: h
  }

  /** The host a remote's host stands for: through an ssh alias, else a dotless `github*` is github.com. */
  fun effectiveHost(remoteHost: String, ssh: SshHostResolver): String {
    val h = remoteHost.trim().lowercase()
    ssh.resolve(h)?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
    return if ('.' !in h && h.startsWith("github")) "github.com" else h
  }

  fun matches(remoteHost: String, serverHost: String, ssh: SshHostResolver): Boolean =
    remoteHost.equals(serverHost, ignoreCase = true) || normalize(effectiveHost(remoteHost, ssh)) == normalize(serverHost)

  /** `github-<login>` -> login, null for any other host. */
  fun aliasSuffix(remoteHost: String): String? =
    remoteHost.trim().lowercase().takeIf { '.' !in it && it.startsWith("github-") }?.removePrefix("github-")?.takeIf { it.isNotEmpty() }

  /** The accounts of the matching server; several: the login equal to the alias suffix or the repo owner, else the first. */
  fun pick(ref: GithubRepoRef, accounts: List<GithubAccount>, ssh: SshHostResolver): GithubAccount? {
    val matching = accounts.filter { matches(ref.host, it.server.host, ssh) }
    if (matching.size < 2) return matching.firstOrNull()
    val wanted = listOfNotNull(aliasSuffix(ref.host), ref.owner)
    return wanted.firstNotNullOfOrNull { w -> matching.firstOrNull { it.name.equals(w, ignoreCase = true) } } ?: matching.first()
  }
}

/** One GET against a GitHub server with a token; the body, null when empty. Fakeable. */
fun interface GithubApi {
  fun get(server: GithubServerPath, token: String, url: String): String?
}

/** The GitHub plugin's request executor. Blocking. */
class GithubPluginApi : GithubApi {
  override fun get(server: GithubServerPath, token: String, url: String): String? {
    val executor = GithubApiRequestExecutor.Factory.getInstance().create(server, token)
    val request = object : GithubApiRequest.Get<String?>(url) {
      override fun extractResult(response: GithubApiResponse): String? =
        response.readBody(ThrowableConvertor { it.readText() })
    }
    return executor.execute(EmptyProgressIndicator(), request)
  }
}

private object GithubPluginServices {
  private val manager get() = ApplicationManager.getApplication().getService(GHAccountManager::class.java)
  fun accounts(): List<GithubAccount> = manager.accountsState.value.toList()
  suspend fun token(account: GithubAccount): String? = manager.findCredentials(account)
}

/**
 * Through the GitHub plugin: the account of the remote's host (ssh aliases and GHE hosts resolved), its stored token, a request.
 * Tries origin, the other remotes, then origin's parent repo (a fork's upstream) for a pull request from `<origin owner>:<branch>`. Blocking.
 */
class GithubPluginPrFetcher(
  private val accounts: () -> List<GithubAccount> = GithubPluginServices::accounts,
  private val token: suspend (GithubAccount) -> String? = GithubPluginServices::token,
  private val api: GithubApi = GithubPluginApi(),
  private val sshHosts: SshHostResolver = SshConfigHosts(),
) : PrFetcher {
  override fun fetch(ref: GithubRepoRef, branch: String): PrInfo? = fetch(listOf(ref), branch)

  override fun fetch(refs: List<GithubRepoRef>, branch: String): PrInfo? {
    val origin = refs.firstOrNull() ?: return null
    val all = accounts()
    val tried = mutableListOf<GithubRepoRef>()
    for (target in refs) {
      tried += target
      queryPulls(target, origin, branch, all)?.let { return it }
    }
    val parent = parentOf(origin, all) ?: return null
    if (tried.any { it.owner.equals(parent.owner, true) && it.repo.equals(parent.repo, true) }) return null
    return queryPulls(parent, origin, branch, all, hostRef = origin)
  }

  private fun queryPulls(target: GithubRepoRef, origin: GithubRepoRef, branch: String, all: List<GithubAccount>, hostRef: GithubRepoRef = target): PrInfo? =
    bestEffort {
      val account = GithubHosts.pick(hostRef, all, sshHosts) ?: return@bestEffort null
      val t = runBlockingMaybeCancellable { token(account) } ?: return@bestEffort null
      val url = PullsResponse.url(account.server.toApiUrl(), target, branch, origin.owner)
      api.get(account.server, t, url)?.let { PullsResponse.parse(it) }
    }.getOrNull()

  private fun parentOf(origin: GithubRepoRef, all: List<GithubAccount>): GithubRepoRef? = bestEffort {
    val account = GithubHosts.pick(origin, all, sshHosts) ?: return@bestEffort null
    val t = runBlockingMaybeCancellable { token(account) } ?: return@bestEffort null
    val body = api.get(account.server, t, "${account.server.toApiUrl().trimEnd('/')}/repos/${origin.owner}/${origin.repo}") ?: return@bestEffort null
    val parent = JsonParser.parseString(body).asJsonObject.get("parent")?.takeUnless { it.isJsonNull }?.asJsonObject ?: return@bestEffort null
    val owner = parent.getAsJsonObject("owner")?.get("login")?.asString ?: return@bestEffort null
    val name = parent.get("name")?.asString ?: return@bestEffort null
    GithubRepoRef(origin.host, owner, name)
  }.getOrNull()
}

/** Real lookup: remote -> repo ref -> fetch; no account, non-GitHub remote or any failure gives null. Off the EDT. */
class GithubPrTitles(
  private val remotes: RemoteSource = GitRemoteSource(),
  private val fetcher: PrFetcher = GithubPluginPrFetcher(),
) : PrTitles {
  override fun titleFor(repoPath: String, branch: String): String? = infoFor(repoPath, branch)?.title

  override fun infoFor(repoPath: String, branch: String): PrInfo? = bestEffort {
    val refs = remotes.githubRepos(repoPath)
    if (refs.isEmpty()) return null
    fetcher.fetch(refs, branch)
  }.getOrNull()
}
