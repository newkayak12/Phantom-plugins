// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.options.OptPane
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import dev.phantom.db.core.exec.DbConsoles
import dev.phantom.db.core.exec.DbRunner
import java.time.DateTimeException
import java.time.LocalDate

/**
 * `DATE '2024-02-30'`, `TIMESTAMP '2024-01-01 25:00:00'`: a typed literal whose fields are out of range, which the
 * server rejects. Only the plain `YYYY-MM-DD[ hh:mm[:ss[.fraction]]]` form is checked; zero dates and other forms
 * MySQL accepts in relaxed syntax are left alone.
 */
class SqlDateTimeLiteralInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    if (holder.file !is SqlFile) return PsiElementVisitor.EMPTY_VISITOR
    return object : PsiElementVisitor() {
      override fun visitElement(element: PsiElement) {
        if (element.node.elementType != SqlTokens.STRING || element.text.length < 2 || element.text[0] != '\'') return
        val typeLeaf = SqlRoutine.significantBackward(PsiTreeUtil.prevLeaf(element)) ?: return
        val type = SqlRoutine.word(typeLeaf) ?: return
        if (type !in TYPES) return
        // `t.date 'x'` is a column, not a type
        if (SqlRoutine.significantBackward(PsiTreeUtil.prevLeaf(typeLeaf))?.node?.elementType == SqlTokens.DOT) return
        val problem = problem(type, element.text.substring(1, element.text.length - 1)) ?: return
        holder.registerProblem(element, PhantomDbBundle.message("sql.inspection.datetime.literal", type, problem))
      }
    }
  }

  companion object {
    private val TYPES = setOf("DATE", "TIME", "TIMESTAMP", "DATETIME")
    private val DATE = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")
    private val TIME = Regex("(\\d{1,3}):(\\d{1,2})(?::(\\d{1,2})(?:\\.\\d{1,6})?)?")

    /** What is wrong with [value] as a literal of [type], or null when it is fine or not in the checked form. */
    fun problem(type: String, value: String): String? {
      val text = value.trim()
      return when (type) {
        "DATE" -> DATE.matchEntire(text)?.let { date(it) }
        "TIME" -> TIME.matchEntire(text)?.let { time(it, hours = 838) }
        else -> {
          val parts = text.split(' ', 'T').filter { it.isNotEmpty() }
          if (parts.size != 2) return DATE.matchEntire(text)?.let { date(it) }
          val d = DATE.matchEntire(parts[0]) ?: return null
          val t = TIME.matchEntire(parts[1]) ?: return null
          date(d) ?: time(t, hours = 23)
        }
      }
    }

    private fun date(m: MatchResult): String? {
      val (y, mo, d) = m.destructured
      if (y.toInt() == 0 || mo.toInt() == 0 || d.toInt() == 0) return null
      if (mo.toInt() > 12) return PhantomDbBundle.message("sql.inspection.datetime.month", mo)
      return try {
        LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
        null
      }
      catch (_: DateTimeException) {
        PhantomDbBundle.message("sql.inspection.datetime.day", d)
      }
    }

    private fun time(m: MatchResult, hours: Int): String? {
      val h = m.groupValues[1].toInt()
      val min = m.groupValues[2].toInt()
      val s = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0
      return when {
        h > hours -> PhantomDbBundle.message("sql.inspection.datetime.hour", h)
        min > 59 -> PhantomDbBundle.message("sql.inspection.datetime.minute", min)
        s > 59 -> PhantomDbBundle.message("sql.inspection.datetime.second", s)
        else -> null
      }
    }
  }
}

/**
 * `LOAD DATA INFILE 'C:\data\x.csv'`: in a MySQL string a backslash escapes the next character, so the server reads
 * another path (`C:datax.csv`). Forward slashes, or doubled backslashes, keep the path.
 */
