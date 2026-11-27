// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.stringhelpers.cron

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.search.GlobalSearchScope
import dev.phantom.stringhelpers.StringHelpersBundle

/** A string literal Spring or Quartz parses as a cron expression. [zone] is `@Scheduled(zone = ...)` when given as a literal. */
class CronTarget(val expression: String, val dialect: CronDialect, val scheduled: Boolean, val zone: String?)

object CronTargets {
  private const val SCHEDULED = "org.springframework.scheduling.annotation.Scheduled"
  private const val QUARTZ_EXPRESSION = "org.quartz.CronExpression"

  /** Calls whose first argument is a cron expression: class, method, dialect. */
  private val CALLS = listOf(
    Triple("org.springframework.scheduling.support.CronExpression", "parse", CronDialect.SPRING),
    Triple("org.quartz.CronScheduleBuilder", "cronSchedule", CronDialect.QUARTZ),
    Triple("org.springframework.scheduling.quartz.CronTriggerFactoryBean", "setCronExpression", CronDialect.QUARTZ),
  )
  private val CALL_NAMES = CALLS.map { it.second }.toSet()

  /** Constructors whose first argument is a cron expression. */
  private val CONSTRUCTORS = mapOf(
    "org.springframework.scheduling.support.CronTrigger" to CronDialect.SPRING,
    QUARTZ_EXPRESSION to CronDialect.QUARTZ,
  )

  /** Whether the project has Spring scheduling or Quartz; JavaLibraryUtil caches this until the roots change. */
  fun enabled(project: Project): Boolean {
    if (JavaLibraryUtil.hasLibraryClass(project, SCHEDULED) || JavaLibraryUtil.hasLibraryClass(project, QUARTZ_EXPRESSION)) return true
    // Light test fixtures declare the annotations as source stubs instead of a library.
    if (!ApplicationManager.getApplication().isUnitTestMode) return false
    val facade = JavaPsiFacade.getInstance(project)
    val scope = GlobalSearchScope.allScope(project)
    return facade.findClass(SCHEDULED, scope) != null || facade.findClass(QUARTZ_EXPRESSION, scope) != null
  }

  fun of(literal: PsiLiteralExpression): CronTarget? {
    val text = literal.value as? String ?: return null
    // Placeholders are resolved by Spring at runtime.
    if (text.contains("\${") || text.contains("#{")) return null
    return when (val parent = literal.parent) {
      is PsiNameValuePair -> scheduled(parent, text)
      is PsiExpressionList -> if (parent.expressions.firstOrNull() == literal) argument(parent, text) else null
      else -> null
    }
  }

  private fun scheduled(pair: PsiNameValuePair, text: String): CronTarget? {
    if (pair.name != "cron") return null
    val annotation = pair.parent?.parent as? PsiAnnotation ?: return null
    if (annotation.nameReferenceElement?.referenceName != "Scheduled" || !annotation.hasQualifiedName(SCHEDULED)) return null
    // "-" turns the task off (Scheduled.CRON_DISABLED).
    if (text.trim() == "-") return null
    val zone = (annotation.findDeclaredAttributeValue("zone") as? PsiLiteralExpression)?.value as? String
    return CronTarget(text, CronDialect.SPRING, true, zone?.takeIf { it.isNotBlank() })
  }

  private fun argument(list: PsiExpressionList, text: String): CronTarget? = when (val call = list.parent) {
    is PsiMethodCallExpression -> {
      if (call.methodExpression.referenceName !in CALL_NAMES) {
        null
      } else {
        val method = call.resolveMethod()
        val owner = method?.containingClass?.qualifiedName
        CALLS.firstOrNull { it.first == owner && it.second == method?.name }?.let { CronTarget(text, it.third, false, null) }
      }
    }
    is PsiNewExpression -> {
      val name = call.classReference?.referenceName
      if (name != "CronTrigger" && name != "CronExpression") {
        null
      } else {
        CONSTRUCTORS[call.classReference?.qualifiedName]?.let { CronTarget(text, it, false, null) }
      }
    }
    else -> null
  }

  /**
   * The Spring plugin already reports a wrong field count or an unknown macro on `@Scheduled`
   * (`SpringScheduledInspection`); with it enabled those are left to it so the error is not shown twice.
   */
  fun reportsShape(target: CronTarget): Boolean =
    !target.scheduled || PluginManagerCore.getPlugin(PluginId.getId("dev.phantom.spring"))?.let { PluginManagerCore.isDisabled(it.pluginId) } != false

  fun message(problem: CronProblem): String {
    val field = problem.field?.let { StringHelpersBundle.message(it) }
    return StringHelpersBundle.message(problem.key, *(listOfNotNull(field) + problem.args).toTypedArray())
  }
}
