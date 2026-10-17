// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.lexer.Lexer
import com.intellij.testFramework.LexerTestCase
import dev.phantom.db.core.SqlLexer

class SqlLexerTest : LexerTestCase() {
  override fun createLexer(): Lexer = SqlLexer()
  override fun getDirPath(): String = ""

  private val sample = """
    -- Flyway V1
    CREATE TABLE `reservation` (
      id BIGINT NOT NULL AUTO_INCREMENT, # MySQL comment
      note VARCHAR(255) DEFAULT 'it''s \'ok\'',
      PRIMARY KEY (id)
    );
    /* block /* nested */ still comment */
    SELECT r.id, "quoted;ident", [bracket id], 1.5e3, 0xFF, x'0A', :name, ?, @user_var, ${'$'}1
    FROM reservation r WHERE r.id::text <> '' AND note LIKE E'a\nb';
    CREATE FUNCTION f() RETURNS int AS ${'$'}body${'$'} SELECT 1; ${'$'}body${'$'} LANGUAGE sql;
    SELECT ${'$'}${'$'}a;b${'$'}${'$'};
    SELECT 'unclosed
  """.trimIndent()

  fun testRestartFromEveryToken() {
    checkCorrectRestart(sample)
  }

  fun testTokens() {
    doTest("SELECT `a;b`, 'x;y' FROM t -- c;\nWHERE a>=1;", """
      KEYWORD ('SELECT')
      WHITE_SPACE (' ')
      QUOTED_IDENTIFIER ('`a;b`')
      , (',')
      WHITE_SPACE (' ')
      STRING (''x;y'')
      WHITE_SPACE (' ')
      KEYWORD ('FROM')
      WHITE_SPACE (' ')
      IDENTIFIER ('t')
      WHITE_SPACE (' ')
      LINE_COMMENT ('-- c;')
      WHITE_SPACE ('\n')
      KEYWORD ('WHERE')
      WHITE_SPACE (' ')
      IDENTIFIER ('a')
      OPERATOR ('>=')
      NUMBER ('1')
      ; (';')
    """.trimIndent())
  }

  fun testDollarQuotesAndCasts() {
    doTest("${'$'}fn${'$'}a;b${'$'}fn${'$'} x::int ${'$'}2", """
      STRING ('${'$'}fn${'$'}a;b${'$'}fn${'$'}')
      WHITE_SPACE (' ')
      IDENTIFIER ('x')
      OPERATOR ('::')
      KEYWORD ('int')
      WHITE_SPACE (' ')
      PARAMETER ('${'$'}2')
    """.trimIndent())
  }
}
