// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.icons.AllIcons
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
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
  override fun getLanguage(): com.intellij.lang.Language = SqlLanguage
  /** Renaming changes SQL text only (see [SqlDbRenameProcessor]); the database is left alone. */
  override fun setName(name: String): PsiElement = this
  /** SQL is in project files and in the consoles under Scratches. */
  override fun getUseScope(): com.intellij.psi.search.SearchScope =
    withConsoles(project, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
  override fun equals(other: Any?): Boolean =
    other is SqlDbTarget && other.dataSource.id == dataSource.id && other.table == table && other.column?.name == column?.name
  override fun hashCode(): Int = (dataSource.id.hashCode() * 31 + table.hashCode()) * 31 + column?.name.hashCode()
}

object SqlResolver {
  private fun schema(element: PsiElement, load: Boolean): Pair<DbDataSource, SqlSchemaSnapshot>? {
    val ds = SqlSchema.dataSourceFor(element.containingFile, strict = false) ?: return null
    return ds to (SqlSchema.getInstance(element.project).snapshotFor(element.containingFile, ds, load) ?: return null)
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
      SqlSchema.getInstance(ref.project).withSchema(ds, schema, load, SqlSchema.schemaFor(ref.containingFile)) ?: return SqlResolution.Unknown
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

/**
 * Whether a name in [element]'s file could be [target], checked without resolving: the same name, in a file of the
 * target's data source. Searches ask this of every candidate word, so a file of another data source is never loaded
 * (or connected to) for them.
 */
private fun couldBe(element: PsiElement, name: String, target: PsiElement): Boolean {
  if (target !is SqlDbTarget) return true
  if (!SqlNames.same(name, target.name)) return false
  return SqlSchema.dataSourceFor(element.containingFile, strict = false)?.id == target.dataSource.id
}

class SqlTableReference(element: SqlTableRef) : PsiReferenceBase<SqlTableRef>(element, element.nameRange, true) {
  override fun resolve(): PsiElement? = (SqlResolver.table(element) as? SqlResolution.Found)?.target
  override fun isReferenceTo(target: PsiElement): Boolean = couldBe(element, element.tableName, target) && super.isReferenceTo(target)
}

class SqlQualifierReference(element: SqlQualifier) : PsiReferenceBase<SqlQualifier>(element, element.nameRange, true) {
  override fun resolve(): PsiElement? = when (val r = SqlResolver.qualifier(element)) {
    // `users.id` with `FROM users`: the database table, like the reference in FROM
    is SqlResolution.Found -> (r.target as? SqlTableRef)?.let { (SqlResolver.table(it) as? SqlResolution.Found)?.target ?: it } ?: r.target
    else -> null
  }
  override fun isReferenceTo(target: PsiElement): Boolean = couldBe(element, element.qualifierName, target) && super.isReferenceTo(target)
}

class SqlColumnReference(element: SqlColumnRef) : PsiReferenceBase<SqlColumnRef>(element, element.nameRange, true) {
  override fun resolve(): PsiElement? = (SqlResolver.column(element) as? SqlResolution.Found)?.target
  override fun isReferenceTo(target: PsiElement): Boolean = couldBe(element, element.columnName, target) && super.isReferenceTo(target)
}

/**
 * Unknown tables, columns and aliases in SELECT, INSERT, UPDATE and DELETE statements. Silent unless the data
 * source is known for sure and its schema loaded; names it cannot judge (derived tables, CTE columns, other schemas)
 * are left alone.
 */
abstract class SqlUnresolvedInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val file = holder.file as? SqlFile ?: return PsiElementVisitor.EMPTY_VISITOR
    val ds = SqlSchema.dataSourceFor(file, strict = true) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (SqlSchema.getInstance(file.project).snapshotFor(file, ds) == null) return PsiElementVisitor.EMPTY_VISITOR
    return object : PsiElementVisitor() {
      override fun visitElement(element: PsiElement) {
        if (element !is SqlTableRef && element !is SqlColumnRef && element !is SqlQualifier) return
        val statement = PsiTreeUtil.getParentOfType(element, SqlStatement::class.java) ?: return
        if (statement.firstKeyword !in CHECKED) return
        check(element, ds, holder)
      }
    }
  }

  protected abstract fun check(element: PsiElement, dataSource: DbDataSource, holder: ProblemsHolder)

  protected fun register(holder: ProblemsHolder, element: PsiElement, range: TextRange, message: String, fixes: List<LocalQuickFix>) {
    holder.registerProblem(element, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, range, *fixes.toTypedArray())
  }

  private companion object {
    val CHECKED = setOf("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "REPLACE")
  }
}

/** A table, or the table or alias before a column (`x` in `x.id`), that the query and the schema do not have. */
class SqlUnresolvedTableInspection : SqlUnresolvedInspection() {
  override fun check(element: PsiElement, dataSource: DbDataSource, holder: ProblemsHolder) {
    when (element) {
      is SqlTableRef -> if (SqlResolver.table(element) == SqlResolution.Missing) {
        register(holder, element, element.nameRange, PhantomDbBundle.message("sql.unresolved.table", element.tableName), emptyList())
      }
      is SqlQualifier -> if (SqlResolver.qualifier(element) == SqlResolution.Missing) {
        register(holder, element, element.nameRange, PhantomDbBundle.message("sql.unresolved.qualifier", element.qualifierName),
                 aliasFixes(element, dataSource))
      }
    }
  }

