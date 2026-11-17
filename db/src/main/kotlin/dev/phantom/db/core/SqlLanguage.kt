// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.DynamicBundle
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.*
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import javax.swing.Icon

@NonNls
private const val BUNDLE = "messages.PhantomDbBundle"

object PhantomDbBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

/** Language ID `SQL`, so `@Language("SQL")` injections can target it later. */
object SqlLanguage : Language("SQL") {
  private fun readResolve(): Any = SqlLanguage
  override fun getDisplayName(): String = "SQL"
  override fun isCaseSensitive(): Boolean = false
}

class SqlFileType private constructor() : LanguageFileType(SqlLanguage) {
  override fun getName(): String = "Phantom SQL"
  override fun getDisplayName(): String = "SQL"
  override fun getDescription(): String = PhantomDbBundle.message("db.file.type.description")
  override fun getDefaultExtension(): String = "sql"
  override fun getIcon(): Icon = AllIcons.Nodes.DataTables

  companion object {
    @JvmField
    val INSTANCE: SqlFileType = SqlFileType()
  }
}

class SqlTokenType(@NonNls debugName: String) : IElementType(debugName, SqlLanguage)

object SqlTokens {
  @JvmField val KEYWORD: IElementType = SqlTokenType("KEYWORD")
  @JvmField val IDENTIFIER: IElementType = SqlTokenType("IDENTIFIER")
  /** `` `name` ``, `"name"`, `[name]`. */
  @JvmField val QUOTED_IDENTIFIER: IElementType = SqlTokenType("QUOTED_IDENTIFIER")
  /** `'text'`, `E'text'`, `N'text'`, `$$text$$`, `$tag$text$tag$`. */
  @JvmField val STRING: IElementType = SqlTokenType("STRING")
  @JvmField val NUMBER: IElementType = SqlTokenType("NUMBER")
  /** `-- text`, `# text`. */
  @JvmField val LINE_COMMENT: IElementType = SqlTokenType("LINE_COMMENT")
  @JvmField val BLOCK_COMMENT: IElementType = SqlTokenType("BLOCK_COMMENT")
  /** `?`, `:name`, `@var`, `@@var`. */
  @JvmField val PARAMETER: IElementType = SqlTokenType("PARAMETER")
  @JvmField val OPERATOR: IElementType = SqlTokenType("OPERATOR")
  @JvmField val SEMICOLON: IElementType = SqlTokenType(";")
  @JvmField val COMMA: IElementType = SqlTokenType(",")
  @JvmField val DOT: IElementType = SqlTokenType(".")
  @JvmField val LPAREN: IElementType = SqlTokenType("(")
  @JvmField val RPAREN: IElementType = SqlTokenType(")")

