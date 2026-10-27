// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.ide.GeneralSettings
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.options.advanced.AdvancedSettingBean
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import com.intellij.ui.components.JBLabel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.HierarchyEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.SwingUtilities

private const val STRIP_CLASS = "dev.phantom.workspaces.core.ProjectTabStrip"
private const val SETTING = "phantom.workspaces.project.tabs"
private const val SAMPLE_MS = 3000L
// -PprobeKeepLayouts: keep the tool window layouts earlier runs saved (shows what they left), instead of a quarter width
private val NORMALIZE_LAYOUT = !java.lang.Boolean.getBoolean("phantom.probe.keepLayouts")

/**
 * Phantom Workspaces in a real IDE with two to four project frames: the strip in every frame, frames stacked at one
 * bounds/maximized/full-screen state through move, maximize, switch, setting off/on and a toolbar relayout, closing from
 * the strip. Single-frame baselines (setting off) are measured first in the same run. Every step records per-frame
 * [x,y,w,h,extendedState] in observed.final and, for stack/switch/setting/relayout steps, 100 ms samples for 3 s.
 *
 * Activation: a sandbox launched from Gradle is not the active macOS app (focus.diagnose tries in-process activation,
 * osascript and a Robot click). Where a user's frame would be activated by the OS (a newly opened frame at its
 * WINDOW_OPENED; a frame the user comes back to), the focus event JBR would post is posted on the system EventQueue and
 * the KeyboardFocusManager dispatches WINDOW_ACTIVATED to whatever listeners the frame has then ([postActivation]).
 * What the user sees in front is read from macOS (NSApp orderedWindows, [zOrder]).
 */
internal object WorkspacesScenario : ProbeScenario {
  override suspend fun run(ctx: ProbeContext) = WorkspacesRun(ctx).run()
}

private class WorkspacesRun(val ctx: ProbeContext) {
  private val report = ctx.report
  private val osActivations = CopyOnWriteArrayList<String>()
  private val stripHierarchy = CopyOnWriteArrayList<String>()
  private val watchedStrips = HashSet<Int>()

  // single-frame baselines (setting off), measured before any second frame opens
  private var baseMoveChanges = 0
  private var baseMaxOk = false
  private var baseMax: List<Int>? = null
  private var basePreMax: List<Int>? = null
  private var baseUnmax: List<Int>? = null
  private var baseFullScreen: Boolean? = null
  private var baseline: Map<String, Any?> = emptyMap()
  private var focusBaseline: ProbeInput.Focus? = null
  // each project's layout opened with the setting off (prep.savedFrames): name -> {inherited, normalized}
  private var layoutBaseline: Map<String, Any?> = emptyMap()

  // ---------------------------------------------------------------- helpers (EDT unless suspend)

  fun open(): List<Project> = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
  fun byName(name: String): Project? = open().firstOrNull { it.name == name }
  fun frame(p: Project): JFrame? = WindowManager.getInstance().getFrame(p)
  fun geomOf(f: Frame): List<Int> = f.bounds.let { listOf(it.x, it.y, it.width, it.height, f.extendedState) }
  fun geom(p: Project): List<Int>? = frame(p)?.let { geomOf(it) }
  fun final(): Map<String, List<Int>> = open().mapNotNull { p -> geom(p)?.let { p.name to it } }.toMap(sortedMapOf())
  fun allEqual(m: Map<String, List<Int>>): Boolean = m.size >= 2 && m.values.all { it == m.values.first() }
  fun fullScreen(p: Project): Boolean = frame(p)?.let { ProjectFrameHelper.getFrameHelper(it)?.isInFullScreen } == true
  fun isMax(g: List<Int>?): Boolean = g != null && g[4] and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH

  fun strip(p: Project): JComponent? {
    val root = frame(p)?.rootPane ?: return null
    fun walk(c: Component): JComponent? {
      if (c.javaClass.name == STRIP_CLASS) return c as JComponent
      if (c is Container) for (child in c.components) walk(child)?.let { return it }
      return null
    }
    return walk(root)
  }

  private fun call(o: Any?, name: String): Any? = o?.javaClass?.methods?.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(o)

  /** Tab components of a strip (children with a JBLabel) and their label text. */
  fun tabComponents(s: JComponent): List<Pair<String, JComponent>> = s.components.filterIsInstance<JComponent>().mapNotNull { c ->
    val label = c.components.filterIsInstance<JBLabel>().firstOrNull() ?: return@mapNotNull null
    label.text to c
  }

  fun stripInfo(p: Project): Map<String, Any?> {
    val s = strip(p) ?: return mapOf("found" to false)
    val root = frame(p)?.rootPane
    val r = if (root != null && s.parent != null) SwingUtilities.convertRectangle(s.parent, s.bounds, root) else s.bounds
    val modelTabs = (call(s, "getTabs") as? List<*>).orEmpty()
    return mapOf(
      "found" to true, "showing" to s.isShowing, "visible" to s.isVisible, "height" to s.height,
      "rect" to listOf(r.x, r.y, r.width, r.height),
      "titles" to tabComponents(s).map { it.first },
      "current" to modelTabs.firstOrNull { call(it, "isCurrent") == true }?.let { call(it, "getTitle") },
    )
  }

  /** The plugin's WindowStack (diagnostics only, never in expected): which frame it treats as front and the shared state. */
  fun pluginStack(): Map<String, Any?> = try {
    val d = PluginManagerCore.getPlugin(PluginId.getId("dev.phantom.workspaces"))
    val cls = d?.pluginClassLoader?.loadClass("dev.phantom.workspaces.core.ProjectTabsService")
    val service = cls?.let { ApplicationManager.getApplication().getServiceIfCreated(it) }
    val stack = service?.javaClass?.getDeclaredField("stack")?.apply { isAccessible = true }?.get(service)
    val front = call(stack, "getFront")
    val frontFrame = call(front, "getFrame") as? JFrame
    val shared = call(stack, "getShared")
    mapOf(
      "front" to open().firstOrNull { frame(it) === frontFrame }?.name,
      "shared" to shared?.let { s -> (call(s, "getBounds") as? Rectangle)?.let { listOf(it.x, it.y, it.width, it.height) } },
      "sharedMaximized" to call(shared, "getMaximized"), "sharedFullScreen" to call(shared, "getFullScreen"),
    )
  }
  catch (e: Throwable) {
    mapOf("error" to e.toString())
  }

  fun watchStrips() {
    for (p in open()) {
      val s = strip(p) ?: continue
      if (!watchedStrips.add(System.identityHashCode(s))) continue
      s.addHierarchyListener { e ->
        if (e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) {
          stripHierarchy.add("${p.name}:${if (s.isDisplayable) "addNotify" else "removeNotify"}@${System.currentTimeMillis()}")
        }
      }
    }
  }

