// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/**
 * The body of a MySQL-style `CREATE TRIGGER` / `PROCEDURE` / `FUNCTION`. A `BEGIN ... END` body has `;` inside, so the
 * file splits it into several statements; the body is followed leaf by leaf from its BEGIN to the END that closes it.
 * PostgreSQL bodies are dollar-quoted strings (or `EXECUTE FUNCTION` for a trigger) and give no body here.
 */
class SqlRoutine(
  val kind: String,
  /** INSERT, UPDATE or DELETE for a trigger. */
  val event: String?,
  /** BEFORE or AFTER for a trigger. */
  val timing: String?,
  /** The significant leaves of the body, BEGIN to END (or the single statement of a trigger), strings and comments left out. */
  val body: List<PsiElement>,
  /** The closing END was found; checks about what is missing need it. */
  val complete: Boolean,
  /** `NO SQL`, `READS SQL DATA`, `MODIFIES SQL DATA` or `CONTAINS SQL` when the header declares one. */
  val dataAccess: String? = null,
) {
  companion object {
    private val KINDS = setOf("TRIGGER", "PROCEDURE", "FUNCTION")
    private val OTHER_OBJECTS = setOf("TABLE", "VIEW", "INDEX", "DATABASE", "SCHEMA", "SEQUENCE", "TYPE", "EXTENSION", "USER", "ROLE", "EVENT")
    /** `END IF`, `END LOOP`, ...: the end of a statement, not of a block. */
    private val END_OF_STATEMENT = setOf("IF", "LOOP", "WHILE", "REPEAT")
    private const val MAX_LEAVES = 50_000

    fun of(statement: SqlStatement): SqlRoutine? {
      if (statement.firstKeyword != "CREATE") return null
      val header = ArrayList<PsiElement>()
      var leaf: PsiElement? = significant(PsiTreeUtil.firstChild(statement))
      var kind: String? = null
      while (leaf != null && PsiTreeUtil.isAncestor(statement, leaf, false)) {
        val word = word(leaf)
        if (word in OTHER_OBJECTS && kind == null) return null
        if (kind == null && word in KINDS) kind = word
        if (kind != null && (word == "BEGIN" || (kind == "TRIGGER" && header.size > 2 && isForEachRow(header)))) break
        header.add(leaf)
        leaf = significant(PsiTreeUtil.nextLeaf(leaf))
      }
      if (kind == null || leaf == null || !PsiTreeUtil.isAncestor(statement, leaf, false)) return null
      var event: String? = null
      var timing: String? = null
      if (kind == "TRIGGER") {
        val words = header.map(::word)
        timing = words.firstOrNull { it == "BEFORE" || it == "AFTER" }
        event = words.firstOrNull { it == "INSERT" || it == "UPDATE" || it == "DELETE" }
        // FOLLOWS other / PRECEDES other
        if (word(leaf) == "FOLLOWS" || word(leaf) == "PRECEDES") leaf = significant(PsiTreeUtil.nextLeaf(leaf)).let { significant(PsiTreeUtil.nextLeaf(it ?: return null)) }
        if (leaf == null || word(leaf) == "EXECUTE") return null
        // `label: BEGIN`
        val next = significant(PsiTreeUtil.nextLeaf(leaf))
        if (word(leaf) != "BEGIN" && next?.text == ":" ) leaf = significant(PsiTreeUtil.nextLeaf(next))
        if (leaf == null) return null
        if (word(leaf) != "BEGIN") {
          // one statement: up to the end of this one
          val body = ArrayList<PsiElement>()
          var l: PsiElement? = leaf
          while (l != null && PsiTreeUtil.isAncestor(statement, l, false) && l.node.elementType != SqlTokens.SEMICOLON) {
            if (isCode(l)) body.add(l)
            l = significant(PsiTreeUtil.nextLeaf(l))
          }
          return SqlRoutine(kind, event, timing, body, complete = true)
        }
      }
      if (word(leaf) != "BEGIN") return null
      val access = dataAccess(header.map(::word))
      val body = ArrayList<PsiElement>()
      val blocks = ArrayList<String>()
      var l: PsiElement? = leaf
      while (l != null && body.size < MAX_LEAVES) {
        if (isCode(l)) body.add(l)
        when (word(l)) {
          "BEGIN" -> blocks.add("BEGIN")
          "CASE" -> blocks.add("CASE")
          "END" -> {
            val afterLeaf = significant(PsiTreeUtil.nextLeaf(l))
            val after = afterLeaf?.let(::word)
            // END closes the innermost BEGIN or CASE (`END CASE` included); END IF and the like close no block
            if (after !in END_OF_STATEMENT) {
              blocks.removeLastOrNull()
              if (blocks.isEmpty()) return SqlRoutine(kind, event, timing, body, complete = true, dataAccess = access)
            }
            // the word after END belongs to it: `END CASE` opens no CASE, `END IF` no IF
            if (after == "CASE" || after in END_OF_STATEMENT) {
              body.add(afterLeaf!!)
              l = afterLeaf
            }
          }
        }
        l = significant(PsiTreeUtil.nextLeaf(l))
      }
      return SqlRoutine(kind, event, timing, body, complete = false, dataAccess = access)
    }

    private fun dataAccess(words: List<String?>): String? {
      for (i in words.indices) {
        when {
          words[i] == "NO" && words.getOrNull(i + 1) == "SQL" -> return "NO SQL"
          words[i] in setOf("READS", "MODIFIES") && words.getOrNull(i + 1) == "SQL" && words.getOrNull(i + 2) == "DATA" -> return "${words[i]} SQL DATA"
          words[i] == "CONTAINS" && words.getOrNull(i + 1) == "SQL" -> return "CONTAINS SQL"
        }
      }
      return null
    }

    /** Words after which a new statement of a body starts. */
    val STATEMENT_START: Set<String> = setOf("BEGIN", "THEN", "ELSE", "DO", "LOOP", "REPEAT")

    /** Whether body[i] begins a statement: it follows `;`, a block opener, or a `label:`. */
    fun startsStatement(body: List<PsiElement>, i: Int): Boolean {
      val prev = body.getOrNull(i - 1) ?: return false
      if (prev.node.elementType == SqlTokens.SEMICOLON) return true
      if (word(prev) in STATEMENT_START) return true
      return prev.text == ":" && body.getOrNull(i - 2)?.let(::word) != null
    }

    private fun isForEachRow(header: List<PsiElement>): Boolean {
      val n = header.size
      return n >= 3 && word(header[n - 3]) == "FOR" && word(header[n - 2]) == "EACH" && word(header[n - 1]) == "ROW"
    }

    /** Uppercased text of a word leaf (keyword or unquoted name), else null. */
    fun word(leaf: PsiElement): String? {
      val type = leaf.node.elementType
      return if (type == SqlTokens.KEYWORD || type == SqlTokens.IDENTIFIER) leaf.text.uppercase() else null
    }

    /** Not a string or a comment: those may say anything. */
    private fun isCode(leaf: PsiElement): Boolean = leaf.node.elementType != SqlTokens.STRING

    fun significant(leaf: PsiElement?): PsiElement? {
      var e = leaf
      while (e != null && (e is PsiWhiteSpace || e is PsiComment || e.node.elementType in SqlTokens.COMMENTS || e.textLength == 0)) e = PsiTreeUtil.nextLeaf(e)
      return e
    }

    fun significantBackward(leaf: PsiElement?): PsiElement? {
      var e = leaf
      while (e != null && (e is PsiWhiteSpace || e is PsiComment || e.node.elementType in SqlTokens.COMMENTS || e.textLength == 0)) e = PsiTreeUtil.prevLeaf(e)
      return e
    }

    /** MySQL and MariaDB write bodies this way; another known dialect does not, so nothing is checked there. */
    fun dialectFits(holder: ProblemsHolder): Boolean {
      val dbms = SqlSchema.dataSourceFor(holder.file, strict = false)?.dialect() ?: return true
      return dbms == Dbms.MYSQL || dbms == Dbms.MARIADB
    }

    /** `OLD.x` / `NEW.x`: the leaf is OLD or NEW, a dot follows, and no dot precedes (`t.new.x` is something else). */
    fun rowReference(body: List<PsiElement>, i: Int): String? {
      val word = word(body[i]) ?: return null
      if (word != "OLD" && word != "NEW") return null
      if (body.getOrNull(i + 1)?.node?.elementType != SqlTokens.DOT) return null
      if (body.getOrNull(i - 1)?.node?.elementType == SqlTokens.DOT) return null
      return word
    }
  }
}