  @JvmField val COMMENTS: TokenSet = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)
  @JvmField val STRINGS: TokenSet = TokenSet.create(STRING)
  @JvmField val NAMES: TokenSet = TokenSet.create(IDENTIFIER, QUOTED_IDENTIFIER)

  /**
   * Reserved and common non-reserved words of SQL:2016, MySQL 8, PostgreSQL 16 and H2 2. One set for all dialects:
   * a word that is a keyword in one dialect and a name in another is rare, and only its color differs.
   */
  val KEYWORDS: Set<String> = """
    ADD AFTER ALL ALTER ANALYZE AND ANY ARRAY AS ASC AUTO_INCREMENT BEFORE BEGIN BETWEEN BIGINT BINARY BLOB BOOLEAN BOTH BY
    CALL CASCADE CASE CAST CHANGE CHAR CHARACTER CHARSET CHECK COLLATE COLUMN COLUMNS COMMENT COMMIT CONSTRAINT CONVERT CREATE
    CROSS CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER CURSOR DATABASE DATABASES DATE DATETIME DAY DEALLOCATE
    DEC DECIMAL DECLARE DEFAULT DEFERRABLE DELETE DELIMITER DESC DESCRIBE DISTINCT DO DOUBLE DROP DUPLICATE EACH ELSE ELSEIF
    END ENGINE ENUM ESCAPE EXCEPT EXECUTE EXISTS EXPLAIN EXTENSION FALSE FETCH FIRST FLOAT FOR FOREIGN FROM FULL FUNCTION
    GENERATED GRANT GROUP HAVING IDENTITY IF IGNORE ILIKE IN INDEX INNER INSERT INT INTEGER INTERSECT INTERVAL INTO IS JOIN
    JSON JSONB KEY KEYS LANGUAGE LAST LATERAL LEADING LEFT LIKE LIMIT LOCK LONGTEXT MATERIALIZED MEDIUMTEXT MERGE MINUS
    MODIFY NATURAL NO NOT NOTHING NULL NULLS NUMERIC OF OFFSET ON ONLY OR ORDER OUTER OVER OWNER PARTITION PRECISION PRIMARY
    PROCEDURE RANGE REAL RECURSIVE REFERENCES RELEASE RENAME REPLACE RESTRICT RETURN RETURNING RETURNS REVOKE RIGHT ROLLBACK
    ROW ROWS SAVEPOINT SCHEMA SCHEMAS SELECT SEQUENCE SERIAL SET SHOW SIGNED SMALLINT SOME START TABLE TABLES TEMPORARY
    TEXT THEN TIME TIMESTAMP TINYINT TO TRAILING TRANSACTION TRIGGER TRUE TRUNCATE TYPE UNION UNIQUE UNSIGNED UPDATE USE
    USING UUID VALUES VARBINARY VARCHAR VIEW WHEN WHERE WHILE WINDOW WITH WITHOUT ZONE
  """.trim().split(Regex("\\s+")).toSet()

  fun isKeyword(word: CharSequence): Boolean = word.length in 2..17 && word.toString().uppercase() in KEYWORDS
}

object SqlElements {
  @JvmField val FILE: IFileElementType = IFileElementType(SqlLanguage)
  @JvmField val STATEMENT: IElementType = IElementType("STATEMENT", SqlLanguage)
  @JvmField val SUBQUERY: IElementType = IElementType("SUBQUERY", SqlLanguage)
  @JvmField val TABLE_REF: IElementType = IElementType("TABLE_REF", SqlLanguage)
  @JvmField val COLUMN_REF: IElementType = IElementType("COLUMN_REF", SqlLanguage)
  @JvmField val INSERT_COLUMN: IElementType = IElementType("INSERT_COLUMN", SqlLanguage)
  @JvmField val QUALIFIER: IElementType = IElementType("QUALIFIER", SqlLanguage)
  @JvmField val ALIAS: IElementType = IElementType("ALIAS", SqlLanguage)
  @JvmField val CTE_NAME: IElementType = IElementType("CTE_NAME", SqlLanguage)
  @JvmField val COLUMN_ALIAS: IElementType = IElementType("COLUMN_ALIAS", SqlLanguage)
}

class SqlFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, SqlLanguage) {
  override fun getFileType(): FileType = SqlFileType.INSTANCE
  override fun toString(): String = "SQL file"

  val statements: List<SqlStatement> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, SqlStatement::class.java)

  /**
   * The statement to run for a caret at [offset]: the one around it, else the one ending earlier on the same line
   * (caret after `;`), else the one starting later on the same line.
   */
  fun statementAt(offset: Int): SqlStatement? {
    val all = statements
    all.firstOrNull { offset >= it.textRange.startOffset && offset < it.textRange.endOffset }?.let { return it }
    val text = viewProvider.contents
    fun sameLine(from: Int, to: Int) = (from until to).none { text[it] == '\n' }
    all.lastOrNull { it.textRange.endOffset <= offset }?.takeIf { sameLine(it.textRange.endOffset, offset) }?.let { return it }
    return all.firstOrNull { it.textRange.startOffset >= offset }?.takeIf { sameLine(offset, it.textRange.startOffset) }
  }
  /** The SQL to run for a caret at [offset]: [statementAt], cut where a `;` is missing before the next statement. */
  fun executableAt(offset: Int): String? = statementAt(offset)?.let { SqlStatements.part(it, offset) }
}

