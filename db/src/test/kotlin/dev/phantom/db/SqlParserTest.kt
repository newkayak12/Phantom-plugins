// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.ParsingTestCase
import dev.phantom.db.core.*
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * PSI dump tests: `testData/parser/<dialect>/<Name>.sql` parsed with [dialect] on the file must give the tree in
 * `<Name>.txt`. A missing `.txt` is written by the first run, which then fails; check it before committing.
 */
abstract class SqlParsingTestCase(directory: String, private val dialect: SqlDialect) :
  ParsingTestCase("parser/$directory", "sql", SqlParserDefinition()) {
  override fun getTestDataPath(): String = File("testData").absolutePath

  override fun createFile(name: String, text: String): com.intellij.psi.PsiFile {
    val file = LightVirtualFile(name, myLanguage, text)
    file.charset = StandardCharsets.UTF_8
    file.putUserData(SqlDialects.KEY, dialect)
    return createFile(file)
  }

  protected fun doTest() = doTest(true, true)
}

class SqlAnyParserTest : SqlParsingTestCase("any", SqlDialects.ANY) {
  fun testSelectJoin() = doTest()
  fun testCteAndSubquery() = doTest()
  fun testIncompleteStatements() = doTest()
}

class SqlMySqlParserTest : SqlParsingTestCase("mysql", MySqlDialect) {
  fun testOnDuplicateKeyUpdate() = doTest()
}

class SqlMariaDbParserTest : SqlParsingTestCase("mariadb", MariaDbDialect) {
  fun testDeleteReturning() = doTest()
}

class SqlPostgresParserTest : SqlParsingTestCase("postgresql", PostgresDialect) {
  fun testCastAndOnConflict() = doTest()
}

class SqlH2ParserTest : SqlParsingTestCase("h2", H2Dialect) {
  fun testMergeKey() = doTest()
}
