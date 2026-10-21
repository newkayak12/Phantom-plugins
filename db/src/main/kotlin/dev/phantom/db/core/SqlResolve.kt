// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.tree.TokenSet
import dev.phantom.db.core.exec.DbColumnInfo
import dev.phantom.db.core.exec.DbTableRef
import dev.phantom.db.core.ui.DbTreeNavigation
import javax.swing.Icon

/** What a name in SQL stands for: found, certainly missing, or not knowable now (schema not loaded, derived table, CTE). */
sealed interface SqlResolution {
  class Found(val target: PsiElement) : SqlResolution
  data object Missing : SqlResolution
  data object Unknown : SqlResolution
}

/**
 * A table or column of the database, as the target of a name in SQL. Navigating selects it in the Database tool
 * window. Two targets for the same table or column are equal, so highlighting and find usages group them.
 */
class SqlDbTarget(private val context: PsiElement, val dataSource: DbDataSource, val table: DbTableRef, val column: DbColumnInfo?) : FakePsiElement() {
  override fun getParent(): PsiElement = context.containingFile
  override fun getName(): String = column?.name ?: table.name
  override fun getIcon(open: Boolean): Icon = if (column != null) AllIcons.Nodes.DataColumn else AllIcons.Nodes.DataTables
  override fun getPresentableText(): String = column?.let { "${table.name}.${it.name}" } ?: table.name
  override fun getLocationString(): String = listOfNotNull(table.schema ?: table.catalog, dataSource.name.ifEmpty { null }).joinToString(" · ")
  override fun getPresentation(): ItemPresentation = this
  override fun canNavigate(): Boolean = true
  override fun canNavigateToSource(): Boolean = false
  override fun navigate(requestFocus: Boolean) = DbTreeNavigation.select(context.project, dataSource, table, column?.name)
  override fun isEquivalentTo(another: PsiElement?): Boolean = another == this
  override fun equals(other: Any?): Boolean =
    other is SqlDbTarget && other.dataSource.id == dataSource.id && other.table == table && other.column?.name == column?.name
  override fun hashCode(): Int = (dataSource.id.hashCode() * 31 + table.hashCode()) * 31 + column?.name.hashCode()
}

object SqlResolver {
  private fun schema(element: PsiElement, load: Boolean): Pair<DbDataSource, SqlSchemaSnapshot>? {
    val ds = SqlSchema.dataSourceFor(element.containingFile, strict = false) ?: return null
    return ds to (SqlSchema.getInstance(element.project).snapshot(ds, load) ?: return null)
  }

  fun table(ref: SqlTableRef, load: Boolean = true): SqlResolution {
    val name = ref.tableName.ifEmpty { return SqlResolution.Unknown }
    val schemaName = ref.schemaName
    if (schemaName == null) SqlScopes.ctes(ref).firstOrNull { SqlNames.same(it.name, name) }?.let { return SqlResolution.Found(it) }
    val (ds, current) = schema(ref, load) ?: return SqlResolution.Unknown
    val snapshot = if (schemaName == null) current
    else {
      // a schema the cache does not list (information_schema, pg_catalog): nothing to say
      val schema = current.schema(schemaName) ?: return SqlResolution.Unknown
      SqlSchema.getInstance(ref.project).withSchema(ds, schema, load) ?: return SqlResolution.Unknown
    }
    val table = snapshot.find(name, schemaName).firstOrNull() ?: return SqlResolution.Missing
    return SqlResolution.Found(SqlDbTarget(ref, ds, table, null))
  }