/** Base: runs once per CREATE TRIGGER / PROCEDURE / FUNCTION with a body this can follow. */
abstract class SqlRoutineInspection : SqlInspection() {
  override fun checkDdl(statement: SqlStatement, holder: ProblemsHolder) {
    if (!SqlRoutine.dialectFits(holder)) return
    val routine = SqlRoutine.of(statement) ?: return
    checkRoutine(routine, holder)
  }

  protected abstract fun checkRoutine(routine: SqlRoutine, holder: ProblemsHolder)
}

/**
 * Row references a trigger cannot have: OLD in an INSERT trigger, NEW in a DELETE trigger, an assignment to NEW in an
 * AFTER trigger, an assignment to OLD at all. MySQL rejects the trigger.
 */
class SqlTriggerTransitionInspection : SqlRoutineInspection() {
  override fun checkRoutine(routine: SqlRoutine, holder: ProblemsHolder) {
    if (routine.kind != "TRIGGER") return
    val body = routine.body
    for (i in body.indices) {
      val row = SqlRoutine.rowReference(body, i) ?: continue
      val assigned = body.getOrNull(i - 1)?.let(SqlRoutine::word) == "SET" && body.getOrNull(i + 3)?.text in setOf("=", ":=")
      val key = when {
        row == "OLD" && routine.event == "INSERT" -> "sql.inspection.trigger.no.old"
        row == "NEW" && routine.event == "DELETE" -> "sql.inspection.trigger.no.new"
        row == "OLD" && assigned -> "sql.inspection.trigger.old.read.only"
        row == "NEW" && assigned && routine.timing == "AFTER" -> "sql.inspection.trigger.new.after"
        else -> continue
      }
      val end = body.getOrNull(i + 2) ?: body[i]
      register(holder, body[i], end, PhantomDbBundle.message(key), ProblemHighlightType.GENERIC_ERROR)
    }
  }
}

