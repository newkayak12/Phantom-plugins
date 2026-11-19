// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.jvm

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.SqlNames
import dev.phantom.db.core.exec.DbColumnInfo
import dev.phantom.db.core.exec.DbTableRef
import org.jetbrains.uast.UClass
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Entities checked against the database a data source points to (the counterpart of Ultimate's JPA/ORM vs data
 * source check). Runs only when the project uses JPA and has a data source the entities can be matched to, and only
 * with the schema metadata loaded: on demand, in the background, into the cache the Database tree shares. Until it
 * is loaded nothing is reported; the file is checked again when it arrives.
 */
abstract class JpaSchemaInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val project = holder.project
    if (!JpaSchemaModel.isJpaProject(project)) return PsiElementVisitor.EMPTY_VISITOR
    val dataSource = JpaSchemaModel.dataSource(project) ?: return PsiElementVisitor.EMPTY_VISITOR
    val naming = JpaSchemaModel.naming(project) ?: return PsiElementVisitor.EMPTY_VISITOR
    @Suppress("UNCHECKED_CAST")
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitClass(node: UClass): Boolean {
        val psiClass = node.javaPsi
        if (!JpaSchemaModel.isEntity(psiClass)) return true
        val table = JpaSchemaModel.table(psiClass, naming)
        val lookup = JpaSchemaModel.lookup(project, dataSource, table, load = true) ?: return true
        val anchor = { element: PsiElement? -> source(element, holder) ?: node.uastAnchor?.sourcePsi }
        check(Entity(psiClass, table, lookup, naming, dataSource), holder, anchor)
        return true
      }
    }, arrayOf(UClass::class.java) as Array<Class<out org.jetbrains.uast.UElement>>)
  }

  internal class Entity(val psiClass: PsiClass, val table: JpaSchemaModel.TableName, val lookup: JpaSchemaModel.Lookup,
               val naming: JpaNaming, val dataSource: DbDataSource)

  internal abstract fun check(entity: Entity, holder: ProblemsHolder, anchor: (PsiElement?) -> PsiElement?)

  /** The table and its columns, or null when the table is missing or its columns are not loaded. */
  internal fun columns(entity: Entity, holder: ProblemsHolder): Pair<DbTableRef, List<DbColumnInfo>>? {
    val table = entity.lookup.tables.firstOrNull() ?: return null
    return JpaSchemaModel.columnsOf(holder.project, entity.dataSource, table, load = true)?.let { table to it }
  }

  /** The element in the checked file for a (possibly light, for Kotlin) PSI element. */
  private fun source(element: PsiElement?, holder: ProblemsHolder): PsiElement? {
    val e = element?.navigationElement ?: return null
    val at = (e as? com.intellij.psi.PsiNameIdentifierOwner)?.nameIdentifier ?: e
    return at.takeIf { it.isValid && it.containingFile == holder.file && it.textLength > 0 }
  }
}

class JpaTableUnknownInspection : JpaSchemaInspection() {
  override fun check(entity: Entity, holder: ProblemsHolder, anchor: (PsiElement?) -> PsiElement?) {
    if (entity.lookup.tables.isNotEmpty()) return
    // a SINGLE_TABLE subclass is reported on its root entity
    if (JpaSchemaModel.tableOwner(entity.psiClass) != entity.psiClass) return
    val at = anchor(entity.table.anchor) ?: return
    val message = if (entity.table.schema != null) PhantomDbBundle.message("jpa.schema.table.unknown.in", entity.table.name, entity.table.schema, entity.dataSource.name)
                  else PhantomDbBundle.message("jpa.schema.table.unknown", entity.table.name, entity.dataSource.name)
    holder.registerProblem(at, message)
  }
}

class JpaColumnUnknownInspection : JpaSchemaInspection() {
  override fun check(entity: Entity, holder: ProblemsHolder, anchor: (PsiElement?) -> PsiElement?) {
    val (table, columns) = columns(entity, holder) ?: return
    val uses = JpaSchemaModel.columns(entity.psiClass, entity.naming) ?: return
    val names = columns.map { it.name }
    for (use in uses) {
      if (names.any { SqlNames.same(it, use.column) }) continue
      val inherited = use.owner != entity.psiClass
      val at = (if (inherited) null else anchor(use.anchor)) ?: anchor(null) ?: continue
      val message = if (inherited) PhantomDbBundle.message("jpa.schema.column.unknown.inherited", use.column, table.name, use.owner.name + "." + use.path)
                    else PhantomDbBundle.message("jpa.schema.column.unknown", use.column, table.name)
      holder.registerProblem(at, message)
    }
  }
}

/**
 * A column the database lets be NULL, mapped to an attribute that cannot hold null (a primitive or a Kotlin non-null
 * type): loading a row where it is NULL fails. Identifiers are left out.
 */
class JpaColumnNullabilityInspection : JpaSchemaInspection() {
  override fun check(entity: Entity, holder: ProblemsHolder, anchor: (PsiElement?) -> PsiElement?) {
    val (table, columns) = columns(entity, holder) ?: return
    val uses = JpaSchemaModel.columns(entity.psiClass, entity.naming) ?: return
    for (use in uses) {
      if (use.nullableInCode != false || use.owner != entity.psiClass || JpaSchemaModel.isId(use.field)) continue
      val column = columns.firstOrNull { SqlNames.same(it.name, use.column) } ?: continue
      if (!column.nullable || column.primaryKey) continue
      val at = anchor(use.field) ?: continue
      holder.registerProblem(at, PhantomDbBundle.message("jpa.schema.column.nullable", column.name, table.name, use.path))
    }
  }
}
