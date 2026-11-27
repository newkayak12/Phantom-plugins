// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.stringhelpers.cron

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.PsiLiteralExpression
import dev.phantom.stringhelpers.StringHelpersBundle
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.Icon

/** A gutter icon on a valid cron expression whose tooltip lists its next runs, computed when the tooltip opens. */
class CronLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = StringHelpersBundle.message("cron.gutter.name")
  override fun getIcon(): Icon = AllIcons.Vcs.History

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element !is PsiJavaToken) return null
    val literal = element.parent as? PsiLiteralExpression ?: return null
    val target = CronTargets.of(literal) ?: return null
    if (!CronTargets.enabled(element.project)) return null
    val schedule = Cron.parse(target.expression, target.dialect).schedule ?: return null
    val zone = zone(target.zone) ?: return null
    return LineMarkerInfo(
      element,
      element.textRange,
      AllIcons.Vcs.History,
      { tooltip(schedule, zone, LocalDateTime.now(zone)) },
      null,
      GutterIconRenderer.Alignment.LEFT,
      { StringHelpersBundle.message("cron.gutter.name") },
    )
  }

  private fun zone(id: String?): ZoneId? = try {
    if (id == null) ZoneId.systemDefault() else ZoneId.of(id)
  } catch (_: DateTimeException) {
    null
  }

  companion object {
    private const val RUNS = 5
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEE", Locale.ENGLISH)

    /** The tooltip for [now] in [zone]; HTML so each run gets its own line. */
    fun tooltip(schedule: CronSchedule, zone: ZoneId, now: LocalDateTime): String {
      val runs = schedule.next(now, RUNS)
      if (runs.isEmpty()) return StringHelpersBundle.message("cron.gutter.none", CronSchedule.HORIZON_YEARS, zone.id)
      return "<html>" + StringHelpersBundle.message("cron.gutter.tooltip", zone.id) +
        runs.joinToString("") { "<br>" + FORMAT.format(it) } + "</html>"
    }
  }
}