  /** `SELECT g.name FROM guest`: give `guest` the alias `g`. Offered for tables without an alias that have the column. */
  private fun aliasFixes(q: SqlQualifier, dataSource: DbDataSource): List<LocalQuickFix> {
    if (q.schemaName != null) return emptyList()
    val column = (q.parent as? SqlColumnRef)?.columnName
    val schema = SqlSchema.getInstance(q.project)
    val scope = SqlScopes.chain(q).firstOrNull() ?: return emptyList()
    val tables = SqlScopes.direct(scope, SqlTableRef::class.java).filter { it.alias == null && SqlScopes.ctes(it).none { c -> SqlNames.same(c.name, it.tableName) } }
    val candidates = tables.filter { ref ->
      if (column == null) return@filter true
      val target = (SqlResolver.table(ref, load = false) as? SqlResolution.Found)?.target as? SqlDbTarget ?: return@filter false
      schema.columns(dataSource, target.table, load = false)?.any { SqlNames.same(it.name, column) } == true
    }
    if (column == null && candidates.size != 1) return emptyList()
    return candidates.take(3).map { SqlAddAliasFix(it, q.nameRange.substring(q.text)) }
  }
}

/** A column that none of the query's tables has. */
class SqlUnresolvedColumnInspection : SqlUnresolvedInspection() {
  override fun check(element: PsiElement, dataSource: DbDataSource, holder: ProblemsHolder) {
    if (element !is SqlColumnRef) return
    if (element.qualifier == null && element.columnName.uppercase().let { it in BARE_WORDS || it in dialectWords(dataSource.dialect()) }) return
    if (SqlResolver.column(element) != SqlResolution.Missing) return
    register(holder, element, element.nameRange, PhantomDbBundle.message("sql.unresolved.column", element.columnName), qualifyFixes(element, dataSource))
  }

  /** `r.name` where `r` has no `name` but another table of the query does: qualify with that one instead. */
  private fun qualifyFixes(ref: SqlColumnRef, dataSource: DbDataSource): List<LocalQuickFix> {
    val q = ref.qualifier ?: return emptyList()
    val schema = SqlSchema.getInstance(ref.project)
    val names = ArrayList<String>()
    for (scope in SqlScopes.chain(ref)) {
      for (table in SqlScopes.direct(scope, SqlTableRef::class.java)) {
        val target = (SqlResolver.table(table, load = false) as? SqlResolution.Found)?.target as? SqlDbTarget ?: continue
        if (schema.columns(dataSource, target.table, load = false)?.any { SqlNames.same(it.name, ref.columnName) } != true) continue
        val name = table.alias?.text ?: table.nameRange.substring(table.text)
        if (!SqlNames.same(SqlNames.unquote(name), q.qualifierName)) names += name
      }
    }
    return names.distinct().take(3).map { SqlQualifyFix(q, it) }
  }

  private companion object {
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

/**
 * Adds an alias after a table reference (`FROM guest` → `FROM guest g`) and writes it for the other qualifiers that
 * named the table (`guest.id` → `g.id`), which the alias would hide.
 */
class SqlAddAliasFix(table: SqlTableRef, private val alias: String) : LocalQuickFixOnPsiElement(table) {
  private val tableName = table.tableName

  override fun getFamilyName(): String = PhantomDbBundle.message("sql.fix.add.alias")
  override fun getText(): String = PhantomDbBundle.message("sql.fix.add.alias.to", alias, tableName)

  override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
    val table = startElement as? SqlTableRef ?: return
    val statement = PsiTreeUtil.getParentOfType(table, SqlStatement::class.java) ?: return
    val qualifiers = PsiTreeUtil.findChildrenOfType(statement, SqlQualifier::class.java)
      .filter { (SqlResolver.qualifier(it) as? SqlResolution.Found)?.target == table }
    val edits = listOfNotNull(SqlTextEdits.edit(file, TextRange.from(table.textRange.endOffset, 0), " $alias")) +
                qualifiers.mapNotNull { SqlTextEdits.edit(file, it.textRange, alias) }
    SqlTextEdits.apply(project, edits)
  }
}

/** Replaces the qualifier of a column with the table or alias that has it, as that one is written: `r.name` → `g.name`. */
class SqlQualifyFix(qualifier: SqlQualifier, private val name: String) : LocalQuickFixOnPsiElement(qualifier) {
  override fun getFamilyName(): String = PhantomDbBundle.message("sql.fix.qualify.family")
  override fun getText(): String = PhantomDbBundle.message("sql.fix.qualify", name)

  override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
    SqlTextEdits.apply(project, listOfNotNull(SqlTextEdits.edit(file, startElement.textRange, name)))
  }
}

/** Words for highlighting and finding usages: aliases in a file, database tables and columns in the project. */
class SqlFindUsagesProvider : FindUsagesProvider {
  override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(SqlLexer(), SqlTokens.NAMES, SqlTokens.COMMENTS, SqlTokens.STRINGS, TokenSet.EMPTY)
  override fun canFindUsagesFor(element: PsiElement): Boolean = element is SqlDeclaration || element is SqlDbTarget
  override fun getHelpId(element: PsiElement): String? = null
  override fun getType(element: PsiElement): String = when (element) {
    is SqlDbTarget -> if (element.column != null) "column" else if (element.table.isView) "view" else "table"
    is SqlCteName -> "common table expression"
    is SqlColumnAlias -> "column alias"
    else -> "alias"
  }
  override fun getDescriptiveName(element: PsiElement): String = when (element) {
    is SqlDbTarget -> element.presentableText
    is SqlDeclaration -> element.name
    else -> element.text
  }
  override fun getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)
}
