// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.codeInsight.generation.surroundWith.SurroundWithHandler
import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.application.options.CodeStyle
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking

/** Folding, parameter info, INSERT column hints, expanding `*` and paired quotes. */
class SqlEditorAssistsTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private var ds: DbDataSource? = null

  override fun tearDown() {
    try {
      ds?.let {
        DbConnectionService.getInstance(project).disconnect(it.id)
        DbDataSourceService.getInstance(project).remove(it.id)
      }
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  /** An H2 data source with `guest` and `reservation`, their columns loaded, attached to a new `q.sql` holding [text]. */
  private fun withSchema(text: String): SqlFile {
    val source = DbDataSource().apply {
      name = "assists"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:assists_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    ds = source
    DbDataSourceService.getInstance(project).save(source, CharArray(0))
    runBlocking {
      DbExecutor.execute(project, source, listOf(
        "CREATE TABLE guest (id BIGINT PRIMARY KEY, name VARCHAR(40))",
        "CREATE TABLE reservation (id BIGINT PRIMARY KEY, guest_id BIGINT, room_no INT)",
      ))
      val snapshot = SqlSchema.getInstance(project).load(source)!!
      for (t in listOf("guest", "reservation")) DbMetadataCache.getInstance(project).columns(source, snapshot.find(t, null).single())
    }
    val file = myFixture.configureByText("q.sql", text) as SqlFile
    DbConsoles.attach(project, file.virtualFile, source.id)
    return file
  }

  fun testFoldsMultiLineParenthesesAndComments() {
    val text = "/* a\n b */\nSELECT a, coalesce(b, c) FROM t\nWHERE a IN (\n  SELECT a FROM s\n);\nCREATE TABLE u (\n  id INT\n);"
    val file = myFixture.configureByText("a.sql", text)
    val folded = SqlFoldingBuilder().buildFoldRegions(file, myFixture.editor.document, false).map { it.range.substring(text) to it.placeholderText }
    assertSameElements(folded, "/* a\n b */" to "/*...*/", "(\n  SELECT a FROM s\n)" to "(...)", "(\n  id INT\n)" to "(...)")
  }

  private fun call(text: String): SqlSignatures.Call? {
    val file = myFixture.configureByText("a.sql", text)
    return SqlSignatures.callAt(file, myFixture.editor.caretModel.offset)
  }

  fun testParameterInfoFollowsTheArgument() {
    call("SELECT coalesce(a, <caret>")!!.let { assertEquals("COALESCE", it.signature.name); assertEquals(1, it.index) }
    call("SELECT round(coalesce(a, b), <caret>) FROM t")!!.let { assertEquals("ROUND", it.signature.name); assertEquals(1, it.index) }
    call("SELECT substring(<caret>x, 1)")!!.let { assertEquals(listOf("text", "start", "[length]"), it.signature.parameters); assertEquals(0, it.index) }
    assertNull(call("SELECT substring(x, 1) <caret>FROM t"))
    assertNull(call("SELECT a FROM t WHERE a IN (1, <caret>"))
    assertNull(call("SELECT s.count(<caret>"))

    myFixture.configureByText("a.sql", "SELECT date_format(d, <caret>")
    val context = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
    assertNotNull(SqlParameterInfoHandler().findElementForParameterInfo(context))
    assertEquals("DATE_FORMAT", (context.itemsToShow!!.single() as SqlSignature).name)
  }

  fun testQuotesArePaired() {
    myFixture.configureByText("a.sql", "SELECT <caret>")
    myFixture.type("'")
    assertEquals("SELECT ''", myFixture.editor.document.text)
    myFixture.type("abc'")
    assertEquals("SELECT 'abc'", myFixture.editor.document.text)
    myFixture.type(", `")
    assertEquals("SELECT 'abc', ``", myFixture.editor.document.text)
  }

  private fun hints(file: SqlFile): List<Pair<String, String>> =
    file.statements.flatMap { SqlInsertHints.hints(it) }.map { (offset, name) -> file.text.substring(offset).substringBefore(',').substringBefore(')') to name }

  fun testInsertValuesShowTheirColumns() {
    val file = myFixture.configureByText("a.sql", "INSERT INTO t (id, name, note) VALUES (1, 'a', note), (2, upper('b'), NULL)") as SqlFile
    assertEquals(listOf("1" to "id", "'a'" to "name", "2" to "id", "upper('b'" to "name", "NULL" to "note"), hints(file))
    assertEmpty(hints(myFixture.configureByText("b.sql", "INSERT INTO t VALUES (1, 2)") as SqlFile))
    assertEquals(listOf("1" to "ID", "2" to "GUEST_ID", "3" to "ROOM_NO"), hints(withSchema("INSERT INTO reservation VALUES (1, 2, 3)")))
  }

  private fun expand(text: String): String? {
    withSchema(text)
    val action = myFixture.filterAvailableIntentions(PhantomDbBundle.message("sql.intention.expand.star")).firstOrNull() ?: return null
    myFixture.launchAction(action)
    return myFixture.editor.document.text
  }

  fun testExpandStar() {
    assertEquals("SELECT ID, NAME FROM guest", expand("SELECT *<caret> FROM guest"))
  }

  fun testExpandStarOfAJoinQualifiesTheColumns() {
    assertEquals("SELECT g.ID, g.NAME, r.ID, r.GUEST_ID, r.ROOM_NO FROM guest g JOIN reservation r ON r.guest_id = g.id",
                 expand("SELECT <caret>* FROM guest g JOIN reservation r ON r.guest_id = g.id"))
  }

  fun testExpandQualifiedStar() {
    assertEquals("SELECT r.ID, r.GUEST_ID, r.ROOM_NO, g.name FROM guest g JOIN reservation r ON r.guest_id = g.id",
                 expand("SELECT r.*<caret>, g.name FROM guest g JOIN reservation r ON r.guest_id = g.id"))
  }

  fun testNoExpansionForCountOrMultiplication() {
    assertNull(expand("SELECT count(*<caret>) FROM guest"))
  }

  fun testNoExpansionInsideASubqueryDerivedTable() {
    assertNull(expand("SELECT *<caret> FROM (SELECT id FROM guest) d"))
    assertNotNull(PsiTreeUtil.findChildOfType(myFixture.file, SqlSubquery::class.java))
  }

  private fun selections(text: String, times: Int): List<String> {
    myFixture.configureByText("a.sql", text)
    return (1..times).map {
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET)
      myFixture.editor.selectionModel.selectedText.orEmpty()
    }.distinct()
  }

  fun testExtendSelectionGrowsByArgumentItemAndClause() {
    val steps = selections("SELECT id, coalesce(na<caret>me, 'x') AS n FROM guest WHERE id = 1;", 8)
    val expected = listOf("name", "name, 'x'", "(name, 'x')", "coalesce(name, 'x') AS n", "id, coalesce(name, 'x') AS n", "SELECT id, coalesce(name, 'x') AS n")
    assertTrue(steps.toString(), steps.containsAll(expected))
    assertEquals(steps.toString(), expected, steps.filter { it in expected })
    assertTrue(steps.toString(), selections("SELECT a FROM t WHERE a = 1 AND b<caret> = 2 ORDER BY a", 6).contains("WHERE a = 1 AND b = 2"))
  }

  fun testCodeStyleSetsKeywordCaseAndIndent() {
    myFixture.configureByText("a.sql", "select a.id, b.name from guest a where a.id = 1;")
    CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
      settings.getCustomSettings(SqlCodeStyleSettings::class.java).KEYWORD_CASE = SqlCodeStyleSettings.UPPER
      settings.getIndentOptions(SqlFileType.INSTANCE).INDENT_SIZE = 2
      WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
    }
    assertEquals("SELECT\n  a.id,\n  b.name\nFROM guest a\nWHERE a.id = 1;", myFixture.editor.document.text)
  }

  private fun surround(text: String, title: String): String {
    myFixture.configureByText("a.sql", text)
    val surrounder = SqlSurroundDescriptor().surrounders.single { it.templateDescription == title }
    SurroundWithHandler.invoke(project, myFixture.editor, myFixture.file, surrounder)
    val document = myFixture.editor.document.text
    val caret = myFixture.editor.caretModel.offset
    return document.substring(0, caret) + "|" + document.substring(caret)
  }

  fun testSurroundWith() {
    assertEquals("SELECT COALESCE(a, |) FROM t", surround("SELECT <selection>a</selection> FROM t", "COALESCE(expression, …)"))
    assertEquals("SELECT a FROM t WHERE NOT (a = 1 AND b = 2)|", surround("SELECT a FROM t WHERE <selection>a = 1 AND b = 2</selection>", "NOT (condition)"))
    assertEquals("SELECT CASE WHEN a > 1 THEN | END FROM t", surround("SELECT <selection>a > 1</selection> FROM t", "CASE WHEN condition THEN … END"))
  }

  private fun completeStatement(text: String): String {
    myFixture.configureByText("a.sql", text)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_COMPLETE_STATEMENT)
    val document = myFixture.editor.document.text
    val caret = myFixture.editor.caretModel.offset
    return document.substring(0, caret) + "|" + document.substring(caret)
  }

  fun testCompleteStatementClosesAndEnds() {
    assertEquals("SELECT a FROM t WHERE a IN (1, 2);\n|", completeStatement("SELECT a FROM t WHERE a IN (1, <caret>2"))
    assertEquals("SELECT coalesce(a, 'x');\n|", completeStatement("SELECT coalesce(a, 'x<caret>"))
    assertEquals("SELECT 1;\n|", completeStatement("SELECT <caret>1;"))
    assertEquals("SELECT (a);\n|\nSELECT 2;", completeStatement("SELECT (a<caret>;\n\nSELECT 2;"))
  }

  private fun usages(): List<String> {
    HighlightUsagesHandler.invoke(project, myFixture.editor, myFixture.file)
    val text = myFixture.editor.document.text
    return myFixture.editor.markupModel.allHighlighters.map { text.substring(it.startOffset, it.endOffset) }
  }

  fun testHighlightUsagesOfAnAlias() {
    myFixture.configureByText("a.sql", "SELECT r.id FROM reservation r WHERE r<caret>.room_no = 1; SELECT r.x FROM other r;")
    assertEquals(listOf("r", "r", "r"), usages())
  }

  fun testHighlightUsagesOfAColumn() {
    withSchema("SELECT room_no FROM reservation WHERE room<caret>_no > 1 ORDER BY room_no;")
    assertEquals(3, usages().count { it.equals("room_no", true) })
  }
}
