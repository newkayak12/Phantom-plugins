// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.editor

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.intellij.util.containers.ContainerUtil
import dev.phantom.ai.api.AiSession
import dev.phantom.ai.api.AiWorktree
import dev.phantom.ai.api.PhantomAiApi
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.model.PathKey
import dev.phantom.ai.visualization.model.ProjectData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one reader of [PhantomAiApi] for every AI Visualization view. Editor tabs and tool windows of all projects show the same
 * IDE-wide data, so they share one debounced load and one model built on a pooled thread instead of each reading and building
 * their own. Worktree lists cost a git call per project: they are cached and read again only when the repository mapping changes,
 * when a change event leaves the sessions as they were (so it was about worktrees), or when a session names an unknown worktree.
 * Change events are ignored while no view is showing; a view coming back on screen asks for a load.
 */
class OfficeLoader(val api: () -> PhantomAiApi, private val parent: Disposable) {
  private class View(val showing: () -> Boolean, val onModel: (OfficeModel) -> Unit)

  private val views = ContainerUtil.createLockFreeCopyOnWriteList<View>()
  private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)
  private val worktrees = ConcurrentHashMap<Project, List<AiWorktree>>()
  private val subscribed = ConcurrentHashMap.newKeySet<Project>()
  private val changedSinceLoad = AtomicBoolean(false)
  @Volatile private var lastSessions: List<AiSession>? = null

  /** The last model built, delivered at once to a view that registers later. */
  @Volatile var model: OfficeModel? = null
    private set

  init {
    api().addChangeListener(parent) {
      changedSinceLoad.set(true)
      if (views.any { it.showing() }) request()
    }
  }

  /** Delivers every new model to [onModel] on the EDT until [disposable] is disposed, and loads the current state. */
  fun register(disposable: Disposable, showing: () -> Boolean, onModel: (OfficeModel) -> Unit) {
    val view = View(showing, onModel)
    views += view
    Disposer.register(disposable) { views.remove(view) }
    model?.let { m -> ApplicationManager.getApplication().invokeLater({ if (view in views) onModel(m) }, ModalityState.any()) }
    request()
  }

  /** Reads the data after a short delay; a newer request replaces an older one. */
  fun request() {
    if (Disposer.isDisposed(parent)) return
    alarm.cancelAllRequests()
    alarm.addRequest({ refreshNow() }, DEBOUNCE_MS)
  }

  private fun refreshNow() {
    if (views.isEmpty()) return
    val m = OfficeModel.build(load())
    model = m
    ApplicationManager.getApplication().invokeLater({ for (v in views) v.onModel(m) }, ModalityState.any())
  }

  private fun load(): List<ProjectData> {
    val a = api()
    val all = a.sessions()
    // AiChangeListener does not say what changed: unchanged sessions after an event mean a worktree was added, removed or renamed
    if (changedSinceLoad.getAndSet(false) && all == lastSessions) worktrees.clear()
    lastSessions = all
    val open = ProjectManager.getInstance().openProjects.filter { !it.isDefault && !it.isDisposed }
    worktrees.keys.retainAll(open.toSet())
    subscribed.retainAll(open.toSet())
    val known = HashSet<String>()
    var data = open.map { p -> read(a, p, all).also { d -> d.worktrees.mapTo(known) { PathKey.of(it.path) } } }
    // a session in a worktree the cache does not list yet: the worktree is new, read the lists again
    if (all.any { PathKey.of(it.worktreePath) !in known } && worktrees.isNotEmpty()) {
      worktrees.clear()
      data = open.map { read(a, it, all) }
    }
    return data
  }

  private fun read(a: PhantomAiApi, p: Project, all: List<AiSession>): ProjectData = try {
    val wts = worktrees[p] ?: a.worktrees(p).also { worktrees[p] = it; watch(p) }
    val keys = wts.mapTo(HashSet()) { PathKey.of(it.path) }
    // sessions(p) would list the worktrees through git a second time; filter the IDE-wide list by the ones read here
    ProjectData(keyOf(p), p.name, wts, all.filter { PathKey.of(it.worktreePath) in keys })
  } catch (e: ProcessCanceledException) {
    throw e
  } catch (e: Exception) {
    LOG.warn("AI Visualization: could not read ${p.name}", e)
    ProjectData(keyOf(p), p.name, emptyList(), emptyList())
  }

  /** Drops [p]'s cached worktrees when its VCS roots change (a repository added, removed or remapped). */
  private fun watch(p: Project) {
    if (!subscribed.add(p)) return
    p.messageBus.connect(parent).subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, VcsRepositoryMappingListener {
      worktrees.remove(p)
      if (views.any { it.showing() }) request()
    })
  }

  /** How many projects have a cached worktree list; for tests. */
  internal val cachedProjects: Int get() = worktrees.size

  companion object {
    private val LOG = logger<OfficeLoader>()
    const val DEBOUNCE_MS = 150

    /** Identifies a project in the model: its base path, else its name. */
    fun keyOf(p: Project): String = p.basePath ?: p.name

    fun getInstance(): OfficeLoader = service<OfficeLoaderService>().loader
  }
}

/** Holds the application's single [OfficeLoader]; disposing it stops the loader's listener and alarm. */
@Service(Service.Level.APP)
class OfficeLoaderService : Disposable {
  val loader = OfficeLoader({ PhantomAiApi.getInstance() }, this)

  override fun dispose() = Unit
}
