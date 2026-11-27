// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.stringhelpers.cron

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiLiteralExpression

/** Reports a cron expression in `@Scheduled`, `CronExpression`, `CronTrigger` or `CronScheduleBuilder` that would fail at runtime. */
class CronInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    if (!CronTargets.enabled(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return object : JavaElementVisitor() {
      override fun visitLiteralExpression(expression: PsiLiteralExpression) {
        val target = CronTargets.of(expression) ?: return
        val problem = Cron.parse(target.expression, target.dialect).problem ?: return
        if (problem.shape && !CronTargets.reportsShape(target)) return
        holder.registerProblem(expression, CronTargets.message(problem))
      }
    }
  }
}
