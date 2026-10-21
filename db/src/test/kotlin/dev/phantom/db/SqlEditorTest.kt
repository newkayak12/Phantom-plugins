// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.CommandProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking

/** 0.14 editor intelligence: join completion, live templates, find usages and rename of tables and columns, unknown-name checks. */
class SqlEditorTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private lateinit var ds: DbDataSource

  override fun setUp() {
    super.setUp()
    ds = DbDataSource().apply {
      name = "shop"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:sql_editor_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    runBlocking {
      DbExecutor.execute(project, ds, listOf(
        "CREATE TABLE customer (id BIGINT PRIMARY KEY, name VARCHAR(40))",
        "CREATE TABLE orders (id BIGINT PRIMARY KEY, customer_id BIGINT REFERENCES customer(id), note VARCHAR(40))",
        "CREATE TABLE product (id BIGINT PRIMARY KEY, name VARCHAR(40))",
        "CREATE TABLE order_item (id BIGINT PRIMARY KEY, order_id BIGINT REFERENCES orders(id), product_id BIGINT)",
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

  private fun sql(text: String, name: String = "q.sql"): SqlFile {
    val file = myFixture.configureByText(name, text) as SqlFile
    DbConsoles.attach(project, file.virtualFile, ds.id)
    return file
  }

  private fun tables(): List<DbTableRef> = runBlocking { SqlSchema.getInstance(project).load(ds)!!.tables }

  /** The schema, every table's columns and, unless [keys] is false, its foreign keys both ways. */
  private fun preload(keys: Boolean = true) {
    runBlocking {
      for (t in tables()) {
        DbMetadataCache.getInstance(project).columns(ds, t)
        if (keys) {
          DbObjectCache.getInstance(project).foreignKeys(ds, t)
          DbObjectCache.getInstance(project).referencingKeys(ds, t)
        }
      }
    }
  }

  private fun complete(text: String): List<String> {
    sql(text)
    myFixture.completeBasic()
    return myFixture.lookupElementStrings.orEmpty()
  }

  fun testJoinOnConditionsFromForeignKeysBothWays() {
    preload()
    // the new table is referenced by the one in FROM
    val forward = complete("SELECT * FROM orders o JOIN customer c ON <caret>")
    assertEquals("o.CUSTOMER_ID = c.ID", forward.first())
    // the new table references the one in FROM; written with the table already in FROM first
    assertEquals("c.ID = o.CUSTOMER_ID", complete("SELECT * FROM customer c JOIN orders o ON <caret>").first())
    // no alias: the table name as written
    assertContainsElements(complete("SELECT * FROM orders JOIN customer ON <caret>"), "orders.CUSTOMER_ID = customer.ID")
  }

  fun testJoinOnConditionsForSeveralTablesKeysFirst() {
    preload()
    val items = complete("SELECT * FROM product p, customer c JOIN orders o ON o.customer_id = c.id JOIN order_item i ON <caret>")
    val fk = items.indexOf("o.ID = i.ORDER_ID")
    val guess = items.indexOf("p.ID = i.PRODUCT_ID")
    assertTrue(items.take(5).toString(), fk >= 0 && guess > fk)
    // customer has no link to order_item
    assertFalse(items.any { it.startsWith("c.") && it.contains("i.") })
  }

  fun testJoinTablesWithTheirOnClause() {
    preload()
    val items = complete("SELECT * FROM orders o JOIN <caret>")
    assertContainsElements(items, "CUSTOMER c ON o.CUSTOMER_ID = c.ID", "ORDER_ITEM oi ON o.ID = oi.ORDER_ID")
    assertTrue(items.take(2).toString(), " ON " in items.first())
    // aliases already taken get a number; lower-case JOIN gets lower-case ON
    assertContainsElements(complete("select * from orders c join <caret>"), "CUSTOMER c1 on c.CUSTOMER_ID = c1.ID")
  }

  /** Completion never waits for the keys: the first time they load in the background, the next completion has them. */
  fun testJoinCompletionDoesNotWaitForKeys() {
    preload(keys = false)
    sql("SELECT * FROM orders o JOIN customer c ON <caret>")
    myFixture.completeBasic()
    fun typeOf(text: String) = myFixture.lookupElements.orEmpty().firstOrNull { it.lookupString == text }
      ?.let { LookupElementPresentation.renderElement(it).typeText }
    // the name guess is there at once
    assertEquals(PhantomDbBundle.message("sql.completion.join.guess"), typeOf("o.CUSTOMER_ID = c.ID"))
    LookupManager.hideActiveLookup(project)
    val customer = tables().single { it.name == "CUSTOMER" }
    val deadline = System.currentTimeMillis() + 10_000
    while (SqlSchema.getInstance(project).foreignKeys(ds, customer) == null && System.currentTimeMillis() < deadline) {
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      Thread.sleep(20)
    }
    myFixture.completeBasic()
    assertEquals(PhantomDbBundle.message("sql.completion.join.fk"), typeOf("o.CUSTOMER_ID = c.ID"))
  }

  private fun command(action: () -> Unit) = CommandProcessor.getInstance().executeCommand(project, action, null, null)

  private fun expand() = command { myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB) }

  fun testLiveTemplates() {
    preload()
    TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    sql("sel<caret>")
    expand()
    assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("SELECT * FROM "))
    command { TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false) }

    sql("ins<caret>")
    expand()
    LookupManager.hideActiveLookup(project)
    myFixture.type("customer")
    LookupManager.hideActiveLookup(project)
    command { TemplateManagerImpl.getTemplateState(myFixture.editor)!!.nextTab() }
    assertEquals("INSERT INTO customer (ID, NAME) VALUES ()", myFixture.editor.document.text)
    command { TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false) }
  }

  private fun targetAt(file: SqlFile, text: String, occurrence: Int = 0): SqlDbTarget {
    var offset = -1
    repeat(occurrence + 1) { offset = file.text.indexOf(text, offset + 1) }
    return file.findReferenceAt(offset)!!.resolve() as SqlDbTarget
  }

  fun testFindUsagesAndRenameOfATableAcrossSqlFiles() {
    preload()
    val other = myFixture.addFileToProject("other.sql", "UPDATE customer SET name = 'x';\nSELECT \"CUSTOMER\".id FROM \"CUSTOMER\"")
    DbConsoles.attach(project, other.virtualFile, ds.id)
    val file = sql("SELECT c.name FROM customer c JOIN orders o ON o.customer_id = c.id")
    val target = targetAt(file, "customer c")
    assertEquals(4, myFixture.findUsages(target).size)
    myFixture.renameElement(target, "client")
    assertEquals("SELECT c.name FROM client c JOIN orders o ON o.customer_id = c.id", file.text)
    // quoted names keep their quotes and take the case H2 stores the unquoted name in; others keep their own case
    assertEquals("UPDATE client SET name = 'x';\nSELECT \"CLIENT\".id FROM \"CLIENT\"", other.text)
  }

  fun testRenameOfAColumnLeavesOtherTablesColumnsAlone() {
    preload()
    val other = myFixture.addFileToProject("other.sql", "SELECT name FROM product;\nUPDATE customer SET name = 'x'")
    DbConsoles.attach(project, other.virtualFile, ds.id)
    val file = sql("SELECT c.name, p.name FROM customer c, product p")
    val target = targetAt(file, "name")
    assertEquals("CUSTOMER", target.table.name)
    assertEquals(2, myFixture.findUsages(target).size)
    myFixture.renameElement(target, "full_name")
    assertEquals("SELECT c.full_name, p.name FROM customer c, product p", file.text)
    assertEquals("SELECT name FROM product;\nUPDATE customer SET full_name = 'x'", other.text)
  }

  fun testAlterStatementForRename() {
    preload()
    val file = sql("SELECT name FROM customer")
    assertEquals("ALTER TABLE PUBLIC.CUSTOMER RENAME TO client", SqlRename.alterStatement(targetAt(file, "customer"), "client"))
    assertEquals("ALTER TABLE PUBLIC.CUSTOMER ALTER COLUMN NAME RENAME TO full_name", SqlRename.alterStatement(targetAt(file, "name"), "full_name"))
  }

  fun testQuotedNamesTakeTheStoredCase() {
    assertEquals("\"CLIENT\"", SqlRename.nameText("\"CUSTOMER\"", "client", Dbms.H2))
    assertEquals("\"client\"", SqlRename.nameText("\"Customer\"", "Client", Dbms.POSTGRESQL))
    assertEquals("`Client`", SqlRename.nameText("`customer`", "Client", Dbms.MYSQL))
    assertEquals("\"my \"\"x\"\"\"", SqlRename.nameText("\"a\"", "my \"x\"", Dbms.H2))
    assertEquals("CLIENT", SqlRename.nameText("CUSTOMER", "client", Dbms.H2))
  }

  /** The ALTER goes into the console the rename started from, or the data source's first console. */
  fun testAlterIsWrittenToTheConsole() {
    preload()
    val first = DbConsoles.consoleFile(ds)!!
    val second = DbConsoles.newConsoleFile(ds)!!
    com.intellij.openapi.application.WriteAction.run<Throwable> { com.intellij.openapi.vfs.VfsUtil.saveText(second, "SELECT name FROM customer") }
    val console = com.intellij.psi.PsiManager.getInstance(project).findFile(second) as SqlFile
    val target = targetAt(console, "customer")
    SqlDbRenameOptions.set(target, SqlDbRenameOptions(generateAlter = true))
    myFixture.renameElement(target, "client")
    val documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
    assertEquals("SELECT name FROM client\nALTER TABLE PUBLIC.CUSTOMER RENAME TO client;\n", documents.getDocument(second)!!.text)
    assertEquals("", documents.getDocument(first)!!.text)

    // from a .sql file: the first console
    val file = sql("SELECT name FROM customer")
    val column = targetAt(file, "name")
    SqlDbRenameOptions.set(column, SqlDbRenameOptions(generateAlter = true))
    myFixture.renameElement(column, "full_name")
    assertEquals("ALTER TABLE PUBLIC.CUSTOMER ALTER COLUMN NAME RENAME TO full_name;\n", documents.getDocument(first)!!.text)
  }

  fun testAlterForGenericDataSourcesSaysToCheck() {
    val generic = DbDataSource().apply { name = "g"; dbms = Dbms.GENERIC; url = "jdbc:x:y" }
    val file = sql("SELECT 1")
    val target = SqlDbTarget(file, generic, DbTableRef(generic.id, null, "S", "T", "TABLE"), null)
    assertEquals("-- check for your database\nALTER TABLE S.T RENAME TO u", SqlRename.alterStatement(target, "u"))
  }

  fun testJoinOnConditionsAfterAndButNotForCrossJoins() {
    preload()
    assertContainsElements(complete("SELECT * FROM orders o JOIN customer c ON c.name = 'x' AND <caret>"), "o.CUSTOMER_ID = c.ID")
    assertDoesntContain(complete("SELECT * FROM orders o NATURAL JOIN customer c ON <caret>"), "o.CUSTOMER_ID = c.ID")
    assertDoesntContain(complete("SELECT * FROM orders o CROSS JOIN customer c ON <caret>"), "o.CUSTOMER_ID = c.ID")
  }

  private fun warnings() = myFixture.doHighlighting(HighlightSeverity.WARNING).filter { it.severity == HighlightSeverity.WARNING }.map { it.description }

  fun testUnresolvedNamesOnlyWithLoadedMetadataAndTheirFixes() {
    myFixture.enableInspections(SqlUnresolvedTableInspection::class.java, SqlUnresolvedColumnInspection::class.java)
    // a data source whose schema cannot be loaded: no warnings
    val down = DbDataSource().apply { name = "down"; dbms = Dbms.H2; url = "jdbc:h2:tcp://localhost:1/nothing"; user = "sa" }
    DbDataSourceService.getInstance(project).save(down, CharArray(0))
    try {
      val file = myFixture.configureByText("down.sql", "SELECT g.name, nope FROM no_such_table")
      DbConsoles.attach(project, file.virtualFile, down.id)
      assertEmpty(warnings())
    }
    finally {
      DbDataSourceService.getInstance(project).remove(down.id)
    }

    preload()
    sql("SELECT <caret>g.name FROM customer")
    assertSameElements(warnings(), "Unknown table or alias 'g'")
    myFixture.launchAction(myFixture.findSingleIntention("Add alias 'g' to customer"))
    assertEquals("SELECT g.name FROM customer g", myFixture.editor.document.text)

    // the alias hides the table name: other qualifiers that used it change too
    sql("SELECT <caret>g.name, customer.id FROM customer WHERE customer.id > 0")
    myFixture.launchAction(myFixture.findSingleIntention("Add alias 'g' to customer"))
    assertEquals("SELECT g.name, g.id FROM customer g WHERE g.id > 0", myFixture.editor.document.text)

    sql("SELECT o.<caret>name FROM orders o JOIN customer c ON c.id = o.customer_id")
    assertSameElements(warnings(), "Unknown column 'name'")
    myFixture.launchAction(myFixture.findSingleIntention("Qualify with 'c'"))
    assertEquals("SELECT c.name FROM orders o JOIN customer c ON c.id = o.customer_id", myFixture.editor.document.text)

    // the alias as written, quotes and all
    sql("SELECT o.<caret>name FROM orders o JOIN customer \"C\" ON \"C\".id = o.customer_id")
    myFixture.launchAction(myFixture.findSingleIntention("Qualify with '\"C\"'"))
    assertEquals("SELECT \"C\".name FROM orders o JOIN customer \"C\" ON \"C\".id = o.customer_id", myFixture.editor.document.text)
  }

  fun testUnresolvedNamesInAnUnlistedSchemaAreLeftAlone() {
    myFixture.enableInspections(SqlUnresolvedTableInspection::class.java, SqlUnresolvedColumnInspection::class.java)
    preload()
    sql("SELECT x.y FROM elsewhere.thing x")
    assertEmpty(warnings())
  }
}