/** One statement, up to and including its `;`. Below it: tokens, names ([SqlTableRef], [SqlColumnRef], ...) and subqueries. */
class SqlStatement(node: ASTNode) : ASTWrapperPsiElement(node), SqlScope {
  /** Text to send to the driver: without the trailing `;` and surrounding blanks. */
  val executableText: String
    get() {
      var end = node.lastChildNode
      while (end != null && (end.elementType == SqlTokens.SEMICOLON || end.elementType == TokenType.WHITE_SPACE ||
                             end.elementType in SqlTokens.COMMENTS)) end = end.treePrev
      if (end == null) return ""
      return text.substring(0, end.startOffsetInParent + end.textLength).trim()
    }

  /** Uppercased first keyword, e.g. `SELECT`; `null` when the statement starts with something else. */
  val firstKeyword: String? get() = SqlStatements.firstKeyword(node.firstChildNode)

  val executableRange: TextRange get() = TextRange.from(textRange.startOffset, executableText.length)
}

object SqlStatements {
  internal fun firstKeyword(first: ASTNode?): String? {
    // `(SELECT ...)` starts with a subquery element: look at the leaves
    val end = first?.treeParent
    var n = first?.let { com.intellij.psi.impl.source.tree.TreeUtil.findFirstLeaf(it) }
    while (n != null && (n.elementType == TokenType.WHITE_SPACE || n.elementType in SqlTokens.COMMENTS || n.elementType == SqlTokens.LPAREN)) {
      n = com.intellij.psi.impl.source.tree.TreeUtil.nextLeaf(n)?.takeIf { end == null || it.startOffset < end.startOffset + end.textLength }
    }
    return n?.takeIf { it.elementType == SqlTokens.KEYWORD }?.text?.uppercase()
  }

  /**
   * The part of [statement] around [offset] (in the file): a statement that runs on into the next one for a missing
   * `;` (`SELECT * FROM a` and `SELECT * FROM b` on the next line) is cut where the `;` should be.
   */
  fun part(statement: SqlStatement, offset: Int): String? {
    val text = statement.executableText
    if (text.isEmpty()) return null
    val start = statement.textRange.startOffset + statement.text.indexOf(text)
    val cuts = SqlSyntaxCheck.check(text).mapNotNull { p -> p.fix?.takeIf { it.text == ";" && it.start == it.end }?.start }.distinct().sorted()
    var from = 0
    for (cut in cuts) {
      if (offset - start <= cut) return text.substring(from, cut).trim().ifEmpty { null }
      from = cut
    }
    return text.substring(from).trim().ifEmpty { null }
  }

  /** Splits plain SQL text into statements with the same lexer as the editor. */
  fun split(text: CharSequence): List<String> {
    val lexer = SqlLexer()
    lexer.start(text)
    val result = ArrayList<String>()
    var start = -1
    var lastSignificant = -1
    while (lexer.tokenType != null) {
      val type = lexer.tokenType
      if (type == SqlTokens.SEMICOLON) {
        if (start >= 0) result.add(text.substring(start, lastSignificant))
        start = -1
      }
      else if (type != TokenType.WHITE_SPACE && type !in SqlTokens.COMMENTS) {
        if (start < 0) start = lexer.tokenStart
        lastSignificant = lexer.tokenEnd
      }
      lexer.advance()
    }
    if (start >= 0) result.add(text.substring(start, lastSignificant))
    return result
  }
}

class SqlParserDefinition : ParserDefinition {
  override fun createLexer(project: Project?): Lexer = SqlLexer()
  override fun createParser(project: Project?): PsiParser = SqlParser()
  override fun getFileNodeType(): IFileElementType = SqlElements.FILE
  override fun getCommentTokens(): TokenSet = SqlTokens.COMMENTS
  override fun getStringLiteralElements(): TokenSet = SqlTokens.STRINGS
  override fun createElement(node: ASTNode): PsiElement = elementFor(node) ?: SqlStatement(node)
  override fun createFile(viewProvider: FileViewProvider): PsiFile = SqlFile(viewProvider)
}