/**
 * COMMIT, ROLLBACK or START TRANSACTION in a trigger or a stored function: MySQL refuses statements that end or begin
 * a transaction there (ROLLBACK TO SAVEPOINT is allowed). Procedures may use them.
 */
class SqlTransactionInTriggerInspection : SqlRoutineInspection() {
  override fun checkRoutine(routine: SqlRoutine, holder: ProblemsHolder) {
    if (routine.kind != "TRIGGER" && routine.kind != "FUNCTION") return
    val body = routine.body
    for ((i, leaf) in body.withIndex()) {
      val next = body.getOrNull(i + 1)?.let(SqlRoutine::word)
      val found = when (SqlRoutine.word(leaf)) {
        "COMMIT" -> true
        "ROLLBACK" -> next != "TO" && !(next == "WORK" && body.getOrNull(i + 2)?.let(SqlRoutine::word) == "TO")
        "START" -> next == "TRANSACTION"
        else -> false
      }
      if (!found || body.getOrNull(i - 1)?.node?.elementType == SqlTokens.DOT) continue
      val key = if (routine.kind == "TRIGGER") "sql.inspection.transaction.in.trigger" else "sql.inspection.transaction.in.function"
      holder.registerProblem(leaf, PhantomDbBundle.message(key, leaf.text.uppercase()), ProblemHighlightType.GENERIC_ERROR)
    }
  }
}