class SqlLoadDataPathInspection : SqlInspection() {
  override fun checkOther(statement: SqlStatement, holder: ProblemsHolder) {
    var leaf = SqlRoutine.significant(PsiTreeUtil.firstChild(statement))
    // LOAD is not a keyword of the lexer: the statement starts with a plain word
    if (leaf == null || SqlRoutine.word(leaf) != "LOAD" || !SqlRoutine.dialectFits(holder)) return
    while (leaf != null && PsiTreeUtil.isAncestor(statement, leaf, false)) {
      if (SqlRoutine.word(leaf) == "INFILE") {
        val path = SqlRoutine.significant(PsiTreeUtil.nextLeaf(leaf)) ?: return
        if (path.node.elementType != SqlTokens.STRING || !path.text.startsWith("'")) return
        if (!hasSingleBackslash(path.text)) return
        holder.registerProblem(path, PhantomDbBundle.message("sql.inspection.load.data.path"),
                               SqlTextFix(PhantomDbBundle.message("sql.fix.forward.slashes"), path.text.replace("\\\\", "/").replace("\\", "/")))
        return
      }
      leaf = SqlRoutine.significant(PsiTreeUtil.nextLeaf(leaf))
    }
  }

  private fun hasSingleBackslash(text: String): Boolean {
    var i = 0
    while (i < text.length) {
      if (text[i] == '\\') {
        if (text.getOrNull(i + 1) != '\\') return true
        i += 2
      }
      else i++
    }
    return false
  }
}

/** A query block that joins more tables than the configured limit: hard to read, and hard for the optimizer to plan. */
class SqlJoinCountInspection : SqlInspection() {
  @JvmField var maxJoins: Int = 10

  override fun getOptionsPane(): OptPane = OptPane.pane(OptPane.number("maxJoins", PhantomDbBundle.message("sql.inspection.join.count.option"), 1, 100))

  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    val joins = items.filter { it.depth == 0 && it.isKeyword("JOIN") }
    if (joins.size <= maxJoins) return
    holder.registerProblem(joins[maxJoins].element, PhantomDbBundle.message("sql.inspection.join.count", joins.size, maxJoins))
  }
}

/**
 * A SQL file of the project with no data source chosen while the project has some: its dialect is not known, so
 * names are not resolved and dialect checks do not run. The fix chooses one. The editor banner says the same; this
 * offers it from Alt+Enter and lists the files in a code inspection run.
 */
class SqlNoDataSourceInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val file = holder.file as? SqlFile ?: return PsiElementVisitor.EMPTY_VISITOR
    if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return PsiElementVisitor.EMPTY_VISITOR
    val virtualFile = file.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
    if (DbConsoles.isConsole(virtualFile)) return PsiElementVisitor.EMPTY_VISITOR
    if (DbDataSourceService.getInstance(file.project).dataSources.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR
    if (DbConsoles.dataSourceFor(file.project, virtualFile) != null) return PsiElementVisitor.EMPTY_VISITOR
    val first = file.statements.firstOrNull() ?: return PsiElementVisitor.EMPTY_VISITOR
    return object : PsiElementVisitor() {
      override fun visitElement(element: PsiElement) {
        if (element != first) return
        val anchor = SqlRoutine.significant(PsiTreeUtil.firstChild(first)) ?: return
        holder.registerProblem(anchor, PhantomDbBundle.message("sql.inspection.no.data.source"),
                               if (isOnTheFly) ProblemHighlightType.INFORMATION else ProblemHighlightType.WEAK_WARNING, ChooseDataSourceFix())
      }
    }
  }

  private class ChooseDataSourceFix : LocalQuickFix {
    override fun getFamilyName(): String = PhantomDbBundle.message("sql.fix.choose.data.source")

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val file = descriptor.psiElement?.containingFile?.virtualFile ?: return
      ApplicationManager.getApplication().invokeLater { DbRunner.chooseDataSource(project, file, null) {} }
    }
  }
}
