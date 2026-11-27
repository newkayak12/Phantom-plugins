// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.FileContentUtilCore
import dev.phantom.db.core.exec.DbConsoles

/**
 * Syntax that only some dialects have. The parser accepts it in every dialect; a construct the file's dialect lacks is
 * reported by an annotator, not by a parse error, so switching data sources does not break the tree (#164).
 */
enum class SqlFeature {
  /** `INSERT/UPDATE/DELETE ... RETURNING`. */
  RETURNING,
  /** `INSERT ... ON CONFLICT (...) DO NOTHING | DO UPDATE`. */
  ON_CONFLICT,
  /** `INSERT ... ON DUPLICATE KEY UPDATE`. */
  ON_DUPLICATE_KEY,
  /** `MERGE INTO ... USING ... WHEN [NOT] MATCHED`. */
  MERGE,
  /** `LIMIT n [OFFSET m]`. */
  LIMIT_OFFSET,
  /** `OFFSET m ROWS FETCH FIRST n ROWS ONLY`. */
  FETCH_FIRST,
  /** `SELECT TOP n`. */
  TOP,
  /** `QUALIFY` after the window functions of a query. */
  QUALIFY,
  /** `LATERAL` derived tables. */
  LATERAL,
  /** `SELECT DISTINCT ON (...)`. */
  DISTINCT_ON,
  /** `x::type`. */
  CAST_OPERATOR,
  /** Case-insensitive `ILIKE`. */
  ILIKE,
  /** `REPLACE INTO`. */
  REPLACE,
  /** `CREATE SEQUENCE` and `NEXTVAL`. */
  SEQUENCES,
}

/**
 * The SQL one kind of database speaks: the words it reserves and the [SqlFeature]s it has. Grammar hooks for constructs
 * whose shape differs per dialect come with the statement parsers that need them.
 */
interface SqlDialect {
  /** Null for [SqlDialects.ANY]. */
  val dbms: Dbms?
  /** Uppercase words that need quotes to be used as names. */
  val reservedWords: Set<String>
  val features: Set<SqlFeature>

  fun isReserved(word: String): Boolean = word.uppercase() in reservedWords
}

/** MySQL 8.4: the reserved words of the manual's keyword list (window function names included). */
object MySqlDialect : SqlDialect {
  override val dbms: Dbms = Dbms.MYSQL
  override val reservedWords: Set<String> = words(
    """
    ACCESSIBLE ADD ALL ALTER ANALYZE AND AS ASC ASENSITIVE BEFORE BETWEEN BIGINT BINARY BLOB BOTH BY CALL CASCADE CASE CHANGE
    CHAR CHARACTER CHECK COLLATE COLUMN CONDITION CONSTRAINT CONTINUE CONVERT CREATE CROSS CUBE CUME_DIST CURRENT_DATE
    CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER CURSOR DATABASE DATABASES DAY_HOUR DAY_MICROSECOND DAY_MINUTE DAY_SECOND DEC
    DECIMAL DECLARE DEFAULT DELAYED DELETE DENSE_RANK DESC DESCRIBE DETERMINISTIC DISTINCT DISTINCTROW DIV DOUBLE DROP DUAL
    EACH ELSE ELSEIF EMPTY ENCLOSED ESCAPED EXCEPT EXISTS EXIT EXPLAIN FALSE FETCH FIRST_VALUE FLOAT FLOAT4 FLOAT8 FOR FORCE
    FOREIGN FROM FULLTEXT FUNCTION GENERATED GET GRANT GROUP GROUPING GROUPS HAVING HIGH_PRIORITY HOUR_MICROSECOND
    HOUR_MINUTE HOUR_SECOND IF IGNORE IN INDEX INFILE INNER INOUT INSENSITIVE INSERT INT INT1 INT2 INT3 INT4 INT8 INTEGER
    INTERSECT INTERVAL INTO IS ITERATE JOIN JSON_TABLE KEY KEYS KILL LAG LAST_VALUE LATERAL LEAD LEADING LEAVE LEFT LIKE
    LIMIT LINEAR LINES LOAD LOCALTIME LOCALTIMESTAMP LOCK LONG LONGBLOB LONGTEXT LOOP LOW_PRIORITY MATCH MAXVALUE MEDIUMBLOB
    MEDIUMINT MEDIUMTEXT MIDDLEINT MINUTE_MICROSECOND MINUTE_SECOND MOD MODIFIES NATURAL NOT NO_WRITE_TO_BINLOG NTH_VALUE
    NTILE NULL NUMERIC OF ON OPTIMIZE OPTIMIZER_COSTS OPTION OPTIONALLY OR ORDER OUT OUTER OUTFILE OVER PARTITION
    PERCENT_RANK PRECISION PRIMARY PROCEDURE PURGE RANGE RANK READ READS READ_WRITE REAL RECURSIVE REFERENCES REGEXP RELEASE
    RENAME REPEAT REPLACE REQUIRE RESIGNAL RESTRICT RETURN REVOKE RIGHT RLIKE ROW ROWS ROW_NUMBER SCHEMA SCHEMAS
    SECOND_MICROSECOND SELECT SENSITIVE SEPARATOR SET SHOW SIGNAL SMALLINT SPATIAL SPECIFIC SQL SQLEXCEPTION SQLSTATE
    SQLWARNING SQL_BIG_RESULT SQL_CALC_FOUND_ROWS SQL_SMALL_RESULT SSL STARTING STORED STRAIGHT_JOIN SYSTEM TABLE TERMINATED
    THEN TINYBLOB TINYINT TINYTEXT TO TRAILING TRIGGER TRUE UNDO UNION UNIQUE UNLOCK UNSIGNED UPDATE USAGE USE USING
    UTC_DATE UTC_TIME UTC_TIMESTAMP VALUES VARBINARY VARCHAR VARCHARACTER VARYING VIRTUAL WHEN WHERE WHILE WINDOW WITH WRITE
    XOR YEAR_MONTH ZEROFILL
    """,
  )
  override val features: Set<SqlFeature> =
    setOf(SqlFeature.ON_DUPLICATE_KEY, SqlFeature.LIMIT_OFFSET, SqlFeature.LATERAL, SqlFeature.REPLACE)
}

