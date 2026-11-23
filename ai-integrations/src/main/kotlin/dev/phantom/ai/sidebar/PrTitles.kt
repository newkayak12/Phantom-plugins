// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import dev.phantom.ai.core.bestEffort
import java.util.concurrent.ConcurrentHashMap

/** The open pull request of a branch: [number] is null when only a title is known; [url] is its web page when known. */
data class PrInfo(val number: Int?, val title: String, val url: String? = null)

/** Title (and number) of the pull request of a branch, or null. Fakeable; any failure is null. */
fun interface PrTitles {
  fun titleFor(repoPath: String, branch: String): String?

  /** Number and title; the default knows only the title. */
  fun infoFor(repoPath: String, branch: String): PrInfo? = titleFor(repoPath, branch)?.let { PrInfo(null, it) }
}

/** Caches [delegate] (also its nulls) for [ttlMillis]; a throwing delegate gives null. */
class CachedPrTitles(
  private val delegate: PrTitles,
  private val ttlMillis: Long = 5 * 60_000L,
  private val clock: () -> Long = System::currentTimeMillis,
) : PrTitles {
  private class Entry(val info: PrInfo?, val at: Long)

  private val cache = ConcurrentHashMap<String, Entry>()

  override fun titleFor(repoPath: String, branch: String): String? = infoFor(repoPath, branch)?.title

  override fun infoFor(repoPath: String, branch: String): PrInfo? {
    val key = "$repoPath\n$branch"
    val now = clock()
    cache[key]?.takeIf { now - it.at < ttlMillis }?.let { return it.info }
    val info = bestEffort { delegate.infoFor(repoPath, branch) }.getOrNull()
    cache[key] = Entry(info, now)
    return info
  }
}


/**
 * [delegate] without the wait: answers at once from what is known, a stale answer too, and looks a missing or stale one
 * up on [background], once however often it is asked, calling [loaded] when it arrives. A view's refresh then no longer
 * waits for git and GitHub once per worktree; the title shows on the refresh that [loaded] starts.
 */
class BackgroundPrTitles(
  private val delegate: PrTitles,
  private val background: (Runnable) -> Unit,
  private val loaded: () -> Unit,
  private val ttlMillis: Long = 5 * 60_000L,
  private val clock: () -> Long = System::currentTimeMillis,
) : PrTitles {
  private class Entry(val info: PrInfo?, val at: Long)

  private val cache = ConcurrentHashMap<String, Entry>()
  private val asked = ConcurrentHashMap.newKeySet<String>()

  override fun titleFor(repoPath: String, branch: String): String? = infoFor(repoPath, branch)?.title

  override fun infoFor(repoPath: String, branch: String): PrInfo? {
    val key = "$repoPath\n$branch"
    val known = cache[key]
    if ((known == null || clock() - known.at >= ttlMillis) && asked.add(key)) {
      background(Runnable {
        val info = bestEffort { delegate.infoFor(repoPath, branch) }.getOrNull()
        cache[key] = Entry(info, clock())
        asked.remove(key)
        if (info != known?.info) loaded()
      })
    }
    return known?.info
  }
}

/** The views' shared PR titles: one lookup per branch however many views ask; an arrival refreshes every view. */
object SharedPrTitles : PrTitles by BackgroundPrTitles(
  GithubPrTitles(),
  { com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(it) },
  { dev.phantom.ai.core.AiState.getInstance().changed() },
)
