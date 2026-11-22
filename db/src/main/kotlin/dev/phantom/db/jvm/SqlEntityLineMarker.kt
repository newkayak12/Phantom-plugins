// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.jvm

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.SqlNames
import dev.phantom.db.core.SqlRoutine
import dev.phantom.db.core.SqlStatement
import dev.phantom.db.core.SqlTableRef
import dev.phantom.db.core.SqlTokens
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon

@TestOnly
internal val entityScanCount = AtomicInteger()

/**
 * On the table name of `CREATE TABLE` in a SQL file (a migration, a schema script): the JPA entities mapped to that
 * table, by the same naming the schema checks use. Nothing when the project's naming strategy is unknown.
 */
class SqlEntityLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomDbBundle.message("gutter.sql.entity.name")

  override fun getIcon(): Icon = AllIcons.Nodes.Class

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null || element.node.elementType !in SqlTokens.NAMES) return null
    val ref = element.parent as? SqlTableRef ?: return null
    if (SqlNames.parts(ref).lastOrNull()?.psi != element) return null
    val statement = ref.parent as? SqlStatement ?: return null
    if (statement.firstKeyword != "CREATE" || !createsTable(statement, ref)) return null
    val project = element.project
    if (DumbService.isDumb(project) || InjectedLanguageManager.getInstance(project).isInjectedFragment(element.containingFile)) return null
    val entities = entitiesByTable(project)[ref.tableName.lowercase()].orEmpty()
    if (entities.isEmpty()) return null
    return NavigationGutterIconBuilder.create(AllIcons.Nodes.Class)
      .setTargets(NotNullLazyValue.lazy { entities.filter { it.isValid } })
      .setTooltipText(PhantomDbBundle.message("gutter.sql.entity.tooltip", entities.joinToString(", ") { it.name.orEmpty() }))
      .setPopupTitle(PhantomDbBundle.message("gutter.sql.entity.popup", ref.tableName))
      .createLineMarkerInfo(element)
  }

  /** `CREATE [TEMPORARY] TABLE [IF NOT EXISTS] name`: the reference is the table being created. */
  private fun createsTable(statement: SqlStatement, ref: SqlTableRef): Boolean {
    var leaf = SqlRoutine.significant(PsiTreeUtil.firstChild(statement))
    while (leaf != null && !PsiTreeUtil.isAncestor(ref, leaf, false)) {
      val word = SqlRoutine.word(leaf)
      if (word !in HEADER) return false
      leaf = SqlRoutine.significant(PsiTreeUtil.nextLeaf(leaf))
    }
    return leaf != null
  }

  private companion object {
    val HEADER = setOf("CREATE", "TEMPORARY", "TABLE", "IF", "NOT", "EXISTS", "OR", "REPLACE")
    val ENTITY = listOf("jakarta.persistence.Entity", "javax.persistence.Entity")

    /** Lowercased physical table name → the entities whose table it is. */
    fun entitiesByTable(project: Project): Map<String, List<PsiClass>> = CachedValuesManager.getManager(project).getCachedValue(project) {
      entityScanCount.incrementAndGet()
      val result = HashMap<String, MutableList<PsiClass>>()
      // entities live in JVM sources; a SQL keystroke does not touch them. The naming also depends on the config files
      val deps = arrayListOf<Any>(CacheDeps.jvm(project), ProjectRootManager.getInstance(project))
      val naming = if (JpaSchemaModel.isJpaProject(project)) {
        deps.addAll(JpaSpringConfig.dependencies(project, JpaSpringConfig.get(project)))
        JpaSchemaModel.naming(project)
      }
      else null
      if (naming != null) {
        val facade = JavaPsiFacade.getInstance(project)
        val all = GlobalSearchScope.allScope(project)
        val scope = GlobalSearchScope.projectScope(project)
        for (fqn in ENTITY) {
          val annotation = facade.findClass(fqn, all) ?: continue
          for (entity in AnnotatedElementsSearch.searchPsiClasses(annotation, scope).findAll()) {
            val table = JpaSchemaModel.table(entity, naming).name
            result.getOrPut(table.lowercase()) { ArrayList() }.add(entity)
          }
        }
      }
      CachedValueProvider.Result.create(result, *deps.toTypedArray())
    }
  }
}