/**
 * MariaDB 11: MySQL's syntax with `RETURNING`, sequences and `FETCH FIRST`, without `LATERAL`. Window function names
 * and a few MySQL 8 additions are not reserved.
 */
object MariaDbDialect : SqlDialect {
  override val dbms: Dbms = Dbms.MARIADB
  override val reservedWords: Set<String> = MySqlDialect.reservedWords - words(
    """
    CUBE CUME_DIST DENSE_RANK EMPTY FIRST_VALUE FUNCTION GROUPING GROUPS JSON_TABLE LAG LAST_VALUE LATERAL LEAD NTH_VALUE
    NTILE OF PERCENT_RANK RANK ROW ROW_NUMBER SYSTEM
    """,
  ) + words("OFFSET RETURNING")
  override val features: Set<SqlFeature> =
    MySqlDialect.features - SqlFeature.LATERAL + setOf(SqlFeature.RETURNING, SqlFeature.SEQUENCES, SqlFeature.FETCH_FIRST)
}

/** PostgreSQL 17: the words the manual's keyword appendix marks reserved (also those reserved only as function or type names). */
object PostgresDialect : SqlDialect {
  override val dbms: Dbms = Dbms.POSTGRESQL
  override val reservedWords: Set<String> = words(
    """
    ALL ANALYSE ANALYZE AND ANY ARRAY AS ASC ASYMMETRIC AUTHORIZATION BINARY BOTH CASE CAST CHECK COLLATE COLLATION COLUMN
    CONCURRENTLY CONSTRAINT CREATE CROSS CURRENT_CATALOG CURRENT_DATE CURRENT_ROLE CURRENT_SCHEMA CURRENT_TIME
    CURRENT_TIMESTAMP CURRENT_USER DEFAULT DEFERRABLE DESC DISTINCT DO ELSE END EXCEPT FALSE FETCH FOR FOREIGN FREEZE FROM
    FULL GRANT GROUP HAVING ILIKE IN INITIALLY INNER INTERSECT INTO IS ISNULL JOIN LATERAL LEADING LEFT LIKE LIMIT LOCALTIME
    LOCALTIMESTAMP NATURAL NOT NOTNULL NULL OFFSET ON ONLY OR ORDER OUTER OVERLAPS PLACING PRIMARY REFERENCES RETURNING RIGHT
    SELECT SESSION_USER SIMILAR SOME SYMMETRIC SYSTEM_USER TABLE TABLESAMPLE THEN TO TRAILING TRUE UNION UNIQUE USER USING
    VARIADIC VERBOSE WHEN WHERE WINDOW WITH
    """,
  )
  override val features: Set<SqlFeature> = setOf(
    SqlFeature.RETURNING, SqlFeature.ON_CONFLICT, SqlFeature.MERGE, SqlFeature.LIMIT_OFFSET, SqlFeature.FETCH_FIRST,
    SqlFeature.LATERAL, SqlFeature.DISTINCT_ON, SqlFeature.CAST_OPERATOR, SqlFeature.ILIKE, SqlFeature.SEQUENCES,
  )
}

