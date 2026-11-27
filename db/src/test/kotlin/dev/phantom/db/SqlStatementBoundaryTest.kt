// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*

/** Where statements end: `;`, a missing `;` and MySQL client `DELIMITER` commands, for the run actions and the editor (#164). */
class SqlStatementBoundaryTest : BasePlatformTestCase() {
  fun testSemicolonsInsideStringsCommentsAndDollarQuotesEndNothing() {
    val script = """
      SELECT 'a;b', "c;d", `e;f` FROM t; -- g;h
      /* i; j */ SELECT 2;
      CREATE FUNCTION f() RETURNS int AS ${'$'}${'$'}BEGIN RETURN 1; END;${'$'}${'$'} LANGUAGE plpgsql;
      SELECT ${'$'}tag${'$'} x; ${'$'}tag${'$'};
    """.trimIndent()
    assertEquals(
      listOf("SELECT 'a;b', \"c;d\", `e;f` FROM t", "SELECT 2",
             "CREATE FUNCTION f() RETURNS int AS ${'$'}${'$'}BEGIN RETURN 1; END;${'$'}${'$'} LANGUAGE plpgsql", "SELECT ${'$'}tag${'$'} x; ${'$'}tag${'$'}"),
      SqlStatements.split(script))
  }

  fun testMissingSemicolonEndsStatement() {
    val script = "SELECT * FROM a\nSELECT * FROM b UNION SELECT * FROM c\nSELECT (SELECT 1) FROM d WHERE x IN (SELECT y FROM e)\nINSERT INTO f VALUES (1)"
    assertEquals(listOf("SELECT * FROM a", "SELECT * FROM b UNION SELECT * FROM c", "SELECT (SELECT 1) FROM d WHERE x IN (SELECT y FROM e)",
                        "INSERT INTO f VALUES (1)"), SqlStatements.split(script))
    // only a SELECT runs into a following statement; an INSERT ... SELECT is one statement
    assertEquals(listOf("INSERT INTO f SELECT * FROM a"), SqlStatements.split("INSERT INTO f SELECT * FROM a"))
  }

  fun testMissingSemicolonIsReportedOnTheNextStatement() {
    myFixture.configureByText("missing.sql", "SELECT * FROM a\n<error descr=\"A new statement starts here without a ; before it\"><caret>SELECT</error> * FROM b;\n")
    myFixture.checkHighlighting(false, false, false)
    myFixture.launchAction(myFixture.findSingleIntention("Add ;"))
    assertEquals("SELECT * FROM a;\nSELECT * FROM b;\n", myFixture.editor.document.text)
  }

  fun testDelimiterBlocksKeepTheirSemicolons() {
    val script = """
      DELIMITER ${'$'}${'$'}
      CREATE PROCEDURE p()
      BEGIN
        DECLARE n INT DEFAULT 0;
        SELECT n;
      END${'$'}${'$'}
      CREATE TRIGGER t BEFORE INSERT ON x FOR EACH ROW BEGIN SET NEW.a = 1; END ${'$'}${'$'}
      DELIMITER //
      CREATE FUNCTION f() RETURNS INT BEGIN RETURN 1; END //
      DELIMITER ;
      SELECT 1; SELECT 2;
    """.trimIndent()
    assertEquals(listOf("CREATE PROCEDURE p()\nBEGIN\n  DECLARE n INT DEFAULT 0;\n  SELECT n;\nEND",
                        "CREATE TRIGGER t BEFORE INSERT ON x FOR EACH ROW BEGIN SET NEW.a = 1; END",
                        "CREATE FUNCTION f() RETURNS INT BEGIN RETURN 1; END", "SELECT 1", "SELECT 2"),
                 SqlStatements.split(script))
  }

  fun testRunAtCaretInsideDelimiterBlock() {
    val file = myFixture.configureByText("routines.sql", "DELIMITER //\nCREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END //\nDELIMITER ;\nSELECT 3;\n") as SqlFile
    val text = file.text
    assertEquals("CREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END", file.executableAt(text.indexOf("SELECT 2")))
    assertNull("a DELIMITER command is not sent to the server", file.executableAt(text.indexOf("DELIMITER ;") + 3))
    assertEquals("SELECT 3", file.executableAt(text.indexOf("SELECT 3")))
    myFixture.checkHighlighting(false, false, false)
  }

  fun testDelimiterIsOnlyACommandFirstOnItsLine() {
    assertEquals(listOf("SELECT delimiter FROM t", "SELECT 1"), SqlStatements.split("SELECT delimiter FROM t;\nSELECT 1;"))
    assertEquals(listOf("SELECT 1 DELIMITER //"), SqlStatements.split("SELECT 1 DELIMITER //;"))
  }

  fun testLexerRestartsInsideDelimiterBlock() {
    val text = "DELIMITER ${'$'}${'$'}\nSELECT 1; END${'$'}${'$'}"
    val lexer = SqlLexer()
    lexer.start(text)
    val tokens = ArrayList<Pair<String, Int>>()
    while (lexer.tokenType != null) {
      tokens += lexer.tokenType.toString() to lexer.state
      lexer.advance()
    }
    assertEquals(listOf("DELIMITER_COMMAND", "WHITE_SPACE", "KEYWORD", "WHITE_SPACE", "NUMBER", ";", "WHITE_SPACE", "KEYWORD", "DELIMITER"),
                 tokens.map { it.first })
    // restarting at `END` with the state recorded there reads the delimiter again
    val restart = SqlLexer()
    restart.start(text, text.indexOf("END"), text.length, tokens[7].second)
    assertEquals("KEYWORD", restart.tokenType.toString())
    restart.advance()
    assertEquals("DELIMITER", restart.tokenType.toString())
  }

  fun testTypingReparsesOnlyThatStatement() {
    val file = myFixture.configureByText("two.sql", "SELECT a FROM t;\nSELECT b FROM u;\n") as SqlFile
    val first = file.statements[0]
    WriteCommandAction.runWriteCommandAction(project) {
      myFixture.editor.document.insertString(file.text.indexOf("b FROM"), "c, ")
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertSame(first, file.statements[0])
    assertEquals("SELECT c, b FROM u", file.statements[1].executableText)
    PsiTestUtil.checkFileStructure(file)
  }

  fun testEditsAroundDelimitersKeepTheTreeConsistent() {
    val file = myFixture.configureByText("d.sql", "DELIMITER //\nSELECT 1; SELECT 2 //\nDELIMITER ;\nSELECT 3;\n") as SqlFile
    WriteCommandAction.runWriteCommandAction(project) {
      val at = file.text.indexOf("//")
      myFixture.editor.document.replaceString(at, at + 2, "${'$'}${'$'}")
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    PsiTestUtil.checkFileStructure(file)
    // `//` is no delimiter now; the `DELIMITER ;` line still ends the statement, as in the mysql client
    assertEquals(listOf("", "SELECT 1; SELECT 2 //", "", "SELECT 3"), file.statements.map { it.executableText })
  }
}