  /** `t` in `t.x`: an alias, a table in FROM, or a CTE, from the innermost query outwards. */
  fun qualifier(q: SqlQualifier): SqlResolution {
    val name = q.qualifierName.ifEmpty { return SqlResolution.Unknown }
    for (scope in SqlScopes.chain(q)) {
      SqlScopes.direct(scope, SqlAlias::class.java).firstOrNull { SqlNames.same(it.name, name) }?.let { return SqlResolution.Found(it) }
      SqlScopes.direct(scope, SqlTableRef::class.java).firstOrNull {
        it.alias == null && SqlNames.same(it.tableName, name) && (q.schemaName == null || it.schemaName == null || SqlNames.same(it.schemaName!!, q.schemaName!!))
      }?.let { return SqlResolution.Found(it) }
    }
    SqlScopes.ctes(q).firstOrNull { SqlNames.same(it.name, name) }?.let { return SqlResolution.Found(it) }
    return SqlResolution.Missing
  }

  fun column(ref: SqlColumnRef, load: Boolean = true): SqlResolution {
    val name = ref.columnName.ifEmpty { return SqlResolution.Unknown }
    val q = ref.qualifier
    if (q != null) {
      val source = when (val r = qualifier(q)) {
        is SqlResolution.Found -> r.target
        else -> return SqlResolution.Unknown // the qualifier gets its own warning
      }
      val table = when (source) {
        is SqlTableRef -> source
        is SqlAlias -> source.table ?: return SqlResolution.Unknown
        else -> return SqlResolution.Unknown
      }
      return columnOf(ref, table, name, load)
    }
    if (ref.isInsertColumn) {
      val target = SqlScopes.chain(ref).firstOrNull()?.let { SqlScopes.direct(it, SqlTableRef::class.java).firstOrNull() } ?: return SqlResolution.Unknown
      return columnOf(ref, target, name, load)
    }
    var unknown = false
    for (scope in SqlScopes.chain(ref)) {
      // ORDER BY total, HAVING total > 1: a select-list alias of the same query
      SqlScopes.direct(scope, SqlColumnAlias::class.java).firstOrNull { SqlNames.same(it.name, name) }?.let { return SqlResolution.Found(it) }
      for (table in SqlScopes.direct(scope, SqlTableRef::class.java)) {
        when (val r = columnOf(ref, table, name, load)) {
          is SqlResolution.Found -> return r
          SqlResolution.Unknown -> unknown = true
          SqlResolution.Missing -> {}
        }
      }
      // a derived table or table function has columns we do not know
      if (SqlScopes.direct(scope, SqlAlias::class.java).any { it.table == null }) unknown = true
      if (unknown) return SqlResolution.Unknown
    }
    return SqlResolution.Missing
  }

  private fun columnOf(context: PsiElement, table: SqlTableRef, name: String, load: Boolean): SqlResolution {
    val target = (table(table, load) as? SqlResolution.Found)?.target as? SqlDbTarget ?: return SqlResolution.Unknown
    val columns = SqlSchema.getInstance(context.project).columns(target.dataSource, target.table, load) ?: return SqlResolution.Unknown
    val column = columns.firstOrNull { SqlNames.same(it.name, name) } ?: return SqlResolution.Missing
    return SqlResolution.Found(SqlDbTarget(context, target.dataSource, target.table, column))
  }
}

class SqlTableReference(element: SqlTableRef) : PsiReferenceBase<SqlTableRef>(element, element.nameRange, true) {
  override fun resolve(): PsiElement? = (SqlResolver.table(element) as? SqlResolution.Found)?.target
}

class SqlQualifierReference(element: SqlQualifier) : PsiReferenceBase<SqlQualifier>(element, element.nameRange, true) {
  override fun resolve(): PsiElement? = when (val r = SqlResolver.qualifier(element)) {
    // `users.id` with `FROM users`: the database table, like the reference in FROM
    is SqlResolution.Found -> (r.target as? SqlTableRef)?.let { (SqlResolver.table(it) as? SqlResolution.Found)?.target ?: it } ?: r.target
    else -> null
  }
}

class SqlColumnReference(element: SqlColumnRef) : PsiReferenceBase<SqlColumnRef>(element, element.nameRange, true) {
  override fun resolve(): PsiElement? = (SqlResolver.column(element) as? SqlResolution.Found)?.target
}