/** A local variable (`DECLARE x INT`) the body never mentions again. */
class SqlUnusedVariableInspection : SqlRoutineInspection() {
  override fun checkRoutine(routine: SqlRoutine, holder: ProblemsHolder) {
    if (!routine.complete) return
    val body = routine.body
    for ((i, leaf) in body.withIndex()) {
      if (SqlRoutine.word(leaf) != "DECLARE") continue
      // DECLARE a, b INT [DEFAULT ...]; not a cursor, condition or handler
      val names = ArrayList<PsiElement>()
      var j = i + 1
      while (j < body.size) {
        val name = body[j]
        if (name.node.elementType != SqlTokens.IDENTIFIER && name.node.elementType != SqlTokens.QUOTED_IDENTIFIER) break
        names.add(name)
        if (body.getOrNull(j + 1)?.node?.elementType != SqlTokens.COMMA) break
        j += 2
      }
      if (names.isEmpty()) continue
      val after = body.getOrNull(j + 1)?.let(SqlRoutine::word)
      if (after in setOf("CURSOR", "CONDITION", "HANDLER")) continue
      val statementEnd = (j until body.size).firstOrNull { body[it].node.elementType == SqlTokens.SEMICOLON } ?: continue
      for (name in names) {
        val n = SqlNames.unquote(name.text)
        val used = (statementEnd + 1 until body.size).any { k ->
          val t = body[k].node.elementType
          (t == SqlTokens.IDENTIFIER || t == SqlTokens.QUOTED_IDENTIFIER || t == SqlTokens.KEYWORD) && SqlNames.same(SqlNames.unquote(body[k].text), n) &&
          body.getOrNull(k - 1)?.node?.elementType != SqlTokens.DOT
        }
        if (!used) holder.registerProblem(name, PhantomDbBundle.message("sql.inspection.unused.variable", n), ProblemHighlightType.LIKE_UNUSED_SYMBOL)
      }
    }
  }
}

/** A stored function whose body has no RETURN: every call fails with "No RETURN found". */
class SqlMissingReturnInspection : SqlRoutineInspection() {
  override fun checkRoutine(routine: SqlRoutine, holder: ProblemsHolder) {
    if (routine.kind != "FUNCTION" || !routine.complete) return
    if (routine.body.any { SqlRoutine.word(it) == "RETURN" }) return
    val begin = routine.body.firstOrNull() ?: return
    holder.registerProblem(begin, PhantomDbBundle.message("sql.inspection.missing.return"))
  }
}

/**
 * Statements after RETURN, LEAVE or ITERATE in the same block: control never reaches them. The block goes on only at
 * its END, or at the next branch (ELSE, ELSEIF, WHEN) or UNTIL.
 */
class SqlUnreachableCodeInspection : SqlRoutineInspection() {
  override fun checkRoutine(routine: SqlRoutine, holder: ProblemsHolder) {
    if (!routine.complete) return
    val body = routine.body
    for (i in body.indices) {
      if (SqlRoutine.word(body[i]) !in JUMPS || !SqlRoutine.startsStatement(body, i)) continue
      val end = statementEnd(body, i) ?: continue
      val next = end + 1
      if (next >= body.size || SqlRoutine.word(body[next]) in CONTINUES) continue
      // a block statement (`IF ... END IF;`, `label: LOOP`) has `;` inside: only its first word is marked
      val block = SqlRoutine.word(body[next]) in BLOCKS || body.getOrNull(next + 1)?.text == ":"
      val last = if (block) next else (statementEnd(body, next) ?: continue) - 1
      if (last < next) continue
      register(holder, body[next], body[last], PhantomDbBundle.message("sql.inspection.unreachable", body[i].text.uppercase()),
               ProblemHighlightType.LIKE_UNUSED_SYMBOL)
    }
  }