  /** Our posted focus events (identity), so the WINDOW_ACTIVATED spy can tell them from the OS's. */
  private val posted = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AWTEvent, Boolean>())
  /** Frames with a posted GAINED_FOCUS whose WINDOW_ACTIVATED has not been dispatched yet. */
  private val pendingPosted: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
  /** Every WINDOW_ACTIVATED dispatched: frame, time, whether it came from our post, whether the plugin was listening then. */
  val activations = CopyOnWriteArrayList<Map<String, Any?>>()

  /**
   * What the OS does when a frame becomes key: JBR posts WINDOW_GAINED_FOCUS (after WINDOW_LOST_FOCUS of the app's
   * previous focused window, or with no opposite when the app was inactive) on the system EventQueue, and the
   * KeyboardFocusManager turns it into WINDOW_ACTIVATED for the frame's listeners. Posted here the same way; the plugin's
   * listener is never called directly. If [f] already is the focused window the app is first deactivated (LOST_FOCUS,
   * opposite null), as when the user goes to another app and comes back. Call on the EDT.
   */
  fun postActivation(f: java.awt.Window): Map<String, Any?> {
    val kfm = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val q = Toolkit.getDefaultToolkit().systemEventQueue
    val was = kfm.focusedWindow
    val events = ArrayList<String>()
    if (was === f || kfm.activeWindow === f) {
      val lost = WindowEvent(f, WindowEvent.WINDOW_LOST_FOCUS, null)
      posted.add(lost); q.postEvent(lost); events += "LOST_FOCUS(opposite=null)"
    }
    val gained = WindowEvent(f, WindowEvent.WINDOW_GAINED_FOCUS, if (was === f) null else was)
    pendingPosted.add(System.identityHashCode(f))
    posted.add(gained); q.postEvent(gained); events += "GAINED_FOCUS"
    return mapOf("via" to "eventQueue", "posted" to events, "focusedBefore" to (was?.let(::nameOf)), "pluginListeningAtPost" to hasPluginListener(f))
  }

  /**
   * The user has [p]'s frame in front and key before clicking in it: if macOS does not have it in front it is ordered
   * front by the platform (frame.toFront, plugin not involved), and the OS's activation of it is posted if it is not
   * the focused window. Returns what was done.
   */
  suspend fun userFront(p: Project): Map<String, Any?> {
    val z = zOrder()
    val f = ctx.edt { frame(p) } ?: return mapOf("error" to "no frame")
    val toFront = z["front"] != p.name
    var via: String? = null
    if (toFront) {
      // frame.toFront reorders this app's windows a little later; tried up to 4 times, then the platform's focus call
      val tries = ArrayList<String>()
      for (i in 1..4) {
        ctx.edt { f.toFront() }
        delay(700)
        val front = zOrder()["front"]
        tries += "toFront#$i->$front"
        if (front == p.name) break
      }
      if (zOrder()["front"] != p.name) { ctx.edt { ProjectUtil.focusProjectWindow(p, true) }; delay(1000); tries += "focusProjectWindow(p,true)->${zOrder()["front"]}" }
      via = tries.joinToString()
    }
    val act = ctx.edt { if (java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow === f) null else postActivation(f) }
    delay(500)
    return mapOf("frame" to p.name, "zFrontBefore" to z["front"], "toFront" to toFront, "via" to via, "activation" to act, "zFrontAfter" to zOrder()["front"])
  }

  fun nameOf(w: java.awt.Window): String = open().firstOrNull { frame(it) === w }?.name ?: w.javaClass.simpleName

  /** Activations dispatched since [from] (index into [activations]). */
  fun activationsSince(from: Int): List<Map<String, Any?>> = activations.drop(from)

  /** The app's project frames front to back as macOS orders them (NSApp orderedWindows; window server order too). */
  suspend fun zOrder(): Map<String, Any?> {
    if (!ProbeMac.isMac) return mapOf("error" to "not macOS")
    val frames = ctx.edt { open().mapNotNull { p -> frame(p)?.let { p.name to it } } }
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      ProbeMac.onMain {
        val nums = frames.associate { (n, f) -> ProbeMac.windowNumber(f) to n }
        val ordered = ProbeMac.orderedWindowNumbers().mapNotNull { nums[it] }
        val server = ProbeMac.serverWindowNumbers().mapNotNull { nums[it] }
        mapOf("front" to ordered.firstOrNull(), "ordered" to ordered, "serverFront" to server.firstOrNull(), "server" to server,
              "appActive" to ProbeMac.appActive(), "visible" to frames.associate { (n, f) -> n to ProbeMac.occlusionVisible(f) },
              "onActiveSpace" to frames.associate { (n, f) -> n to ProbeMac.onActiveSpace(f) })
      } ?: mapOf("error" to "main thread did not answer in 3 s")
    }
  }

  fun activeProject(): String? = open().firstOrNull { frame(it)?.isActive == true }?.name

  /** Bounds of every open frame every 100 ms for [ms]: {frame: [[ms,x,y,w,h,state],...]}. */
  suspend fun sample(ms: Long = SAMPLE_MS): Map<String, List<List<Int>>> {
    val out = sortedMapOf<String, MutableList<List<Int>>>()
    val t0 = System.currentTimeMillis()
    while (true) {
      val t = (System.currentTimeMillis() - t0).toInt()
      ctx.edt { for ((n, g) in final()) out.getOrPut(n) { ArrayList() }.add(listOf(t) + g) }
      if (t >= ms) break
      delay(100)
    }
    return out
  }

  fun dedup(seq: List<List<Int>>): List<List<Int>> {
    val q = ArrayList<List<Int>>()
    for (x in seq) { val b = x.drop(1); if (q.isEmpty() || q.last() != b) q.add(b) }
    return q
  }

  fun abaCount(samples: Map<String, List<List<Int>>>): Int =
    samples.values.sumOf { seq -> val q = dedup(seq); (0 until (q.size - 2).coerceAtLeast(0)).count { q[it] == q[it + 2] } }

  /** Changes of any frame after the first sample at which [settled] holds; every change if it never holds. */
  fun changesAfter(samples: Map<String, List<List<Int>>>, settled: (Map<String, List<Int>>) -> Boolean): Int {
    val n = samples.values.minOfOrNull { it.size } ?: return 0
    val at = (0 until n).firstOrNull { i -> settled(samples.mapValues { it.value[i].drop(1) }) } ?: 0
    return samples.values.sumOf { seq -> (at + 1 until seq.size).count { seq[it].drop(1) != seq[it - 1].drop(1) } }
  }

  /** One step; an exception inside [observe] becomes observed.error, so the step fails and the run goes on. */
  suspend fun rec(name: String, expected: Map<String, Any?>, env: String? = null, observe: suspend () -> Map<String, Any?>): Map<String, Any?> {
    val observed = try {
      observe()
    }
    catch (e: Throwable) {
      if (e is kotlinx.coroutines.CancellationException) throw e
      mapOf("error" to e.toString())
    }
    val withFinal = if ("final" in observed) observed else observed + ("final" to ctx.edt { final() })
    val stepEnv = env ?: (withFinal["env"] as? String)
    report.step(name, expected, withFinal - "env", stepEnv)
    return withFinal
  }

  /** Last open's timing: when the new frame was first showing and when the plugin's WindowListener was on it (ms from the call). */
  var openTiming: Map<String, Any?> = emptyMap()

  fun hasPluginListener(w: java.awt.Window) = w.windowListeners.any { it.javaClass.name.startsWith("dev.phantom.workspaces") }

  /** Whether opens post the OS's activation of the new frame at WINDOW_OPENED (see [postActivation]); off while preparing. */
  var activateOnOpen = true

  suspend fun openProject(name: String, newFrame: Boolean): Project? = kotlinx.coroutines.coroutineScope {
    val path = ctx.fixtures.resolve(name)
    val task = OpenProjectTask {
      forceOpenInNewFrame = newFrame
      showWelcomeScreen = false
    }
    val known = ctx.edt { java.awt.Window.getWindows().filter { it.isShowing }.toSet() }
    val t0 = System.currentTimeMillis()
    val shownAt = HashMap<java.awt.Window, Long>()
    val listenerAt = HashMap<java.awt.Window, Long>()
    val boundsAtShow = HashMap<java.awt.Window, List<Int>>()
    val movedAt = HashMap<java.awt.Window, Long>()
    val macAtShow = HashMap<java.awt.Window, Map<String, Any?>>()
    val openedPost = HashMap<java.awt.Window, Map<String, Any?>>()
    val paintAt = java.util.concurrent.ConcurrentHashMap<java.awt.Window, Long>()
    // the new frame's layout over time (changes only) and at its addNotify: bounds/state, isAboutToBeMaximized, Project tool window, editor
    val timeline = HashMap<java.awt.Window, MutableList<Map<String, Any?>>>()
    val atDisplayable = HashMap<java.awt.Window, Map<String, Any?>>()
    fun layoutNow(w: JFrame): Map<String, Any?> = layoutIn(w, ProjectFrameHelper.getFrameHelper(w)?.project)
    val displayable = AWTEventListener { e ->
      val w = e.source
      if (e is HierarchyEvent && e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && w is JFrame && w.isDisplayable && w !in known && w !in atDisplayable) {
        atDisplayable[w] = mapOf("atMs" to System.currentTimeMillis() - t0, "showing" to w.isShowing) + layoutNow(w)
      }
    }
    val activationsFrom = activations.size
    // the OS makes a newly shown frame key and JBR posts its focus event then; posted here at the frame's WINDOW_OPENED
    val opener = AWTEventListener { e ->
      val w = e.source
      if (e.id == java.awt.event.PaintEvent.PAINT || e.id == java.awt.event.PaintEvent.UPDATE) {
        (w as? Component)?.let { c -> (c as? java.awt.Window ?: SwingUtilities.getWindowAncestor(c)) }
          ?.takeIf { it is JFrame && it !in known }?.let { win -> paintAt.putIfAbsent(win, System.currentTimeMillis() - t0) }
      }
      if (e.id == WindowEvent.WINDOW_OPENED && w is JFrame && w !in known && w !in openedPost && activateOnOpen) {
        val realActive = ProbeMac.isMac && ProbeMac.appActive() == true
        openedPost[w] = if (realActive) mapOf("via" to "os (app really active)", "atMs" to System.currentTimeMillis() - t0)
                        else postActivation(w) + ("atMs" to System.currentTimeMillis() - t0)
      }
    }
    ctx.edt {
      Toolkit.getDefaultToolkit().addAWTEventListener(opener, AWTEvent.WINDOW_EVENT_MASK or AWTEvent.PAINT_EVENT_MASK)
      Toolkit.getDefaultToolkit().addAWTEventListener(displayable, AWTEvent.HIERARCHY_EVENT_MASK)
    }
    // polls every 10 ms: when the frame shows, when the plugin's listener is on it, when it first moves from where it showed
    val poll = launch {
      while (true) {
        val fresh = ctx.edt {
          val now = System.currentTimeMillis() - t0
          val newly = ArrayList<java.awt.Window>()
          for (w in java.awt.Window.getWindows()) {
            if (w !is JFrame || w in known || !w.isShowing) continue
            if (w !in shownAt) { shownAt[w] = now; boundsAtShow[w] = geomOf(w); newly += w }
            else if (w !in movedAt && geomOf(w) != boundsAtShow[w]) movedAt[w] = now
            if (w !in listenerAt && hasPluginListener(w)) listenerAt[w] = now
            val l = layoutNow(w)
            val seq = timeline.getOrPut(w) { ArrayList() }
            if (seq.isEmpty() || (seq.last() - "atMs") != l) seq.add(mapOf("atMs" to now) + l)
          }
          newly
        }
        for (w in fresh) if (ProbeMac.isMac) {
          macAtShow[w] = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ProbeMac.onMain { mapOf("occlusionVisible" to ProbeMac.occlusionVisible(w), "appTopmost" to (ProbeMac.orderedWindowNumbers().firstOrNull() == ProbeMac.windowNumber(w))) }
          }.orEmpty() + ("atMs" to (System.currentTimeMillis() - t0))
        }
        delay(10)
      }
    }
    val p = if (newFrame) ProjectManagerEx.getInstanceEx().openProjectAsync(path, task) else ProjectUtil.openOrImportAsync(path, task)
    ctx.waitUntil(30_000) { p != null && frame(p)?.isShowing == true && strip(p) != null }
    delay(400)
    poll.cancel()
    ctx.edt {
      Toolkit.getDefaultToolkit().removeAWTEventListener(opener)
      Toolkit.getDefaultToolkit().removeAWTEventListener(displayable)
    }
    val f: java.awt.Window? = p?.let { ctx.edt { frame(it) } }
    val fName = p?.name
    openTiming = mapOf(
      "project" to name, "frameShownMs" to shownAt[f], "pluginListenerMs" to listenerAt[f],
      "listenerAfterShownMs" to (listenerAt[f]?.let { l -> shownAt[f]?.let { l - it } }), "boundsWhenShown" to boundsAtShow[f],
      "firstMovedMs" to movedAt[f], "msAtShownBounds" to (movedAt[f]?.let { m -> shownAt[f]?.let { m - it } }), "boundsAfter" to (f as? Frame)?.let { ctx.edt { geomOf(it) } },
      "macAtShow" to macAtShow[f], "activationPost" to openedPost[f], "firstPaintEventMs" to paintAt[f],
      "atDisplayable" to atDisplayable[f], "layoutTimeline" to timeline[f],
      "activationsDispatched" to activationsSince(activationsFrom).filter { it["id"] == System.identityHashCode(f) }.map { a ->
        a + ("atMs" to ((a["at"] as Long) - t0)) - "at" - "id" + ("frame" to fName)
      },
    )
    p
  }

  suspend fun toggleFullScreen(p: Project, state: Boolean): Boolean {
    val helper = ctx.edt { frame(p)?.let { ProjectFrameHelper.getFrameHelper(it) } } ?: return false
    val job = ctx.edt { helper.toggleFullScreen(state) }
    withTimeoutOrNull(10_000) { job.join() }
    ctx.waitUntil(5_000) { helper.isInFullScreen == state }
    delay(800)
    return ctx.edt { helper.isInFullScreen } == state
  }

  /**
   * A user's click of [button] on [c], posted through the system event queue: the pointer enters and moves first, and
   * the press comes 250 ms later, as a person's does (the platform's buttons ignore a press within 150 ms of the pointer
   * entering, TimedDeadzone.DEFAULT, once the app has a focus owner).
   */
  suspend fun click(c: Component, button: Int) {
    hover(c)
    delay(250)
    press(c, button)
  }

  /** The pointer enters [c] and moves over its middle (MOUSE_ENTERED, MOUSE_MOVED on the system EventQueue). */
  suspend fun hover(c: Component) {
    val p = Point(c.width / 2, c.height / 2)
    val q = Toolkit.getDefaultToolkit().systemEventQueue
    fun ev(id: Int) = MouseEvent(c, id, System.currentTimeMillis(), 0, p.x, p.y, 0, false, 0)
    ctx.edt { q.postEvent(ev(MouseEvent.MOUSE_ENTERED)); q.postEvent(ev(MouseEvent.MOUSE_MOVED)) }
  }

  /** PRESSED, RELEASED and CLICKED of [button] in the middle of [c], on the system EventQueue. */
  suspend fun press(c: Component, button: Int) {
    val p = Point(c.width / 2, c.height / 2)
    val mask = when (button) {
      MouseEvent.BUTTON2 -> InputEvent.BUTTON2_DOWN_MASK
      MouseEvent.BUTTON3 -> InputEvent.BUTTON3_DOWN_MASK
      else -> InputEvent.BUTTON1_DOWN_MASK
    }
    val q = Toolkit.getDefaultToolkit().systemEventQueue
    fun ev(id: Int, mods: Int) = MouseEvent(c, id, System.currentTimeMillis(), mods, p.x, p.y, 1, false, button)
    ctx.edt {
      q.postEvent(ev(MouseEvent.MOUSE_PRESSED, mask))
      q.postEvent(ev(MouseEvent.MOUSE_RELEASED, 0))
      q.postEvent(ev(MouseEvent.MOUSE_CLICKED, 0))
    }
  }

  fun tabIn(stripOf: Project, title: String): JComponent? = strip(stripOf)?.let { s -> tabComponents(s).firstOrNull { it.first == title }?.second }

  fun closeButton(tab: JComponent): JComponent? =
    tab.components.filterIsInstance<JComponent>().firstOrNull { it.javaClass.name == "com.intellij.ui.InplaceButton" }

  fun paint(p: Project, png: String): Map<String, Any?> = ProbeSnapshot.paint(p, ctx.out, png)

  fun screen(): Rectangle = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds

  /** IdeFrameImpl.isAboutToBeMaximized (internal @JvmField; diagnostics only): while set, ToolWindowPane.setWeight postpones. */
  fun aboutToBeMaximized(f: java.awt.Window): Boolean? = try {
    generateSequence<Class<*>>(f.javaClass) { it.superclass }.firstNotNullOfOrNull { c -> c.declaredFields.firstOrNull { it.name == "isAboutToBeMaximized" } }
      ?.apply { isAccessible = true }?.getBoolean(f)
  }
  catch (_: Throwable) {
    null
  }

  fun projectToolWindow(p: Project): ToolWindowEx? =
    p.takeUnless { it.isDisposed }?.getServiceIfCreated(ToolWindowManager::class.java)?.getToolWindow(ToolWindowId.PROJECT_VIEW) as? ToolWindowEx

  /**
   * What the user sees of [p]'s frame layout, in root-pane pixels: the Project tool window (its decorator) and the editor
   * area, and the Project tool window's share of the root pane width (what ToolWindowPane.setWeight sizes it from).
   */
  fun layoutOf(p: Project): Map<String, Any?> = frame(p)?.let { layoutIn(it, p) } ?: mapOf("frame" to false)

  /** [layoutOf] for a frame whose project may not be assigned yet (services not created yet are not created). */
  fun layoutIn(f: JFrame, p: Project?): Map<String, Any?> {
    val root = f.rootPane
    fun rect(c: Component?): List<Int>? = c?.takeIf { it.isShowing && it.parent != null }
      ?.let { SwingUtilities.convertRectangle(it.parent, it.bounds, root) }?.let { listOf(it.x, it.y, it.width, it.height) }
    val tw = p?.let(::projectToolWindow)
    val deco = try { tw?.decorator } catch (_: Throwable) { null }
    val project = rect(deco)
    val editors = p?.takeUnless { it.isDisposed }?.getServiceIfCreated(com.intellij.openapi.fileEditor.FileEditorManager::class.java) as? FileEditorManagerEx
    val editor = rect(editors?.component)
    return mapOf(
      "rootWidth" to root.width, "frame" to geomOf(f), "aboutToBeMaximized" to aboutToBeMaximized(f),
      "projectVisible" to (tw?.isVisible == true && deco?.isShowing == true), "projectRect" to project, "editorRect" to editor,
      "projectRatio" to project?.let { if (root.width > 0) Math.round(it[2] * 1000.0 / root.width) / 1000.0 else null },
    )
  }

  /**
   * Gives [p]'s Project tool window a quarter of the root pane width (ToolWindowEx.stretchWidth), which the platform records as
   * its weight 100 ms later; the probe's sandbox keeps each project's layout from run to run, so every run starts from this.
   */
  suspend fun normalizeProjectWidth(p: Project): Map<String, Any?> {
    val inherited = ctx.edt { layoutOf(p) }
    if (!NORMALIZE_LAYOUT) return mapOf("inherited" to inherited, "normalized" to inherited)
    ctx.edt { projectToolWindow(p)?.let { if (!it.isVisible) it.show() } }
    ctx.waitUntil(3000) { projectToolWindow(p)?.decorator?.isShowing == true }
    for (i in 1..3) {
      val done = ctx.edt {
        val tw = projectToolWindow(p) ?: return@edt true
        val w = frame(p)?.rootPane?.width ?: return@edt true
        val delta = w / 4 - tw.decorator.width
        if (kotlin.math.abs(delta) > 2) tw.stretchWidth(delta)
        kotlin.math.abs(delta) <= 2
      }
      delay(400)
      if (done) break
    }
    return mapOf("inherited" to inherited, "normalized" to ctx.edt { layoutOf(p) })
  }

  /**
   * The editor area's empty-state text in [png] (root-pane pixels) inside [editorRect]: "ink" is a pixel at least 40 brighter
   * or darker than the band's most common colour in the middle third of the editor's height. Holds when there is ink and
   * none of it is within 16 px of the editor's left or right edge, so the text is shown whole, not clipped by a narrow editor.
   */
  fun editorText(png: String, editorRect: List<Int>?): Map<String, Any?> {
    val r = editorRect ?: return mapOf("ok" to false, "reason" to "no editor area")
    val file = ctx.out.resolve(png).toFile()
    val img = (if (file.isFile) javax.imageio.ImageIO.read(file) else null) ?: return mapOf("ok" to false, "reason" to "no $png")
    val x0 = r[0].coerceAtLeast(0)
    val x1 = (r[0] + r[2]).coerceAtMost(img.width)
    val y0 = (r[1] + r[3] / 3).coerceAtLeast(0)
    val y1 = (r[1] + 2 * r[3] / 3).coerceAtMost(img.height)
    if (x1 - x0 < 1 || y1 - y0 < 1) return mapOf("ok" to false, "reason" to "empty band", "band" to listOf(x0, y0, x1 - x0, y1 - y0))
    val counts = HashMap<Int, Int>()
    for (y in y0 until y1) for (x in x0 until x1) counts.merge(img.getRGB(x, y) and 0xffffff, 1, Int::plus)
    val bg = counts.maxBy { it.value }.key
    fun lum(c: Int) = (c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114
    var inkMin = Int.MAX_VALUE
    var inkMax = -1
    for (y in y0 until y1) for (x in x0 until x1) {
      if (kotlin.math.abs(lum(img.getRGB(x, y) and 0xffffff) - lum(bg)) >= 40_000) { inkMin = minOf(inkMin, x); inkMax = maxOf(inkMax, x) }
    }
    val inside = inkMax >= 0 && inkMin >= x0 + 16 && inkMax <= x1 - 1 - 16
    return mapOf("ok" to inside, "band" to listOf(x0, y0, x1 - x0, y1 - y0), "background" to String.format("#%06x", bg),
                 "ink" to if (inkMax >= 0) listOf(inkMin, inkMax) else null, "colours" to counts.size)
  }

  /**
   * layout.newFrame.*: [p]'s frame, opened with the setting on, against the same project opened with the setting off in
   * prep.savedFrames ([layoutBaseline]): the Project tool window is shown, its share of the width is within 1.5x of the
   * platform's, the editor area is at least 300 px wide and its empty-state text in [png] is shown whole.
   */
  fun layoutCheck(p: Project, png: String, timing: Map<String, Any?>): Map<String, Any?> {
    val now = layoutOf(p)
    @Suppress("UNCHECKED_CAST") val base = (layoutBaseline[p.name] as? Map<String, Any?>)?.get("normalized") as? Map<String, Any?>
    val ratio = now["projectRatio"] as? Double
    val baseRatio = base?.get("projectRatio") as? Double
    @Suppress("UNCHECKED_CAST") val editor = now["editorRect"] as? List<Int>
    val text = editorText(png, editor)
    return mapOf(
      "projectToolWindowVisible" to (now["projectVisible"] == true),
      "projectWidthAtLeast100" to (((now["projectRect"] as? List<*>)?.get(2) as? Int ?: 0) >= 100),
      "projectWidthWithinBaseline" to (ratio != null && baseRatio != null && baseRatio > 0 && ratio / baseRatio in (1 / 1.5)..1.5),
      "editorWidthAtLeast300" to ((editor?.get(2) ?: 0) >= 300),
      "editorTextShownWhole" to (text["ok"] == true),
      "layout" to now, "baseline" to base, "baselineOf" to "prep.savedFrames: the same project opened with the setting off",
      "alpha" to byName("alpha")?.let(::layoutOf), "editorText" to text, "png" to png,
      "pixels" to listOfNotNull((text["band"] as? List<*>)?.let { mapOf("kind" to "nonuniform", "min" to 4, "a" to mapOf("png" to png, "rect" to it)) }),
      "openTimeline" to timing["layoutTimeline"], "atDisplayable" to timing["atDisplayable"],
    )
  }

  // ---------------------------------------------------------------- scenario

  suspend fun run() {
    val spy = AWTEventListener { e ->
      if (e.id == WindowEvent.WINDOW_ACTIVATED) {
        val w = e.source as java.awt.Window
        val name = nameOf(w)
        val fromPost = pendingPosted.remove(System.identityHashCode(w))
        if (!fromPost) osActivations.add("$name@${System.currentTimeMillis()}")
        activations.add(mapOf("frame" to name, "id" to System.identityHashCode(w), "at" to System.currentTimeMillis(), "source" to (if (fromPost) "posted" else "os/platform"),
                              "pluginListening" to hasPluginListener(w)))
      }
    }
    Toolkit.getDefaultToolkit().addAWTEventListener(spy, AWTEvent.WINDOW_EVENT_MASK)
    val oldConfirm = GeneralSettings.getInstance().confirmOpenNewProject
    val ui = UISettings.getInstance()
    val oldToolbar = ui.showMainToolbar
    val oldNavbar = ui.showNavigationBar
    try {
      body()
    }
    finally {
      Toolkit.getDefaultToolkit().removeAWTEventListener(spy)
      ctx.edt {
        GeneralSettings.getInstance().confirmOpenNewProject = oldConfirm
        AdvancedSettings.setBoolean(SETTING, true)
        if (ui.showMainToolbar != oldToolbar || ui.showNavigationBar != oldNavbar) {
          ui.showMainToolbar = oldToolbar
          ui.showNavigationBar = oldNavbar
          ui.fireUISettingsChanged()
        }
      }
      // leave alpha alone so the next run starts with one project
      for (p in ctx.edt { open().filter { it.name != "alpha" } }) {
        if (ctx.edt { fullScreen(p) }) toggleFullScreen(p, false)
        ProjectManagerEx.getInstanceEx().forceCloseProjectAsync(p)
      }
      ctx.edt { byName("alpha") }?.let { a ->
        if (ctx.edt { fullScreen(a) }) toggleFullScreen(a, false)
        // back where this run found it, so runs do not drift
        @Suppress("UNCHECKED_CAST") (baseline["orig"] as? List<Int>)?.let { o -> ctx.edt { frame(a)?.bounds = Rectangle(o[0], o[1], o[2], o[3]) } }
      }
    }
  }

  private suspend fun body() {
    // projects reopened from an earlier run are closed first, and reopening is switched off for the next run
    val closedAtStart = ArrayList<String>()
    ctx.edt { GeneralSettings.getInstance().isReopenLastProject = false }
    for (p in ctx.edt { open().filter { it.name != "alpha" } }) {
      closedAtStart += p.name
      ProjectManagerEx.getInstanceEx().forceCloseProjectAsync(p)
    }
    ctx.waitUntil { byName("alpha")?.let { frame(it)?.isShowing } == true }
    val alpha = ctx.edt { byName("alpha") } ?: error("project alpha is not open")
    ctx.edt { AdvancedSettings.setBoolean(SETTING, true) }

    rec("setting.registered", mapOf("registered" to true, "groupText" to "Phantom Workspaces")) {
      ctx.edt {
        val bean = AdvancedSettingBean.EP_NAME.extensionList.firstOrNull { it.id == SETTING }
        mapOf(
          "registered" to (bean != null), "groupText" to bean?.group(), "title" to bean?.title(), "description" to bean?.description(),
          "pluginId" to bean?.getPluginDescriptor()?.pluginId?.idString, "value" to AdvancedSettings.getBoolean(SETTING),
          "openProjects" to open().map { it.name }, "closedAtStart" to closedAtStart,
        )
      }
    }

    baselines(alpha)

    // ------------------------------------------------------------ beta in a new frame
    prepareSavedFrames(alpha)
    val alphaBeforeBeta = ctx.edt { geom(alpha) }
    val beta = openProject("beta", newFrame = true) ?: error("beta did not open")
    val betaTiming = openTiming
    // the OS's activation of the new frame was posted on the EventQueue at its WINDOW_OPENED (openTiming.activationPost)
    val betaActivation = betaTiming["activationPost"]
    val atOpen = ctx.edt { final() }
    val stackAtOpen = ctx.edt { pluginStack() }
    val openSamples = sample()
    ctx.waitUntil(5_000) { strip(alpha)?.isShowing == true && strip(beta)?.isShowing == true }
    ctx.edt { watchStrips() }
    focusDiagnose(beta)

    rec("strip.visible", mapOf("allStripsShowing" to true, "allStripHeightsPositive" to true, "stripPixelsNonuniform" to true)) {
      val pngs = ctx.edt { listOf(alpha, beta).associate { it.name to paint(it, "strip-${it.name}.png") } }
      ctx.edt {
        val infos = listOf(alpha, beta).associate { it.name to stripInfo(it) }
        val pixels = infos.map { (n, i) ->
          @Suppress("UNCHECKED_CAST") val r = (i["rect"] as? List<Int>) ?: listOf(0, 0, 0, 0)
          // an invisible strip has no area: a 1-pixel rect at its place, which is uniform, so the assertion fails as it should
          mapOf("kind" to "nonuniform", "min" to 4, "a" to mapOf("png" to "strip-$n.png", "rect" to listOf(r[0], r[1], r[2].coerceAtLeast(1), r[3].coerceAtLeast(1))))
        }
        mapOf(
          "allStripsShowing" to infos.values.all { it["showing"] == true },
          "allStripHeightsPositive" to infos.values.all { (it["height"] as? Int ?: 0) > 0 },
          "stripPixelsNonuniform" to pixels.all { px -> nonuniform(px) },
          "strips" to infos, "pngs" to pngs, "pixels" to pixels,
        )
      }
    }

    rec("strip.titles", mapOf(
      "titlesPerFrame" to mapOf("alpha" to listOf("alpha", "beta"), "beta" to listOf("alpha", "beta")),
      "currentPerFrame" to mapOf("alpha" to "alpha", "beta" to "beta"),
    )) {
      ctx.edt {
        val infos = listOf(alpha, beta).associate { it.name to stripInfo(it) }
        mapOf("titlesPerFrame" to infos.mapValues { it.value["titles"] }, "currentPerFrame" to infos.mapValues { it.value["current"] })
      }
    }

    // what the user sees inside the new frame: tool windows and editor laid out as the platform does with the setting off
    rec("layout.newFrame.beta", mapOf("projectToolWindowVisible" to true, "projectWidthAtLeast100" to true, "projectWidthWithinBaseline" to true, "editorWidthAtLeast300" to true, "editorTextShownWhole" to true)) {
      ctx.edt { layoutCheck(beta, "strip-beta.png", betaTiming) }
    }

    rec("stack.open", mapOf("allFramesEqual" to true)) {
      mapOf(
        "allFramesEqual" to allEqual(atOpen), "final" to atOpen, "samples" to openSamples, "alphaBeforeBeta" to alphaBeforeBeta,
        "activation" to betaActivation, "timing" to betaTiming, "plugin" to stackAtOpen, "osActivations" to osActivations.toList(),
        "activationsDispatched" to betaTiming["activationsDispatched"],
      )
    }

    rec("stack.settled", mapOf("allFramesEqual" to true)) {
      delay(1000)
      val s = sample()
      ctx.edt { final().let { mapOf("allFramesEqual" to allEqual(it), "final" to it, "samples" to s, "plugin" to pluginStack()) } }
    }

    // ------------------------------------------------------------ move the front frame (beta, the one the user sees)
    var moveSamples: Map<String, List<List<Int>>> = emptyMap()
    rec("stack.move", mapOf("allFramesEqual" to true, "frontAtRequestedBounds" to true)) {
      // beta is the frame the user has in front (opened last, on top: zOrderBefore); its activation was posted at open
      val activation = "posted at beta's WINDOW_OPENED (stack.open observed.activation)"
      val zBefore = zOrder()
      val pluginBefore = ctx.edt { pluginStack() }
      val requested = ctx.edt {
        val b = frame(beta)!!.bounds
        val r = Rectangle(b.x + 60, b.y + 40, (b.width - 120).coerceAtLeast(700), (b.height - 80).coerceAtLeast(500))
        frame(beta)!!.bounds = r // what a drag and a resize of the window end with
        r
      }
      moveSamples = sample()
      ctx.edt {
        val f = final()
        mapOf(
          "allFramesEqual" to allEqual(f), "frontAtRequestedBounds" to (f["beta"] == listOf(requested.x, requested.y, requested.width, requested.height, 0)),
          "requested" to listOf(requested.x, requested.y, requested.width, requested.height), "front" to "beta", "activation" to activation,
          "final" to f, "samples" to moveSamples, "plugin" to pluginStack(), "pluginBefore" to pluginBefore, "zOrderBefore" to zBefore,
          "via" to "frame.setBounds",
        )
      }
    }

    // the same move once the plugin has seen beta activated (as after the user clicks another window and back)
    // the user comes back to beta (the OS activates it): beta should stay where the user put it, the others follow it
    rec("stack.activate.keepsBounds", mapOf("activatedFrameMoved" to false, "allFramesEqual" to true)) {
      val before = ctx.edt { final() }
      val pluginBefore = ctx.edt { pluginStack() }
      val from = activations.size
      // the user goes to another app and comes back to beta: LOST_FOCUS (no opposite), then GAINED_FOCUS, both posted
      val activation = ctx.edt { postActivation(frame(beta)!!) }
      val s = sample(1500)
      ctx.edt {
        val f = final()
        mapOf(
          "activatedFrameMoved" to (f["beta"] != before["beta"]), "allFramesEqual" to allEqual(f), "before" to before,
          "activation" to activation, "activationsDispatched" to activationsSince(from), "pluginBefore" to pluginBefore,
          "final" to f, "samples" to s, "plugin" to pluginStack(),
        )
      }
    }

    var afterMoveSamples: Map<String, List<List<Int>>> = emptyMap()
    rec("stack.move.afterActivation", mapOf("allFramesEqual" to true, "frontAtRequestedBounds" to true)) {
      val from = activations.size
      val activation = ctx.edt { postActivation(frame(beta)!!) }
      val afterActivation = sample(500)
      val requested = ctx.edt {
        val b = frame(beta)!!.bounds
        val r = Rectangle(b.x + 20, b.y + 10, b.width + 40, b.height + 20)
        frame(beta)!!.bounds = r
        r
      }
      val s = sample()
      afterMoveSamples = s
      ctx.edt {
        val f = final()
        mapOf(
          "allFramesEqual" to allEqual(f), "frontAtRequestedBounds" to (f["beta"] == listOf(requested.x, requested.y, requested.width, requested.height, 0)),
          "requested" to listOf(requested.x, requested.y, requested.width, requested.height), "activation" to activation,
          "activationsDispatched" to activationsSince(from), "samplesAfterActivation" to afterActivation, "final" to f, "samples" to s, "plugin" to pluginStack(), "via" to "frame.setBounds",
        )
      }
    }

    rec("stack.noOscillation", mapOf("abaCount" to 0, "changesAfterSettleOverBaseline" to 0)) {
      // the move that the plugin propagates (after activation); stack.move's own samples are counted for A-B-A too
      val on = changesAfter(afterMoveSamples) { allEqual(it) }
      mapOf(
        "abaCount" to abaCount(moveSamples) + abaCount(afterMoveSamples), "changesAfterSettleOverBaseline" to (on - baseMoveChanges).coerceAtLeast(0),
        "changesAfterSettle" to on, "baselineChangesAfterSettle" to baseMoveChanges, "of" to "stack.move.afterActivation samples (+ stack.move for A-B-A)",
        "samples" to afterMoveSamples,
      )
    }

    // ------------------------------------------------------------ maximize / un-maximize the front frame
    val zoomEnv = if (baseMaxOk) null else "zoom-unavailable"
    val zoomBaseline = mapOf("ok" to baseMaxOk, "preMax" to basePreMax, "max" to baseMax, "unmax" to baseUnmax)
    var betaPreMax: List<Int>? = null
    rec("stack.maximize", mapOf("allFramesEqual" to true, "frontMatchesSingleFrameBaseline" to true), zoomEnv) {
      betaPreMax = ctx.edt { geom(beta) }
      ctx.edt { frame(beta)!!.let { it.extendedState = it.extendedState or Frame.MAXIMIZED_BOTH } }
      ctx.waitUntil(3000) { isMax(geom(beta)) }
      val s = sample()
      ctx.edt {
        val f = final()
        mapOf(
          "allFramesEqual" to allEqual(f), "frontMatchesSingleFrameBaseline" to (f["beta"] == baseMax),
          "front" to "beta", "frontPreMax" to betaPreMax, "baseline" to zoomBaseline, "final" to f, "samples" to s, "plugin" to pluginStack(),
        )
      }
    }

    rec("stack.unmaximize", mapOf("allFramesEqual" to true, "frontMatchesSingleFrameBaseline" to true), zoomEnv) {
      ctx.edt { frame(beta)!!.let { it.extendedState = it.extendedState and Frame.MAXIMIZED_BOTH.inv() } }
      ctx.waitUntil(3000) { !isMax(geom(beta)) }
      val s = sample()
      ctx.edt {
        val f = final()
        val front = f["beta"]
        val baseBack = baseUnmax?.take(4) == basePreMax?.take(4)
        val frontBack = front?.take(4) == betaPreMax?.take(4)
        mapOf(
          "allFramesEqual" to allEqual(f),
          "frontMatchesSingleFrameBaseline" to (front?.get(4) == baseUnmax?.get(4) && frontBack == baseBack),
          "frontBackToPreMax" to frontBack, "baselineBackToPreMax" to baseBack, "front" to "beta", "frontPreMax" to betaPreMax,
          "baseline" to zoomBaseline, "final" to f, "samples" to s, "plugin" to pluginStack(),
        )
      }
    }

    // ------------------------------------------------------------ a third frame
    val gamma = openProject("gamma", newFrame = true)
    val gammaTiming = openTiming
    var deltaTiming: Map<String, Any?> = emptyMap()
    rec("stack.third", mapOf("allFramesEqual" to true)) {
      gamma ?: error("gamma did not open")
      val timing = openTiming
      val activation = timing["activationPost"]
      val s = sample()
      ctx.edt { watchStrips() }
      ctx.edt {
        val f = final()
        mapOf("allFramesEqual" to allEqual(f), "frames" to f.size, "activation" to activation, "timing" to timing, "final" to f, "samples" to s,
              "plugin" to pluginStack(), "png" to paint(gamma, "third-gamma.png"))
      }
    }

    rec("layout.newFrame.gamma", mapOf("projectToolWindowVisible" to true, "projectWidthAtLeast100" to true, "projectWidthWithinBaseline" to true, "editorWidthAtLeast300" to true, "editorTextShownWhole" to true)) {
      gamma ?: error("gamma did not open")
      ctx.edt { layoutCheck(gamma, "third-gamma.png", gammaTiming) }
    }

    // ------------------------------------------------------------ a fourth project through the normal open path
    rec("open.normalPath", mapOf("newFrame" to true, "stripShowingInNewFrame" to true, "allFramesEqual" to true)) {
      makeFixture("delta")
      val framesBefore = ctx.edt { open().mapNotNull { frame(it) }.toSet() }
      var timing: Map<String, Any?> = emptyMap()
      ctx.edt { GeneralSettings.getInstance().confirmOpenNewProject = GeneralSettings.OPEN_PROJECT_NEW_WINDOW }
      val delta = openProject("delta", newFrame = false).also { timing = openTiming; deltaTiming = openTiming }
      ctx.waitUntil(30_000) { delta != null && frame(delta)?.isShowing == true }
      ctx.waitUntil(5_000) { delta != null && strip(delta)?.isShowing == true }
      val activation = timing["activationPost"]
      val s = sample()
      ctx.edt { watchStrips() }
      ctx.edt {
        val f = final()
        val df = delta?.let(::frame)
        mapOf(
          "newFrame" to (df != null && df !in framesBefore && df.isShowing), "stripShowingInNewFrame" to (delta?.let { strip(it)?.isShowing } == true),
          "allFramesEqual" to allEqual(f), "openProjects" to open().map { it.name }, "activation" to activation, "timing" to timing,
          "via" to "ProjectUtil.openOrImportAsync, confirmOpenNewProject=OPEN_PROJECT_NEW_WINDOW", "final" to f, "samples" to s,
          "plugin" to pluginStack(), "strip" to delta?.let(::stripInfo),
        )
      }
    }

    rec("layout.newFrame.delta", mapOf("projectToolWindowVisible" to true, "projectWidthAtLeast100" to true, "projectWidthWithinBaseline" to true, "editorWidthAtLeast300" to true, "editorTextShownWhole" to true)) {
      val delta = ctx.edt { byName("delta") } ?: error("delta did not open")
      ctx.edt { paint(delta, "open-delta.png") }
      ctx.edt { layoutCheck(delta, "open-delta.png", deltaTiming) }
    }

    // ------------------------------------------------------------ the open-time jump (user report: "frames jumping")
    rec("stack.open.noJump", mapOf("shownAtStackBounds" to true)) {
      val per = linkedMapOf("beta" to betaTiming, "gamma" to gammaTiming, "delta" to deltaTiming).mapValues { (_, t) ->
        mapOf(
          "boundsWhenShown" to t["boundsWhenShown"], "boundsAfterAttach" to t["boundsAfter"], "jumped" to (t["boundsWhenShown"] != t["boundsAfter"]),
          "msAtShownBounds" to t["msAtShownBounds"], "frameShownMs" to t["frameShownMs"], "firstMovedMs" to t["firstMovedMs"],
          "firstPaintEventMs" to t["firstPaintEventMs"], "listenerAfterShownMs" to t["listenerAfterShownMs"], "macAtShow" to t["macAtShow"],
        )
      }
      mapOf("shownAtStackBounds" to per.values.none { it["jumped"] == true }, "frames" to per,
            "of" to "openProject 10 ms poll: bounds at first isShowing vs 400 ms after the strip attached")
    }

    // ------------------------------------------------------------ switching
    // what the user sees is which frame macOS has in front: NSApp orderedWindows before and after (zOrder*)
    var clickZ: Pair<Map<String, Any?>, Map<String, Any?>>? = null
    rec("switch.click", mapOf("activeProject" to "beta", "allFramesEqual" to true)) {
      val from = ctx.edt { byName("delta") ?: gamma ?: alpha }
      // a user's click makes the app active: the sandbox is made frontmost first (other apps are left running)
      val foreground = keepFrontmost()
      // the user clicks in the frame they have in front: delta is put in front and key first (not by the plugin)
      val precondition = userFront(from)
      val tab = ctx.edt { tabIn(from, "beta") } ?: error("no beta tab in ${from.name}'s strip")
      hover(tab)
      delay(250)
      // and again right before the press: another app (Orca, a Slack notification) takes the foreground back within ~0.6 s
      val foregroundAtInput = keepFrontmost()
      val before = ctx.edt { mapOf("active" to activeProject(), "plugin" to pluginStack()) }
      val zBefore = zOrder()
      val atInput = frontmost()
      val t0 = System.currentTimeMillis()
      press(tab, MouseEvent.BUTTON1)
      // the switch's outcome is read once it has settled (or 3 s); what other apps do later is in the timeline
      ctx.waitUntil(3000) { frame(beta)?.isActive == true }
      val settledMs = System.currentTimeMillis() - t0
      val active = ctx.edt { activeProject() }
      val afterInput = frontmost()
      val timeline = activationTimeline(t0, 3000) { frame(beta)?.isActive == true }
      val s = sample()
      val zAfter = zOrder()
      clickZ = zBefore to zAfter
      val activeAfterSamples = ctx.edt { activeProject() }
      val env = envFor(active == "beta", beta, from, zBefore["appActive"] as? Boolean)
      ctx.edt {
        val f = final()
        mapOf(
          "activeProject" to active, "allFramesEqual" to allEqual(f), "via" to ProbeInput.VIA_QUEUE, "clickedIn" to from.name,
          "foreground" to mapOf("keepFrontmost" to foreground, "keepFrontmostAtInput" to foregroundAtInput, "atInput" to atInput, "afterInput" to afterInput, "timeline" to timeline),
          "settledMs" to settledMs, "activeAfterSamples" to activeAfterSamples,
          "before" to before, "precondition" to precondition, "plugin" to pluginStack(), "zOrderBefore" to zBefore, "zOrderAfter" to zAfter,
          "final" to f, "samples" to s, "env" to env.first, "baseline" to env.second,
        )
      }
    }

    // what the user sees: delta in front (checked), a click on beta's tab in delta's strip, then which frame macOS has in
    // front. The plugin's switch ends in ProjectUtil.focusProjectWindow(project) (FrameWindow.activate); that platform call
    // alone, from the same start, is recorded as the baseline of what this environment does with it
    rec("switch.click.front", mapOf("frontByZOrder" to "beta", "frontChanged" to true, "allFramesEqual" to true)) {
      val (zb0, za0) = clickZ ?: error("switch.click did not run")
      val from = ctx.edt { byName("delta") ?: gamma ?: alpha }
      val foreground = keepFrontmost()
      val reset = userFront(from)
      val zb = zOrder()
      if (zb["front"] != from.name) error("precondition: ${from.name} could not be put in front (${reset["via"]})")
      val pluginBefore = ctx.edt { pluginStack() }
      val tab = ctx.edt { tabIn(from, "beta") } ?: error("no beta tab in ${from.name}'s strip")
      click(tab, MouseEvent.BUTTON1)
      delay(1500)
      val za = zOrder()
      val pluginAfter = ctx.edt { pluginStack() }
      val reset2 = userFront(from)
      val zReset = zOrder()
      ctx.edt { ProjectUtil.focusProjectWindow(beta) }
      delay(1500)
      val zPlatform = zOrder()
      ctx.edt {
        val f = final()
        mapOf("frontByZOrder" to za["front"], "frontChanged" to (zb["front"] != za["front"]), "allFramesEqual" to allEqual(f),
              "clickedIn" to from.name, "via" to ProbeInput.VIA_QUEUE, "zOrderBefore" to zb, "zOrderAfter" to za, "reset" to reset, "keepFrontmost" to foreground,
              "pluginBefore" to pluginBefore, "pluginAfter" to pluginAfter,
              "platformBaseline" to mapOf("call" to "ProjectUtil.focusProjectWindow(beta)", "reset" to reset2, "frontBefore" to zReset["front"], "frontAfter" to zPlatform["front"]),
              "switchClickZOrder" to mapOf("before" to zb0["ordered"], "after" to za0["ordered"], "note" to "switch.click's own click, made while beta was still in front"),
              "final" to f)
      }
    }

    var nextZ: Pair<Map<String, Any?>, Map<String, Any?>>? = null
    rec("switch.nextProjectWindow", mapOf("activeProjectChanged" to true, "allFramesEqual" to true)) {
      val foreground = keepFrontmost()
      val before = ctx.edt { activeProject() }
      val pluginBefore = ctx.edt { pluginStack() }
      val zBefore = zOrder()
      val atInput = frontmost()
      val performed = ctx.edt {
        val action = ActionManager.getInstance().getAction("NextProjectWindow")
        val root = frame(beta)?.rootPane
        if (action == null || root == null) false else { ActionUtil.invokeAction(action, root, ActionPlaces.MAIN_MENU, null, null); true }
      }
      val t0 = System.currentTimeMillis()
      // settled: another project's frame active (not just none active for a moment), or 3 s
      ctx.waitUntil(3000) { activeProject().let { it != null && it != before } }
      val settledMs = System.currentTimeMillis() - t0
      val after = ctx.edt { activeProject() }
      val afterInput = frontmost()
      val timeline = activationTimeline(t0, 3000) { activeProject().let { it != null && it != before } }
      val s = sample()
      val zAfter = zOrder()
      nextZ = zBefore to zAfter
      val activeAfterSamples = ctx.edt { activeProject() }
      val env = envFor(after != before && after != null, ctx.edt { open().firstOrNull { it.name == (after ?: zAfter["front"]) && it.name != before } ?: open().firstOrNull { it.name == zAfter["front"] } ?: alpha },
                       ctx.edt { open().firstOrNull { it.name == zBefore["front"] } }, zBefore["appActive"] as? Boolean)
      ctx.edt {
        val f = final()
        mapOf(
          "activeProjectChanged" to (after != before && after != null), "allFramesEqual" to allEqual(f), "activeBefore" to before, "activeAfter" to after,
          "foreground" to mapOf("keepFrontmost" to foreground, "atInput" to atInput, "afterInput" to afterInput, "timeline" to timeline),
          "settledMs" to settledMs, "activeAfterSamples" to activeAfterSamples, "performed" to performed, "pluginBefore" to pluginBefore, "plugin" to pluginStack(), "zOrderBefore" to zBefore, "zOrderAfter" to zAfter,
          "final" to f, "samples" to s, "env" to env.first, "baseline" to env.second,
        )
      }
    }

    rec("switch.nextProjectWindow.front", mapOf("frontByZOrderChanged" to true, "allFramesEqual" to true)) {
      val (zb, za) = nextZ ?: error("switch.nextProjectWindow did not run")
      ctx.edt {
        val f = final()
        mapOf("frontByZOrderChanged" to (za["front"] != null && za["front"] != zb["front"]), "allFramesEqual" to allEqual(f),
              "zOrderBefore" to zb, "zOrderAfter" to za, "of" to "the switch.nextProjectWindow action (NSApp orderedWindows)", "final" to f)
      }
    }

    // ------------------------------------------------------------ the Advanced Setting
    rec("setting.off", mapOf("anyStripShowing" to false, "othersMoved" to false)) {
      val before = ctx.edt { final() }
      ctx.edt { AdvancedSettings.setBoolean(SETTING, false) }
      val s = sample()
      ctx.edt {
        val f = final()
        mapOf(
          "anyStripShowing" to open().any { strip(it)?.isShowing == true }, "othersMoved" to (f != before), "before" to before,
          "final" to f, "samples" to s, "png" to paint(alpha, "setting-off-alpha.png"),
        )
      }
    }

    rec("setting.on", mapOf("allStripsShowing" to true, "allFramesEqual" to true)) {
      // while off, the frames are independent: move alpha aside, as a user may; on must stack it again
      val moved = ctx.edt { frame(alpha)!!.let { f -> f.setLocation(f.x + 30, f.y + 20); geomOf(f) } }
      delay(500)
      ctx.edt { AdvancedSettings.setBoolean(SETTING, true) }
      val s = sample()
      ctx.edt {
        val f = final()
        mapOf(
          "allStripsShowing" to open().all { strip(it)?.isShowing == true }, "allFramesEqual" to allEqual(f), "alphaMovedWhileOff" to moved,
          "active" to activeProject(), "final" to f, "samples" to s, "plugin" to pluginStack(),
        )
      }
    }

    rec("relayout.toolbar", mapOf("boundsChanged" to false)) {
      val before = ctx.edt { final() }
      val hierarchyBefore = stripHierarchy.size
      val ui = UISettings.getInstance()
      val was = ctx.edt { listOf(ui.showMainToolbar, ui.showNavigationBar) }
      ctx.edt { ui.showMainToolbar = !was[0]; ui.showNavigationBar = !was[1]; ui.fireUISettingsChanged() }
      val s1 = sample(1500)
      ctx.edt { ui.showMainToolbar = was[0]; ui.showNavigationBar = was[1]; ui.fireUISettingsChanged() }
      val s2 = sample(1500)
      ctx.edt {
        val all = (s1.values + s2.values).flatten().map { it.drop(1) }
        val f = final()
        mapOf(
          "boundsChanged" to (f != before || all.any { g -> g !in before.values }), "before" to before, "final" to f,
          "samples" to s1.mapValues { (k, v) -> v + (s2[k].orEmpty().map { listOf(it[0] + 1500) + it.drop(1) }) },
          "toggled" to mapOf("showMainToolbar" to !was[0], "showNavigationBar" to !was[1]),
          "stripReattach" to stripHierarchy.drop(hierarchyBefore),
        )
      }
    }

    fullScreenSteps(alpha, beta)
    gamma?.let { closeFrontSteps(alpha, it) }

    // ------------------------------------------------------------ closing from the strip
    rec("close.middleClick", mapOf("closedProject" to "beta", "alphaOpen" to true)) {
      val pre = userFront(alpha)
      val before = ctx.edt { open().map { it.name } }
      val active = ctx.edt { activeProject() }
      val tab = ctx.edt { tabIn(alpha, "beta") } ?: error("no beta tab in alpha's strip")
      click(tab, MouseEvent.BUTTON2)
      ctx.waitUntil(10_000) { open().size < before.size }
      delay(500)
      ctx.edt {
        val now = open().map { it.name }
        val closed = before - now.toSet()
        mapOf(
          "closedProject" to closed.singleOrNull(), "closed" to closed, "alphaOpen" to ("alpha" in now), "openProjects" to now,
          "activeBefore" to active, "precondition" to pre, "via" to ProbeInput.VIA_QUEUE, "button" to 2,
        )
      }
    }

    rec("close.toSingle", mapOf("openProjects" to listOf("alpha"), "stripShowing" to false, "alphaMoved" to false)) {
      val pre = userFront(alpha)
      val alphaBefore = ctx.edt { geom(alpha) }
      val closedVia = ArrayList<String>()
      for (name in listOf("gamma", "delta")) {
        val before = ctx.edt { open().size }
        if (ctx.edt { byName(name) } == null) continue
        val button = ctx.edt { tabIn(alpha, name)?.let(::closeButton) }
        if (button != null) {
          click(button, MouseEvent.BUTTON1)
          closedVia += "$name:closeButton"
        }
        else {
          // the strip may already be hidden; fall back to the platform, recorded
          ctx.edt { byName(name) }?.let { ProjectManagerEx.getInstanceEx().forceCloseProjectAsync(it) }
          closedVia += "$name:forceClose(no close button)"
        }
        ctx.waitUntil(10_000) { open().size < before }
      }
      val s = sample(1500)
      ctx.edt {
        mapOf(
          "openProjects" to open().map { it.name }, "stripShowing" to (strip(alpha)?.isShowing == true), "alphaMoved" to (geom(alpha) != alphaBefore),
          "alphaBefore" to alphaBefore, "precondition" to pre, "closedVia" to closedVia, "via" to ProbeInput.VIA_QUEUE, "samples" to s,
          "png" to paint(alpha, "single-alpha.png"),
        )
      }
    }

    rec("activation.summary", mapOf("recorded" to true)) {
      mapOf("recorded" to true, "osActivations" to osActivations.toList(), "stripHierarchy" to stripHierarchy.toList())
    }
  }

  /** Front frame [beta] enters full screen; then a click in its strip switches to alpha, which should take full screen. */
  private suspend fun fullScreenSteps(alpha: Project, beta: Project) {
    val fsBaseline = mapOf("ok" to (baseFullScreen == true), "enteredFullScreen" to baseFullScreen)
    rec("stack.fullScreen", mapOf("frontMatchesSingleFrameBaseline" to true, "othersWindowedAtStack" to true)) {
      ctx.edt { postActivation(frame(beta)!!) }
      delay(300)
      val windowedBefore = ctx.edt { final() }
      val entered = toggleFullScreen(beta, true)
      val s = sample()
      ctx.edt {
        val others = open().filter { it !== beta }
        val og = others.mapNotNull { geom(it) }
        mapOf(
          "frontMatchesSingleFrameBaseline" to (entered == (baseFullScreen == true)),
          "othersWindowedAtStack" to (others.none(::fullScreen) && og.isNotEmpty() && og.all { it == og.first() } && og.first() == windowedBefore["alpha"]),
          "frontFullScreen" to entered, "othersFullScreen" to others.associate { it.name to fullScreen(it) }, "windowedBefore" to windowedBefore,
          "baseline" to fsBaseline, "final" to final(), "samples" to s, "plugin" to pluginStack(),
        )
      }
    }
    rec("switch.click.fullScreen", mapOf("targetMatchesSingleFrameBaseline" to true, "othersPushedIntoFullScreen" to false)) {
      val tab = ctx.edt { tabIn(beta, "alpha") } ?: error("no alpha tab in beta's strip")
      click(tab, MouseEvent.BUTTON1)
      ctx.waitUntil(10_000) { fullScreen(alpha) }
      delay(1000)
      val s = sample()
      ctx.edt {
        val others = open().filter { it !== alpha && it !== beta }
        mapOf(
          "targetMatchesSingleFrameBaseline" to (fullScreen(alpha) == (baseFullScreen == true)),
          "othersPushedIntoFullScreen" to others.any(::fullScreen), "targetFullScreen" to fullScreen(alpha), "previousFullScreen" to fullScreen(beta),
          "fullScreen" to open().associate { it.name to fullScreen(it) }, "via" to ProbeInput.VIA_QUEUE, "baseline" to fsBaseline,
          "final" to final(), "samples" to s, "plugin" to pluginStack(),
        )
      }
    }
    // the front frame (alpha, switched to in full screen) leaves full screen as with the green button: it should come
    // back to the shared windowed bounds, and no windowed frame behind should move while it is in transition
    rec("stack.fullScreen.exit", mapOf("frontBackAtSharedWindowed" to true, "othersMovedDuringTransition" to false)) {
      val pluginBefore = ctx.edt { pluginStack() }
      val windowed = ctx.edt { open().filter { !fullScreen(it) }.mapNotNull { p -> geom(p)?.let { p.name to it } }.toMap() }
      val before = ctx.edt { final() }
      val (left, s) = kotlinx.coroutines.coroutineScope {
        val sj = async { sample(4000) }
        val ok = toggleFullScreen(alpha, false)
        ok to sj.await()
      }
      delay(500)
      ctx.edt {
        @Suppress("UNCHECKED_CAST") val shared = pluginBefore["shared"] as? List<Int>
        val a = geom(alpha)
        val moved = windowed.filter { (n, g) -> s[n].orEmpty().any { it.drop(1) != g } }.keys
        mapOf(
          "frontBackAtSharedWindowed" to (left && a != null && !isMax(a) && a.take(4) == shared && pluginBefore["sharedMaximized"] == false),
          "othersMovedDuringTransition" to moved.isNotEmpty(), "movedFrames" to moved, "left" to left, "alpha" to a,
          "pluginBefore" to pluginBefore, "plugin" to pluginStack(), "windowedBefore" to windowed, "before" to before,
          "fullScreen" to open().associate { it.name to fullScreen(it) }, "via" to "ProjectFrameHelper.toggleFullScreen(false)", "final" to final(), "samples" to s,
        )
      }
    }
    // windowed again for the steps after this
    for (p in ctx.edt { open() }) if (ctx.edt { fullScreen(p) }) toggleFullScreen(p, false)
    sample(1000)
  }

  /**
   * The front frame is closed and the frames left are moved. A strip click on gamma makes it the plugin's front and puts it
   * on top; gamma is closed from its own strip; macOS then makes the topmost frame left key, posted the same way
   * ([postActivation]); that frame is moved and the others should follow.
   */
  private suspend fun closeFrontSteps(alpha: Project, gamma: Project) {
    rec("stack.closeFront", mapOf("allFramesEqual" to true, "frontAtRequestedBounds" to true)) {
      val pre = userFront(alpha)
      val tab = ctx.edt { tabIn(alpha, "gamma") } ?: error("no gamma tab in alpha's strip")
      click(tab, MouseEvent.BUTTON1)
      delay(1200)
      val zSwitched = zOrder()
      // macOS makes the frame the switch brought to front key: posted for the frame in front
      val switchActivation = ctx.edt { byName(zSwitched["front"] as? String ?: "gamma") }?.let { userFront(it) }
      val pluginBefore = ctx.edt { pluginStack() }
      val zBefore = zOrder()
      val close = ctx.edt { tabIn(gamma, "gamma")?.let(::closeButton) } ?: error("no close button on gamma's own tab")
      val n = ctx.edt { open().size }
      click(close, MouseEvent.BUTTON1)
      val closedOk = ctx.waitUntil(10_000) { open().size < n }
      if (!closedOk) error("gamma did not close from its own strip's close button")
      delay(500)
      val pluginAfterClose = ctx.edt { pluginStack() }
      val zAfterClose = zOrder()
      val nextName = (zAfterClose["front"] as? String) ?: "alpha"
      val next = ctx.edt { byName(nextName) } ?: alpha
      val from = activations.size
      val activation = ctx.edt { postActivation(frame(next)!!) }
      delay(500)
      val pluginAfterActivation = ctx.edt { pluginStack() }
      val requested = ctx.edt {
        val f = frame(next)!!
        if (isMax(geomOf(f))) f.extendedState = 0
        val r = Rectangle(f.x + 50, f.y + 30, (f.width - 100).coerceAtLeast(700), (f.height - 60).coerceAtLeast(500))
        f.bounds = r
        r
      }
      val s = sample()
      ctx.edt {
        val f = final()
        mapOf(
          "allFramesEqual" to allEqual(f), "frontAtRequestedBounds" to (f[next.name] == listOf(requested.x, requested.y, requested.width, requested.height, 0)),
          "closed" to (if (gamma.isDisposed) "gamma" else null), "moved" to next.name, "precondition" to pre, "zOrderAfterSwitch" to zSwitched,
          "switchActivation" to switchActivation, "requested" to listOf(requested.x, requested.y, requested.width, requested.height),
          "pluginBefore" to pluginBefore, "pluginAfterClose" to pluginAfterClose, "pluginAfterActivation" to pluginAfterActivation, "plugin" to pluginStack(),
          "zOrderBefore" to zBefore, "zOrderAfterClose" to zAfterClose, "activation" to activation, "activationsDispatched" to activationsSince(from),
          "via" to "strip click, strip close button (eventQueue), frame.setBounds", "final" to f, "samples" to s,
        )
      }
    }
  }

  /**
   * Polls every 100 ms for up to [ms] (and at least 1.5 s) after an input made at [t0]: ms, NSApp isActive, the frontmost
   * pid, whether [done] holds and the active project, one entry per change. Ends early once [done] holds and 1.5 s have passed.
   */
  suspend fun activationTimeline(t0: Long, ms: Long, done: () -> Boolean): List<List<Any?>> {
    val out = ArrayList<List<Any?>>()
    while (true) {
      val t = System.currentTimeMillis() - t0
      val fm = frontmost()
      val d = ctx.edt(done)
      @Suppress("UNCHECKED_CAST") val pid = (fm["lsappinfo"] as? Map<String, Any?>)?.get("frontPid")
      val e = listOf(fm["nsAppActive"], pid, d, ctx.edt { activeProject() })
      if (out.isEmpty() || out.last().drop(1) != e) out += listOf<Any?>(t) + e
      if ((d && t >= 1500) || t >= ms) return out
      delay(100)
    }
  }

  /** Which app macOS has frontmost now: NSApp isActive and `lsappinfo front`. */
  suspend fun frontmost(): Map<String, Any?> =
    if (!ProbeMac.isMac) mapOf("error" to "not macOS")
    else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      mapOf("nsAppActive" to ProbeMac.onMain { ProbeMac.appActive() }, "lsappinfo" to ProbeMac.lsappinfoFront())
    }

  /**
   * Makes this sandbox the frontmost app before a switch step, so the step runs with the app active as a user's click
   * makes it: in-process activation, then osascript System Events by pid if that was not enough. Quits nothing.
   */
  suspend fun keepFrontmost(): Map<String, Any?> {
    val before = frontmost()
    if (!ProbeMac.isMac || before["nsAppActive"] == true) return mapOf("before" to before, "via" to null, "after" to before)
    val tries = ArrayList<String>()
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ProbeMac.onMain { ProbeMac.activateInProcess() } }
    tries += "inProcess"
    suspend fun waitActive() { val end = System.currentTimeMillis() + 1500; while (frontmost()["nsAppActive"] != true && System.currentTimeMillis() < end) delay(100) }
    waitActive()
    if (frontmost()["nsAppActive"] != true) {
      val rc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
          val script = "tell application \"System Events\" to set frontmost of (first process whose unix id is ${ProcessHandle.current().pid()}) to true"
          val pr = ProcessBuilder("osascript", "-e", script).redirectErrorStream(true).start()
          pr.inputStream.readAllBytes()
          if (pr.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) pr.exitValue() else null
        }
        catch (_: Exception) { null }
      }
      tries += "osascript rc=$rc"
      waitActive()
    }
    return mapOf("before" to before, "via" to tries.joinToString(), "after" to frontmost())
  }

  /**
   * env for a focus step. None if it worked. Otherwise the baseline is the plugin's own call, ProjectUtil.focusProjectWindow
   * ([p], false) (FrameWindow.activate), made without the plugin from [from] in front again, toFront only, so the app's
   * active state is not changed by the reset. The step is env only if the app was inactive at the step and at the baseline
   * and that baseline also leaves [p] inactive (baseline.ok false): then ok:null. A failure with the app active is the
   * plugin's (env null, ok:false). The stealing call (focusProjectWindow(p, true)) is recorded after it, as stealingBaseline.
   */
  private suspend fun envFor(worked: Boolean, p: Project, from: Project?, appActiveAtStep: Boolean?): Pair<String?, Map<String, Any?>> {
    if (worked) return null to mapOf("ok" to true, "via" to "not needed", "appActiveAtStep" to appActiveAtStep)
    val reset = ArrayList<String>()
    val fromFrame = from?.let { ctx.edt { frame(it) } }
    if (fromFrame != null) {
      for (i in 1..4) {
        ctx.edt { fromFrame.toFront() }
        delay(700)
        val front = zOrder()["front"]
        reset += "toFront#$i->$front"
        if (front == from.name) break
      }
    }
    // the same app state as at the step: made frontmost again if it was then (nothing else is done if it was not)
    val reactivated = if (appActiveAtStep == true) keepFrontmost() else null
    val zReset = zOrder()
    val appActiveAtBaseline = zReset["appActive"] as? Boolean
    val t0 = System.currentTimeMillis()
    val err = ctx.edt { try { ProjectUtil.focusProjectWindow(p, false); null } catch (e: Exception) { e.toString() } }
    val timeline = activationTimeline(t0, 3000) { frame(p)?.isActive == true }
    val ok = ctx.edt { frame(p)?.isActive == true }
    val zAfter = zOrder()
    val likeForLike = mapOf(
      "ok" to ok, "via" to "ProjectUtil.focusProjectWindow(${p.name}, false), the plugin's call, plugin not involved", "target" to p.name,
      "error" to err, "from" to from?.name, "reset" to reset.joinToString(), "frontBefore" to zReset["front"], "frontAfter" to zAfter["front"],
      "appActiveAtStep" to appActiveAtStep, "appActiveAtBaseline" to appActiveAtBaseline, "appActiveAfter" to zAfter["appActive"],
      "activeAfterBaseline" to ctx.edt { activeProject() }, "frontmost" to frontmost(), "reactivated" to reactivated, "timeline" to timeline,
    )
    val stealErr = ctx.edt { try { ProjectUtil.focusProjectWindow(p, true); null } catch (e: Exception) { e.toString() } }
    ctx.waitUntil(1500) { frame(p)?.isActive == true }
    val stealing = mapOf("ok" to ctx.edt { frame(p)?.isActive == true }, "via" to "ProjectUtil.focusProjectWindow(${p.name}, true)",
                         "error" to stealErr, "zOrderAfter" to zOrder())
    val env = if (!ok && appActiveAtStep == false && appActiveAtBaseline == false) "app-inactive" else null
    return env to (likeForLike + ("stealingBaseline" to stealing))
  }

  private fun nonuniform(px: Map<String, Any?>): Boolean {
    @Suppress("UNCHECKED_CAST") val a = px["a"] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val r = a["rect"] as List<Int>
    val file = ctx.out.resolve(a["png"] as String).toFile()
    if (!file.isFile) return false
    val img = javax.imageio.ImageIO.read(file) ?: return false
    val colours = HashSet<Int>()
    for (y in r[1] until (r[1] + r[3]).coerceAtMost(img.height)) for (x in r[0] until (r[0] + r[2]).coerceAtMost(img.width)) colours.add(img.getRGB(x, y))
    return colours.size >= 4
  }

  private suspend fun makeFixture(name: String) {
    val dir = ctx.fixtures.resolve(name)
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      Files.createDirectories(dir.resolve(".idea"))
      Files.writeString(dir.resolve(".idea/modules.xml"),
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project version=\"4\"><component name=\"ProjectModuleManager\"><modules>" +
        "<module fileurl=\"file://\$PROJECT_DIR\$/$name.iml\" filepath=\"\$PROJECT_DIR\$/$name.iml\" /></modules></component></project>\n")
      Files.writeString(dir.resolve("$name.iml"),
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<module type=\"EMPTY_MODULE\" version=\"4\"><component name=\"NewModuleRootManager\">" +
        "<content url=\"file://\$MODULE_DIR\$\" /><orderEntry type=\"sourceFolder\" forTests=\"false\" /></component></module>\n")
      for (f in 'a'..'c') Files.writeString(dir.resolve("$f.txt"), "$name $f\n")
    }
  }

  /**
   * Gives beta and gamma a saved frame state of their own, as a user's projects have: with the setting off, beta is opened,
   * put at other bounds and closed; gamma is opened, maximized and closed. They reopen from these (IdeProjectFrameAllocator).
   * delta is opened and closed as it is. Each project's layout as the platform shows it with the setting off is recorded
   * (layout.newFrame.* compare with it): as inherited from earlier runs, then with the Project tool window at a quarter of
   * the width ([normalizeProjectWidth]), which is what it is saved with.
   */
  private suspend fun prepareSavedFrames(alpha: Project) {
    rec("prep.savedFrames", mapOf("recorded" to true)) {
      ctx.edt { AdvancedSettings.setBoolean(SETTING, false) }
      delay(300)
      activateOnOpen = false
      val scr = ctx.edt { screen() }
      val rb = Rectangle(scr.x + 200, scr.y + 80, 1000, 700)
      val saved = LinkedHashMap<String, Any?>()
      val layouts = LinkedHashMap<String, Any?>()
      layouts["alpha"] = normalizeProjectWidth(alpha)
      makeFixture("delta")
      for (name in listOf("beta", "gamma", "delta")) {
        val p = openProject(name, newFrame = true) ?: continue
        ctx.edt {
          val f = frame(p)!!
          if (fullScreen(p)) return@edt
          if (name == "beta") { if (isMax(geomOf(f))) f.extendedState = 0; f.bounds = rb }
          else if (name == "gamma") f.extendedState = f.extendedState or Frame.MAXIMIZED_BOTH
        }
        ctx.waitUntil(3000) { if (name == "beta") frame(p)!!.bounds == rb else if (name == "gamma") isMax(geom(p)) else true }
        delay(500)
        layouts[name] = normalizeProjectWidth(p)
        saved[name] = ctx.edt { geom(p) }
        ProjectManagerEx.getInstanceEx().forceCloseProjectAsync(p, save = true)
        ctx.waitUntil(10_000) { p.isDisposed }
      }
      activateOnOpen = true
      layoutBaseline = layouts
      ctx.edt { AdvancedSettings.setBoolean(SETTING, true) }
      delay(500)
      mapOf("recorded" to true, "savedAtClose" to saved, "layouts" to layouts, "alpha" to ctx.edt { geom(alpha) }, "openProjects" to ctx.edt { open().map { it.name } }, "plugin" to ctx.edt { pluginStack() })
    }
  }

  /**
   * focus.diagnose: can this sandbox become the active app at all. Tried in order, each followed by NSApp isActive and
   * `lsappinfo front`: in-process activation (NSRunningApplication / NSApp, no permission needed), osascript System
   * Events (rc recorded), then a java.awt.Robot move and click on [target]'s own current strip tab (switches nothing),
   * made only after the window is ordered front (orderFrontRegardless) and the topmost window at that point is checked
   * to be this frame. AXIsProcessTrusted says whether Robot's events can be posted at all.
   */
  private suspend fun focusDiagnose(target: Project) {
    rec("focus.diagnose", mapOf("recorded" to true)) {
      val io = kotlinx.coroutines.Dispatchers.IO
      suspend fun macState(): Map<String, Any?> = kotlinx.coroutines.withContext(io) {
        mapOf("nsAppActive" to ProbeMac.onMain { ProbeMac.appActive() }, "lsappinfo" to ProbeMac.lsappinfoFront(), "screenLocked" to ProbeMac.screenLocked())
      } + mapOf("kfmActiveWindow" to ctx.edt { java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow?.let(::nameOf) },
                "realActivations" to osActivations.toList())
      val out = LinkedHashMap<String, Any?>()
      out["recorded"] = true
      out["axTrusted"] = kotlinx.coroutines.withContext(io) { ProbeMac.axTrusted() }
      out["before"] = macState()
      // 1 in-process activation
      out["inProcess"] = mapOf("calls" to kotlinx.coroutines.withContext(io) { ProbeMac.onMain { ProbeMac.activateInProcess() } }) +
                         (delay(1500).let { macState() })
      // 2 osascript System Events, rc recorded, then what macOS has in front
      val osa = kotlinx.coroutines.withContext(io) {
        try {
          val script = "tell application \"System Events\" to set frontmost of (first process whose unix id is ${ProcessHandle.current().pid()}) to true"
          val pr = ProcessBuilder("osascript", "-e", script).redirectErrorStream(true).start()
          val o = String(pr.inputStream.readAllBytes()).trim()
          val done = pr.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
          mapOf("rc" to (if (done) pr.exitValue() else null), "output" to o.take(300))
        }
        catch (e: Exception) { mapOf("rc" to null, "output" to e.toString()) }
      }
      delay(1500)
      out["osascript"] = osa + macState()
      // 3 Robot on an uncovered window
      val tab = ctx.edt { tabIn(target, target.name) }
      val fr = ctx.edt { frame(target) }
      if (tab == null || fr == null) out["robot"] = "no target tab"
      else {
        val pt = ctx.edt { ProbeInput.toScreen(tab, Point(tab.width / 2, tab.height / 2)) }
        val ordered = kotlinx.coroutines.withContext(io) { ProbeMac.onMain { ProbeMac.orderFrontRegardless(fr) } }
        delay(500)
        fun cover(): Map<String, Any?> = ProbeMac.onMain<Map<String, Any?>> {
          val top = ProbeMac.topWindowAt(pt)
          mapOf("self" to ProbeMac.windowNumber(fr), "topAtPoint" to top, "topIsThisApp" to (top in ProbeMac.orderedWindowNumbers()),
                "onActiveSpace" to ProbeMac.onActiveSpace(fr), "occlusionVisible" to ProbeMac.occlusionVisible(fr))
        }.orEmpty()
        var c1 = kotlinx.coroutines.withContext(io) { cover() }
        // not on the screen the user sees (another Space, or under a full-screen app): let it join every Space and order it front
        var oldBehavior: Long? = null
        if (c1["self"] != c1["topAtPoint"]) {
          oldBehavior = kotlinx.coroutines.withContext(io) { ProbeMac.onMain { ProbeMac.collectionBehavior(fr) } }
          kotlinx.coroutines.withContext(io) { ProbeMac.onMain { ProbeMac.setCollectionBehavior(fr, (oldBehavior ?: 0L) or 1L or 256L); ProbeMac.orderFrontRegardless(fr) } }
          delay(700)
          val c1b = kotlinx.coroutines.withContext(io) { cover() }
          c1 = c1 + ("afterJoinAllSpaces" to c1b)
          if (c1b["self"] == c1b["topAtPoint"]) c1 = c1 + ("topAtPoint" to c1b["topAtPoint"])
        }
        val seen = CopyOnWriteArrayList<String>()
        val spy = AWTEventListener { e ->
          if (e is MouseEvent && SwingUtilities.getWindowAncestor(e.component) === fr) seen.add("${e.id}@${e.component.javaClass.simpleName}")
        }
        ctx.edt { Toolkit.getDefaultToolkit().addAWTEventListener(spy, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK) }
        val robotOut = LinkedHashMap<String, Any?>()
        robotOut["screenPoint"] = listOf(pt.x, pt.y)
        robotOut["orderFrontRegardless"] = ordered
        robotOut["coverBeforeMove"] = c1
        try {
          val robot = java.awt.Robot()
          val saved = java.awt.MouseInfo.getPointerInfo()?.location
          kotlinx.coroutines.withContext(io) { robot.mouseMove(pt.x - 3, pt.y); robot.mouseMove(pt.x, pt.y) }
          delay(400)
          robotOut["pointerAfterMove"] = java.awt.MouseInfo.getPointerInfo()?.location?.let { listOf(it.x, it.y) }
          robotOut["eventsAfterMove"] = seen.toList()
          val c2 = kotlinx.coroutines.withContext(io) { cover() }
          robotOut["coverBeforeClick"] = c2
          val uncovered = c2["self"] != null && c2["self"] == c2["topAtPoint"]
          robotOut["uncovered"] = uncovered
          if (uncovered) {
            kotlinx.coroutines.withContext(io) { robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.delay(40); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
            delay(1500)
            robotOut["clicked"] = true
          }
          else robotOut["clicked"] = "no: another window is topmost at the point"
          robotOut["eventsSeen"] = seen.take(20)
          if (saved != null) kotlinx.coroutines.withContext(io) { robot.mouseMove(saved.x, saved.y) }
        }
        catch (e: Exception) { robotOut["error"] = e.toString() }
        finally {
          ctx.edt { Toolkit.getDefaultToolkit().removeAWTEventListener(spy) }
          oldBehavior?.let { b -> kotlinx.coroutines.withContext(io) { ProbeMac.onMain { ProbeMac.setCollectionBehavior(fr, b) } } }
        }
        out["robot"] = robotOut + macState()
      }
      val active = (out.values.filterIsInstance<Map<*, *>>().any { it["nsAppActive"] == true })
      out["osActivationAvailable"] = active || osActivations.isNotEmpty()
      out["zOrder"] = zOrder()
      out
    }
  }

  /** Single frame (alpha), setting off: what the platform itself does to one frame for move, zoom and full screen. */
  private suspend fun baselines(alpha: Project) {
    rec("baseline.singleFrame", mapOf("recorded" to true)) {
      ctx.edt { AdvancedSettings.setBoolean(SETTING, false) }
      delay(300)
      ctx.edt { frame(alpha)!!.let { if (isMax(geomOf(it))) it.extendedState = it.extendedState and Frame.MAXIMIZED_BOTH.inv() } }
      delay(500)
      val orig = ctx.edt { geom(alpha)!! }
      // move: the same kind of setBounds stack.move does
      val requested = ctx.edt {
        val f = frame(alpha)!!
        val r = Rectangle(f.x + 40, f.y + 30, (f.width - 80).coerceAtLeast(700), (f.height - 60).coerceAtLeast(500))
        f.bounds = r
        listOf(r.x, r.y, r.width, r.height, f.extendedState)
      }
      val moveSamples = sample()
      baseMoveChanges = changesAfter(moveSamples) { m -> m.values.all { it == requested } }
      // zoom
      basePreMax = ctx.edt { geom(alpha) }
      ctx.edt { frame(alpha)!!.let { it.extendedState = it.extendedState or Frame.MAXIMIZED_BOTH } }
      ctx.waitUntil(3000) { isMax(geom(alpha)) }
      val maxSamples = sample(1500)
      baseMax = ctx.edt { geom(alpha) }
      baseMaxOk = isMax(baseMax) || baseMax?.take(4) != basePreMax?.take(4)
      ctx.edt { frame(alpha)!!.let { it.extendedState = it.extendedState and Frame.MAXIMIZED_BOTH.inv() } }
      ctx.waitUntil(3000) { !isMax(geom(alpha)) }
      val unmaxSamples = sample(1500)
      baseUnmax = ctx.edt { geom(alpha) }
      // full screen
      val fsEntered = toggleFullScreen(alpha, true)
      val fsGeom = ctx.edt { geom(alpha) }
      val fsLeft = toggleFullScreen(alpha, false)
      baseFullScreen = fsEntered
      // back where it was, setting on again
      ctx.edt { frame(alpha)!!.bounds = Rectangle(orig[0], orig[1], orig[2], orig[3]) }
      delay(500)
      ctx.edt { AdvancedSettings.setBoolean(SETTING, true) }
      delay(500)
      baseline = mapOf(
        "orig" to orig, "moveRequested" to requested, "moveChangesAfterSettle" to baseMoveChanges, "moveSamples" to moveSamples,
        "zoomOk" to baseMaxOk, "preMax" to basePreMax, "max" to baseMax, "unmax" to baseUnmax, "maxSamples" to maxSamples, "unmaxSamples" to unmaxSamples,
        "fullScreenEntered" to fsEntered, "fullScreenGeom" to fsGeom, "fullScreenLeft" to fsLeft,
        "restored" to ctx.edt { geom(alpha) }, "screen" to ctx.edt { screen().let { listOf(it.x, it.y, it.width, it.height) } },
      )
      mapOf("recorded" to true) + baseline
    }
  }
}