/**
 * Unknown tables, columns and aliases in SELECT, INSERT, UPDATE and DELETE statements. Silent unless the data
 * source is known for sure and its schema loaded; names it cannot judge (derived tables, CTE columns, other schemas)
 * are left alone.
 */
class SqlUnresolvedAnnotator : Annotator {
  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    if (element !is SqlTableRef && element !is SqlColumnRef && element !is SqlQualifier) return
    val statement = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, SqlStatement::class.java) ?: return
    if (statement.firstKeyword !in CHECKED) return
    val file = element.containingFile
    val ds = SqlSchema.dataSourceFor(file, strict = true) ?: return
    if (SqlSchema.getInstance(element.project).snapshot(ds) == null) return
    val (resolution, message, range) = when (element) {
      is SqlTableRef -> Triple(SqlResolver.table(element), PhantomDbBundle.message("sql.unresolved.table", element.tableName), element.nameRange)
      is SqlQualifier -> Triple(SqlResolver.qualifier(element), PhantomDbBundle.message("sql.unresolved.qualifier", element.qualifierName), element.nameRange)
      is SqlColumnRef -> {
        if (element.qualifier == null && element.columnName.uppercase().let { it in BARE_WORDS || it in dialectWords(ds.dialect()) }) return
        Triple(SqlResolver.column(element), PhantomDbBundle.message("sql.unresolved.column", element.columnName), element.nameRange)
      }
      else -> return
    }
    if (resolution != SqlResolution.Missing) return
    holder.newAnnotation(HighlightSeverity.WARNING, message).range(range.shiftRight(element.textRange.startOffset)).create()
  }

  private companion object {
    val CHECKED = setOf("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "REPLACE")

    /** Bare words of standard SQL that are not columns: date parts and niladic functions. */
    val BARE_WORDS = setOf("YEAR", "MONTH", "WEEK", "HOUR", "MINUTE", "SECOND", "QUARTER", "MICROSECOND", "MILLISECOND",
                           "LOCALTIME", "LOCALTIMESTAMP", "SESSION_USER", "SYSTEM_USER", "USER")

    /** The same for one database: only the data source's own words apply. */
    fun dialectWords(dbms: Dbms): Set<String> = when (dbms) {
      Dbms.MYSQL, Dbms.MARIADB -> setOf("SYSDATE", "UTC_DATE", "UTC_TIME", "UTC_TIMESTAMP", "MODE", "YEAR_MONTH", "DAY_HOUR",
                                        "DAY_MINUTE", "DAY_SECOND", "HOUR_MINUTE", "HOUR_SECOND", "MINUTE_SECOND")
      Dbms.POSTGRESQL -> setOf("EPOCH", "DOW", "DOY", "ISODOW", "CURRENT_SCHEMA", "CURRENT_CATALOG", "CURRENT_ROLE", "EXCLUDED")
      Dbms.H2 -> setOf("CURRENT_SCHEMA", "CURRENT_CATALOG", "SYSDATE")
      Dbms.GENERIC -> emptySet()
    }
  }
}

/** Words for highlighting and finding alias usages in a file. */
class SqlFindUsagesProvider : FindUsagesProvider {
  override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(SqlLexer(), SqlTokens.NAMES, SqlTokens.COMMENTS, SqlTokens.STRINGS, TokenSet.EMPTY)
  override fun canFindUsagesFor(element: PsiElement): Boolean = element is SqlDeclaration
  override fun getHelpId(element: PsiElement): String? = null
  override fun getType(element: PsiElement): String = when (element) {
    is SqlCteName -> "common table expression"
    is SqlColumnAlias -> "column alias"
    else -> "alias"
  }
  override fun getDescriptiveName(element: PsiElement): String = (element as? SqlDeclaration)?.name ?: element.text
  override fun getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)
}