/** H2 2.x in its default mode: the keyword list of its grammar documentation. */
object H2Dialect : SqlDialect {
  override val dbms: Dbms = Dbms.H2
  override val reservedWords: Set<String> = words(
    """
    ALL AND ANY ARRAY AS ASYMMETRIC AUTHORIZATION BETWEEN BOTH CASE CAST CHECK CONSTRAINT CROSS CURRENT_CATALOG CURRENT_DATE
    CURRENT_PATH CURRENT_ROLE CURRENT_SCHEMA CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER DAY DEFAULT DISTINCT ELSE END
    EXCEPT EXISTS FALSE FETCH FOR FOREIGN FROM FULL GROUP GROUPS HAVING HOUR IF ILIKE IN INNER INTERSECT INTERVAL IS JOIN
    KEY LEADING LEFT LIKE LIMIT LOCALTIME LOCALTIMESTAMP MINUS MINUTE MONTH NATURAL NOT NULL OFFSET ON OR ORDER OVER
    PARTITION PRIMARY QUALIFY RANGE REGEXP RIGHT ROW ROWNUM ROWS SECOND SELECT SESSION_USER SET SOME SYMMETRIC SYSTEM_USER
    TABLE TO TOP TRAILING TRUE UESCAPE UNION UNIQUE UNKNOWN USER USING VALUE VALUES WHEN WHERE WINDOW WITH YEAR _ROWID_
    """,
  )
  override val features: Set<SqlFeature> = setOf(
    SqlFeature.MERGE, SqlFeature.LIMIT_OFFSET, SqlFeature.FETCH_FIRST, SqlFeature.TOP, SqlFeature.QUALIFY, SqlFeature.ILIKE,
    SqlFeature.SEQUENCES,
  )
}

object SqlDialects {
  /** The dialects with grammar of their own, in [Dbms] order. */
  val SUPPORTED: List<SqlDialect> = listOf(MySqlDialect, MariaDbDialect, PostgresDialect, H2Dialect)

  /**
   * Used when no data source is attached, for injected SQL and for databases without dialect knowledge: every supported
   * dialect's syntax, so their SQL opens without false errors. A word is reserved only when every dialect reserves it.
   */
  val ANY: SqlDialect = object : SqlDialect {
    override val dbms: Dbms? = null
    override val reservedWords: Set<String> = SUPPORTED.map { it.reservedWords }.reduce { a, b -> a intersect b }
    override val features: Set<SqlFeature> = SUPPORTED.flatMapTo(HashSet()) { it.features }
    override fun toString(): String = "ANY"
  }

  /**
   * The dialect of a SQL file, on its virtual file. The parser reads it and falls back to [ANY]; it never asks the data
   * source registry, because a parse runs where project services are not safe to use.
   */
  val KEY: Key<SqlDialect> = Key.create("phantom.db.sqlDialect")

  fun forDbms(dbms: Dbms?): SqlDialect = SUPPORTED.firstOrNull { it.dbms == dbms } ?: ANY

  /**
   * Puts the dialect of [file]'s data source on it and, when that changed, reparses it later on the EDT: a reparse
   * replaces the file's PSI, which the caller (a data source popup, an opening editor) may still hold.
   */
  fun update(project: Project, file: VirtualFile) {
    val dialect = forDbms(DbConsoles.dataSourceFor(project, file)?.dialect())
    if ((file.getUserData(KEY) ?: ANY) === dialect) return
    file.putUserData(KEY, dialect)
    ApplicationManager.getApplication().invokeLater({ FileContentUtilCore.reparseFiles(file) }, project.disposed)
  }
}

/**
 * The dialect key lives only in memory, so a SQL file gets it from its data source when an editor opens it;
 * [DbConsoles.attach] updates it when the user picks another data source.
 */
internal class SqlDialectFileListener : FileEditorManagerListener {
  override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
    if (file.fileType == SqlFileType.INSTANCE) SqlDialects.update(source.project, file)
  }
}

private fun words(text: String): Set<String> = text.trim().split(Regex("\\s+")).toSet()
