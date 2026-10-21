// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

class SqlIntelligenceTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private lateinit var ds: DbDataSource

  override fun setUp() {
    super.setUp()
    ds = DbDataSource().apply {
      name = "hotel"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:sql_intel_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    runBlocking {
      DbExecutor.execute(project, ds, listOf(
        "CREATE TABLE guest (id BIGINT PRIMARY KEY, name VARCHAR(40), email VARCHAR(80))",
        "CREATE TABLE reservation (id BIGINT PRIMARY KEY, guest_id BIGINT, room_no INT, status VARCHAR(10))",
        "CREATE SCHEMA audit",
        "CREATE TABLE audit.log_entry (id BIGINT, message VARCHAR(100))",
      ))
    }
  }

  override fun tearDown() {
    try {
      DbConnectionService.getInstance(project).disconnect(ds.id)
      DbDataSourceService.getInstance(project).remove(ds.id)
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  private fun sql(text: String): SqlFile {
    val file = myFixture.configureByText("q.sql", text) as SqlFile
    DbConsoles.attach(project, file.virtualFile, ds.id)
    return file
  }

  /** What the EDT-bound test cannot wait for: the current schema's tables, and the columns of the given tables. */
  private fun preload(vararg tables: String) {
    val schema = SqlSchema.getInstance(project)
    runBlocking {
      val snapshot = schema.load(ds)!!
      for (name in tables) {
        val (s, t) = if ('.' in name) name.substringBefore('.') to name.substringAfter('.') else null to name
        val ref = if (s == null) snapshot.find(t, null).single()
        else {
          val schemaRef = snapshot.schema(s)!!
          DbMetadataCache.getInstance(project).tables(ds, schemaRef).single { it.name.equals(t, true) }
        }
        DbMetadataCache.getInstance(project).columns(ds, ref)
      }
    }
  }

  private inline fun <reified T : com.intellij.psi.PsiElement> names(file: SqlFile, crossinline name: (T) -> String): List<String> =
    PsiTreeUtil.findChildrenOfType(file, T::class.java).map { name(it) }

  fun testParserMarksNames() {
    val file = sql("""
      WITH recent AS (SELECT * FROM reservation)
      SELECT r.id, g.name AS guest_name, count(*) total, CASE WHEN r.room_no > 1 THEN 'a' ELSE 'b' END kind,
             EXTRACT(YEAR FROM r.created), CAST(r.id AS int4), r.status::text
      FROM recent, (SELECT id FROM audit.log_entry) d, reservation r JOIN guest AS g ON g.id = r.guest_id
      WHERE r.status = 'X' ORDER BY total;
      INSERT INTO reservation (id, status) VALUES (1, 'A') ON DUPLICATE KEY UPDATE status = 'B';
      UPDATE guest SET email = 'x' WHERE id = 1
    """.trimIndent())
    assertEquals(listOf("reservation", "recent", "log_entry", "reservation", "guest", "reservation", "guest"), names<SqlTableRef>(file) { it.tableName })
    assertEquals("audit", PsiTreeUtil.findChildrenOfType(file, SqlTableRef::class.java).single { it.tableName == "log_entry" }.schemaName)
    assertEquals(listOf("d", "r", "g"), names<SqlAlias>(file) { it.name })
    assertEquals(listOf("guest_name", "total", "kind"), names<SqlColumnAlias>(file) { it.name })
    assertEquals(listOf("recent"), names<SqlCteName>(file) { it.name })
    val columns = PsiTreeUtil.findChildrenOfType(file, SqlColumnRef::class.java).map { (it.qualifier?.qualifierName?.let { q -> "$q." } ?: "") + it.columnName }
    assertEquals(listOf("r.id", "g.name", "r.room_no", "YEAR", "r.created", "r.id", "r.status", "id", "g.id", "r.guest_id", "r.status", "total",
                        "id", "status", "status", "email", "id"), columns)
    assertEquals(2, PsiTreeUtil.findChildrenOfType(file, SqlColumnRef::class.java).count { it.isInsertColumn })
    assertEquals(listOf("WITH", "INSERT", "UPDATE"), file.statements.map { it.firstKeyword })
    assertEquals(2, PsiTreeUtil.findChildrenOfType(file, SqlSubquery::class.java).size)
  }

  fun testCompletion() {
    preload("reservation", "guest", "audit.log_entry")
    sql("SELECT r.<caret> FROM reservation r")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, "ID", "GUEST_ID", "ROOM_NO", "STATUS")

    sql("SELECT <caret> FROM reservation r JOIN guest g ON g.id = r.guest_id")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "ROOM_NO", "EMAIL", "r", "g", "SELECT")

    sql("SELECT * FROM <caret>")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "GUEST", "RESERVATION", "AUDIT")

    sql("SELECT * FROM audit.<caret>")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, "LOG_ENTRY")

    sql("INSERT INTO reservation (id, <caret>)")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, "ID", "GUEST_ID", "ROOM_NO", "STATUS")

    sql("SELECT l.<caret> FROM audit.log_entry l")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, "ID", "MESSAGE")
  }

  fun testResolveToDatabaseAndAliases() {
    preload("reservation", "guest")
    val file = sql("SELECT r.status, name FROM reservation r JOIN guest g ON g.id = r.guest_id")
    fun at(text: String, occurrence: Int = 0): com.intellij.psi.PsiElement? {
      var offset = -1
      repeat(occurrence + 1) { offset = file.text.indexOf(text, offset + 1) }
      return file.findReferenceAt(offset)?.resolve()
    }
    val status = at("status") as SqlDbTarget
    assertEquals("RESERVATION", status.table.name)
    assertEquals("STATUS", status.column!!.name)
    assertEquals("GUEST", (at("name") as SqlDbTarget).table.name)
    assertEquals("r", (at("r.", 0) as SqlAlias).name)
    assertEquals("RESERVATION", (at("reservation") as SqlDbTarget).table.name)
  }

  /** Off the EDT, as the daemon and completion run, the schema and columns load on demand. */
  fun testLoadsOnDemandOffTheEdt() {
    val file = sql("SELECT r.room_no FROM reservation r")
    val ref = PsiTreeUtil.findChildOfType(file, SqlColumnRef::class.java)!!
    val result = ApplicationManager.getApplication().executeOnPooledThread<SqlResolution> {
      ReadAction.compute<SqlResolution, RuntimeException> { SqlResolver.column(ref) }
    }.get(10, TimeUnit.SECONDS)
    assertEquals("ROOM_NO", ((result as SqlResolution.Found).target as SqlDbTarget).column!!.name)
    // only what the name needed: the current schema's tables and this table's columns, not the other schema
    val snapshot = SqlSchema.getInstance(project).snapshot(ds, load = false)!!
    assertDoesntContain(snapshot.tables.map { it.name }, "LOG_ENTRY")
    assertNull(DbMetadataCache.cachedColumns(project, snapshot.find("GUEST", null).single()))
  }

  fun testUnresolvedWarningsWithoutFalsePositives() {
    myFixture.enableInspections(SqlUnresolvedTableInspection::class.java, SqlUnresolvedColumnInspection::class.java)
    preload("reservation", "guest", "audit.log_entry")
    sql("""
      SELECT r.id, r.nope, g.name, x.id FROM reservation r JOIN guest g ON g.id = r.guest_id WHERE missing_col = 1;
      SELECT * FROM no_such_table;
      WITH recent AS (SELECT id, status FROM reservation) SELECT recent.status, rid FROM recent, (SELECT id AS rid FROM guest) d;
      SELECT count(*) AS total, status FROM reservation GROUP BY status HAVING total > 1 ORDER BY total;
      SELECT id FROM information_schema.tables;
      SELECT EXTRACT(YEAR FROM CURRENT_DATE), CAST(id AS VARCHAR(10)), :p, ?, @x, `id`, "NAME" FROM guest WHERE name LIKE 'a%';
      INSERT INTO reservation (id, status, bogus) VALUES (1, 'A', 2);
      UPDATE guest SET email = 'x' WHERE id = 1;
      SELECT g.id FROM guest g WHERE EXISTS (SELECT 1 FROM reservation r WHERE r.guest_id = g.id);
      CREATE TABLE new_one (id INT, whatever INT);
      SELECT * FROM audit.log_entry l WHERE l.message IS NOT NULL;
      SELECT now(), coalesce(email, name) FROM guest;
    """.trimIndent())
    val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING).filter { it.severity == HighlightSeverity.WARNING }.map { it.description }
    assertSameElements(warnings, "Unknown column 'nope'", "Unknown table or alias 'x'", "Unknown column 'missing_col'",
                       "Unknown table 'no_such_table'", "Unknown column 'bogus'")
  }

  fun testNoWarningsWithoutADataSource() {
    myFixture.enableInspections(SqlUnresolvedTableInspection::class.java, SqlUnresolvedColumnInspection::class.java)
    myFixture.configureByText("plain.sql", "SELECT nope FROM no_such_table")
    assertEmpty(myFixture.doHighlighting(HighlightSeverity.WARNING).filter { it.severity == HighlightSeverity.WARNING })
  }

  private fun reformat(text: String): String {
    myFixture.configureByText("f.sql", text)
    WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
    return myFixture.editor.document.text
  }

  private fun checkFormat(input: String, expected: String) {
    assertEquals(expected, reformat(input))
    assertEquals("not idempotent", expected, reformat(expected))
  }

  fun testFormatSelect() = checkFormat(
    "select a.id, b.name from guest a left join reservation b on b.guest_id=a.id where a.id in (select guest_id from reservation where status='X') and b.room_no>-1 group by a.id order by 1;",
    """
      select
          a.id,
          b.name
      from guest a
      left join reservation b
          on b.guest_id = a.id
      where a.id in (
          select guest_id
          from reservation
          where status = 'X'
      ) and b.room_no > -1
      group by a.id
      order by 1;
    """.trimIndent())

  fun testFormatInsertUpdateCte() = checkFormat(
    "insert into reservation(id,status) values(1,'A');\nupdate guest set name='a',email='b' where id=1;\n\nwith r as (select id from reservation) select count( * ) from r;",
    """
      insert into reservation (id, status)
      values (1, 'A');
      update guest
      set
          name = 'a',
          email = 'b'
      where id = 1;

      with r as (
          select id
          from reservation
      )
      select count(*)
      from r;
    """.trimIndent())

  fun testFormatKeepsCommentsStringsAndDdlLines() = checkFormat(
    "SELECT 'a  ,b' -- note\nFROM t;\ncreate table t (\n  id int,\n  name varchar (10)\n);",
    """
      SELECT 'a  ,b' -- note
      FROM t;
      create table t (
          id int,
          name varchar(10)
      );
    """.trimIndent())
}
