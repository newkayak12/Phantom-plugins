// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.jvm

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UNamedExpression
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElement

/**
 * Table names in `@Table(name = "…")` and column names in `@Column(name = "…")` / `@JoinColumn(name = "…")`, from
 * the data source the entities map to. Uses the metadata already loaded, or loads what is missing in the background
 * (completion runs off the EDT and waits briefly for it).
 */
class JpaSchemaCompletionContributor : CompletionContributor() {
  private val tableAnnotations = setOf("jakarta.persistence.Table", "javax.persistence.Table")
  private val columnAnnotations = setOf("jakarta.persistence.Column", "javax.persistence.Column",
                                        "jakarta.persistence.JoinColumn", "javax.persistence.JoinColumn")

  override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
    val position = parameters.position
    val host = PsiTreeUtil.getParentOfType(position, PsiLanguageInjectionHost::class.java, false) ?: return
    val project = position.project
    if (!JpaSchemaModel.isJpaProject(project)) return
    val literal = host.toUElement() as? UExpression ?: return
    val named = literal.uastParent as? UNamedExpression
    if ((named?.name ?: "value") != "name") return
    val annotation = (named?.uastParent ?: literal.uastParent) as? UAnnotation ?: return
    val fqn = annotation.qualifiedName ?: return
    val isTable = fqn in tableAnnotations
    if (!isTable && fqn !in columnAnnotations) return
    val dataSource = JpaSchemaModel.dataSource(project) ?: return
    val naming = JpaSchemaModel.naming(project) ?: return
    val entity = annotation.getParentOfType<UClass>()?.javaPsi ?: return
    if (!JpaSchemaModel.isEntity(entity)) return
    // what was typed before the caret inside the string
    val prefix = parameters.originalPosition?.text?.let { text ->
      val start = parameters.originalPosition!!.textRange.startOffset
      text.substring(0, (parameters.offset - start).coerceIn(0, text.length)).trimStart('"')
    }.orEmpty().takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
    val out = result.withPrefixMatcher(prefix)
    val table = JpaSchemaModel.table(entity, naming)
    if (isTable) {
      val snapshot = dev.phantom.db.core.SqlSchema.getInstance(project).snapshot(dataSource) ?: return
      val schemaName = table.schema ?: snapshot.current?.name
      for (t in snapshot.tables.filter { schemaName == null || dev.phantom.db.core.SqlNames.same(it.schema ?: it.catalog.orEmpty(), schemaName) }) {
        out.addElement(LookupElementBuilder.create(t.name).withTypeText(t.kind.lowercase()).withCaseSensitivity(false))
      }
      return
    }
    val lookup = JpaSchemaModel.lookup(project, dataSource, table, load = true) ?: return
    val ref = lookup.tables.firstOrNull() ?: return
    val columns = JpaSchemaModel.columnsOf(project, dataSource, ref, load = true) ?: return
    for (c in columns) {
      out.addElement(LookupElementBuilder.create(c.name).withTypeText(c.typeName + if (c.nullable) "" else " NOT NULL").withTailText(" " + ref.name, true)
                       .withCaseSensitivity(false))
    }
  }
}
