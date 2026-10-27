// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
import com.intellij.ui.mac.foundation.MacUtil
import com.sun.jna.NativeLibrary
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Window
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * What macOS itself says about the sandbox, read in process without any permission: whether the app is active, the
 * front-to-back order of its windows (NSApp orderedWindows), which window is topmost at a screen point (across all
 * apps), and the Accessibility trust that java.awt.Robot needs. Never call [onMain] from the EDT.
 */
object ProbeMac {
  val isMac: Boolean = System.getProperty("os.name").startsWith("Mac")

  /** Runs [block] on the AppKit main thread and waits up to 3 s for it. */
  fun <T> onMain(block: () -> T): T? {
    val f = CompletableFuture<T>()
    Foundation.executeOnMainThread(true, false) {
      try { f.complete(block()) } catch (e: Throwable) { f.completeExceptionally(e) }
    }
    return try { f.get(3, TimeUnit.SECONDS) } catch (_: Exception) { null }
  }

  private fun app(): ID = Foundation.invoke("NSApplication", "sharedApplication")

  fun appActive(): Boolean? = try { Foundation.invoke(app(), "isActive").booleanValue() } catch (_: Throwable) { null }

  fun nsWindow(w: Window): ID? = try { MacUtil.getWindowFromJavaWindow(w).takeIf { it.toLong() != 0L } } catch (_: Throwable) { null }

  fun windowNumber(w: Window): Long? = nsWindow(w)?.let { Foundation.invoke(it, "windowNumber").toLong() }

  /** NSWindowOcclusionStateVisible: some part of the window is visible on screen (not covered, not off screen). */
  fun occlusionVisible(w: Window): Boolean? = nsWindow(w)?.let { Foundation.invoke(it, "occlusionState").toLong() and 2L != 0L }

  /** This app's NSWindows front to back (NSApp orderedWindows) as window numbers. */
  fun orderedWindowNumbers(): List<Long> {
    val arr = Foundation.invoke(app(), "orderedWindows")
    val n = Foundation.invoke(arr, "count").toInt()
    return (0 until n).map { Foundation.invoke(Foundation.invoke(arr, "objectAtIndex:", it.toLong()), "windowNumber").toLong() }
  }

  /** This app's on-screen windows front to back as the window server has them (NSWindow windowNumbersWithOptions: 0). */
  fun serverWindowNumbers(): List<Long> {
    val arr = Foundation.invoke("NSWindow", "windowNumbersWithOptions:", 0L)
    val n = Foundation.invoke(arr, "count").toInt()
    return (0 until n).map { Foundation.invoke(Foundation.invoke(arr, "objectAtIndex:", it.toLong()), "integerValue").toLong() }
  }

  /** Window number of the topmost window of any app at a Java screen point (Cocoa y is flipped against the primary screen). */
  fun topWindowAt(p: Point): Long {
    val h = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds.height
    return Foundation.invoke(Foundation.getObjcClass("NSWindow"), Foundation.createSelector("windowNumberAtPoint:belowWindowWithWindowNumber:"),
                             p.x.toDouble(), (h - p.y).toDouble(), 0L).toLong()
  }

  fun onActiveSpace(w: Window): Boolean? = nsWindow(w)?.let { Foundation.invoke(it, "isOnActiveSpace").booleanValue() }

  fun collectionBehavior(w: Window): Long? = nsWindow(w)?.let { Foundation.invoke(it, "collectionBehavior").toLong() }

  fun setCollectionBehavior(w: Window, value: Long): Boolean = nsWindow(w)?.let { Foundation.invoke(it, "setCollectionBehavior:", value); true } ?: false

  fun orderFrontRegardless(w: Window): Boolean = nsWindow(w)?.let { Foundation.invoke(it, "orderFrontRegardless"); true } ?: false

  fun axTrusted(): Boolean? = try {
    NativeLibrary.getInstance("/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices")
      .getFunction("AXIsProcessTrusted").invokeInt(emptyArray()) != 0
  }
  catch (_: Throwable) {
    null
  }

  /** In-process activation without Automation or Accessibility: NSRunningApplication, then NSApp. Main thread. */
  fun activateInProcess(): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    out["runningApplication.activateWithOptions(ignoringOtherApps)"] = try {
      Foundation.invoke(Foundation.invoke("NSRunningApplication", "currentApplication"), "activateWithOptions:", 2L).booleanValue()
    }
    catch (e: Throwable) { e.toString() }
    out["NSApp.activateIgnoringOtherApps"] = try { Foundation.invoke(app(), "activateIgnoringOtherApps:", true); "called" } catch (e: Throwable) { e.toString() }
    out["NSApp.activate"] = try { Foundation.invoke(app(), "activate"); "called" } catch (e: Throwable) { e.toString() }
    return out
  }

  /** `lsappinfo front`: which process macOS has frontmost, and whether it is this one. */
  fun lsappinfoFront(): Map<String, Any?> = try {
    val asn = run("lsappinfo", "front").trim()
    val info = run("lsappinfo", "info", "-only", "pid", asn).trim()
    val pid = Regex("(\\d+)").find(info.substringAfter("pid"))?.value?.toLongOrNull()
    mapOf("front" to asn, "frontPid" to pid, "selfPid" to ProcessHandle.current().pid(), "selfFront" to (pid == ProcessHandle.current().pid()))
  }
  catch (e: Throwable) {
    mapOf("error" to e.toString())
  }

  /** The console session's lock state (`ioreg -n Root -d1 -a`, CGSSessionScreenIsLocked): a locked screen shows no app's windows. */
  fun screenLocked(): Boolean? = try {
    val x = run("ioreg", "-n", "Root", "-d1", "-a")
    Regex("<key>CGSSessionScreenIsLocked</key>\\s*<(true|false)/>").find(x)?.groupValues?.get(1)?.toBoolean() ?: false
  }
  catch (_: Throwable) {
    null
  }

  private fun run(vararg cmd: String): String {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val s = String(p.inputStream.readAllBytes())
    p.waitFor(3, TimeUnit.SECONDS)
    return s
  }
}