  private companion object {
    val JUMPS = setOf("RETURN", "LEAVE", "ITERATE")
    val CONTINUES = setOf("END", "ELSE", "ELSEIF", "WHEN", "UNTIL")
    val BLOCKS = setOf("BEGIN", "IF", "LOOP", "WHILE", "REPEAT", "CASE")

    /** The `;` that ends the statement at [from], outside parentheses. */
    fun statementEnd(body: List<PsiElement>, from: Int): Int? {
      var depth = 0
      for (k in from until body.size) {
        when (body[k].node.elementType) {
          SqlTokens.LPAREN -> depth++
          SqlTokens.RPAREN -> depth--
          SqlTokens.SEMICOLON -> if (depth <= 0) return k
        }
      }
      return null
    }
  }
}

/** FETCH or CLOSE of a cursor that the body never OPENs: MySQL fails with "Cursor is not open". */
class SqlIllegalCursorStateInspection : SqlRoutineInspection() {
  override fun checkRoutine(routine: SqlRoutine, holder: ProblemsHolder) {
    if (!routine.complete) return
    val body = routine.body
    val declared = HashSet<String>()
    val opened = HashSet<String>()
    val used = ArrayList<Pair<String, PsiElement>>()
    fun name(k: Int): String? = body.getOrNull(k)?.takeIf { it.node.elementType == SqlTokens.IDENTIFIER || it.node.elementType == SqlTokens.QUOTED_IDENTIFIER }
      ?.let { SqlNames.unquote(it.text).lowercase() }
    for (i in body.indices) {
      when (SqlRoutine.word(body[i])) {
        "DECLARE" -> if (SqlRoutine.word(body.getOrNull(i + 2) ?: continue) == "CURSOR") name(i + 1)?.let(declared::add)
        "OPEN" -> if (SqlRoutine.startsStatement(body, i)) name(i + 1)?.let(opened::add)
        "CLOSE" -> if (SqlRoutine.startsStatement(body, i)) name(i + 1)?.let { used.add(it to body[i + 1]) }
        "FETCH" -> if (SqlRoutine.startsStatement(body, i)) {
          var k = i + 1
          if (SqlRoutine.word(body.getOrNull(k) ?: continue) == "NEXT") k++
          if (SqlRoutine.word(body.getOrNull(k) ?: continue) == "FROM") k++
          name(k)?.let { used.add(it to body[k]) }
        }
      }
    }
    for ((cursor, element) in used) {
      if (cursor in declared && cursor !in opened) {
        holder.registerProblem(element, PhantomDbBundle.message("sql.inspection.cursor.not.open", element.text))
      }
    }
  }
}

/**
 * A routine declared `NO SQL` that reads or writes tables, or `READS SQL DATA` that writes them. MySQL takes the
 * characteristic on trust; binary logging and the optimizer rely on it.
 */
class SqlSideEffectsInspection : SqlRoutineInspection() {
  override fun checkRoutine(routine: SqlRoutine, holder: ProblemsHolder) {
    val access = routine.dataAccess ?: return
    val forbidden = when (access) {
      "NO SQL" -> WRITES + READS
      "READS SQL DATA" -> WRITES
      else -> return
    }
    val body = routine.body
    for (i in body.indices) {
      val word = SqlRoutine.word(body[i]) ?: continue
      if (word !in forbidden || !SqlRoutine.startsStatement(body, i)) continue
      holder.registerProblem(body[i], PhantomDbBundle.message("sql.inspection.side.effects", word, access))
    }
  }

  private companion object {
    val WRITES = setOf("INSERT", "UPDATE", "DELETE", "REPLACE", "TRUNCATE")
    val READS = setOf("SELECT", "WITH")
  }
}
