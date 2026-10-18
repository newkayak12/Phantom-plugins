// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core.script

/**
 * The part of JavaScript that response handler scripts use: variables, functions and arrows, objects and arrays,
 * template strings, `if`/`for`/`while`/`try`. No classes, generators, regex literals, spread or destructuring.
 */
class JsSyntaxError(message: String, val line: Int) : Exception("$message (line $line)")

internal enum class T { NUM, STR, TEMPLATE, IDENT, PUNCT, EOF }

internal class Tok(val type: T, val text: String, val line: Int, val newlineBefore: Boolean,
                   val num: Double = 0.0, val parts: List<String> = emptyList(), val exprs: List<String> = emptyList())

internal class JsLexer(private val src: String, private val firstLine: Int = 1) {
  private var i = 0
  private var line = firstLine

  fun tokens(): List<Tok> {
    val out = ArrayList<Tok>()
    while (true) {
      val nl = skipSpace()
      if (i >= src.length) {
        out.add(Tok(T.EOF, "", line, true)); return out
      }
      out.add(next(nl))
    }
  }

  private fun skipSpace(): Boolean {
    var nl = false
    while (i < src.length) {
      val c = src[i]
      when {
        c == '\n' -> { nl = true; line++; i++ }
        c.isWhitespace() -> i++
        src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') i++
        src.startsWith("/*", i) -> {
          val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
          for (k in i until end) if (src[k] == '\n') { nl = true; line++ }
          i = end
        }
        else -> return nl
      }
    }
    return nl
  }

  private fun next(nl: Boolean): Tok {
    val c = src[i]
    val start = i
    if (c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit())) {
      if (c == '0' && i + 1 < src.length && (src[i + 1] == 'x' || src[i + 1] == 'X')) {
        i += 2
        while (i < src.length && (src[i].isLetterOrDigit())) i++
        return Tok(T.NUM, src.substring(start, i), line, nl, src.substring(start + 2, i).toLong(16).toDouble())
      }
      while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
      if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
        i++
        if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
        while (i < src.length && src[i].isDigit()) i++
      }
      val text = src.substring(start, i)
      return Tok(T.NUM, text, line, nl, text.toDoubleOrNull() ?: throw JsSyntaxError("Bad number '$text'", line))
    }
    if (c.isJavaIdentifierStart()) {
      while (i < src.length && src[i].isJavaIdentifierPart()) i++
      return Tok(T.IDENT, src.substring(start, i), line, nl)
    }
    if (c == '"' || c == '\'') return Tok(T.STR, string(c), line, nl)
    if (c == '`') return template(nl)
    for (p in PUNCT) if (src.startsWith(p, i)) {
      i += p.length; return Tok(T.PUNCT, p, line, nl)
    }
    throw JsSyntaxError("Unexpected character '$c'", line)
  }

  private fun string(quote: Char): String {
    i++
    val sb = StringBuilder()
    while (true) {
      if (i >= src.length || src[i] == '\n') throw JsSyntaxError("Unterminated string", line)
      val c = src[i++]
      if (c == quote) return sb.toString()
      if (c == '\\') escape(sb) else sb.append(c)
    }
  }

  private fun escape(sb: StringBuilder) {
    if (i >= src.length) return
    when (val e = src[i++]) {
      'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b')
      'f' -> sb.append('\u000c'); 'v' -> sb.append('\u000b'); '0' -> sb.append('\u0000')
      'u' -> {
        if (i < src.length && src[i] == '{') {
          val end = src.indexOf('}', i)
          sb.appendCodePoint(src.substring(i + 1, end).toInt(16)); i = end + 1
        }
        else {
          sb.append(src.substring(i, i + 4).toInt(16).toChar()); i += 4
        }
      }
      'x' -> { sb.append(src.substring(i, i + 2).toInt(16).toChar()); i += 2 }
      '\n' -> line++
      else -> sb.append(e)
    }
  }

  private fun template(nl: Boolean): Tok {
    val startLine = line
    i++
    val parts = ArrayList<String>()
    val exprs = ArrayList<String>()
    val sb = StringBuilder()
    while (true) {
      if (i >= src.length) throw JsSyntaxError("Unterminated template string", startLine)
      val c = src[i]
      when {
        c == '`' -> { i++; parts.add(sb.toString()); return Tok(T.TEMPLATE, "`", startLine, nl, parts = parts, exprs = exprs) }
        c == '\\' -> { i++; escape(sb) }
        c == '$' && i + 1 < src.length && src[i + 1] == '{' -> {
          parts.add(sb.toString()); sb.setLength(0)
          i += 2
          val from = i
          var depth = 1
          while (i < src.length && depth > 0) {
            when (src[i]) { '{' -> depth++; '}' -> depth--; '\n' -> line++ }
            if (depth > 0) i++
          }
          exprs.add(src.substring(from, i)); i++
        }
        else -> { if (c == '\n') line++; sb.append(c); i++ }
      }
    }
  }

  companion object {
    private val PUNCT = listOf(
      "===", "!==", "**=", "...", "??=", "||=", "&&=", ">>>",
      "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=", "/=", "%=", "**", "<<", ">>",
      "{", "}", "(", ")", "[", "]", ";", ",", "<", ">", "+", "-", "*", "/", "%", "!", "~", "?", ":", "=", ".", "&", "|", "^",
    )
  }
}

// ---- AST ----

internal sealed class Node(val line: Int)

internal sealed class Stmt(line: Int) : Node(line)
internal class VarStmt(line: Int, val const: Boolean, val decls: List<Pair<String, Expr?>>) : Stmt(line)
internal class FunStmt(line: Int, val fn: FunExpr) : Stmt(line)
internal class ExprStmt(line: Int, val expr: Expr) : Stmt(line)
internal class BlockStmt(line: Int, val body: List<Stmt>) : Stmt(line)
internal class IfStmt(line: Int, val test: Expr, val then: Stmt, val otherwise: Stmt?) : Stmt(line)
internal class ForStmt(line: Int, val init: Stmt?, val test: Expr?, val update: Expr?, val body: Stmt) : Stmt(line)
internal class ForEachStmt(line: Int, val name: String, val declares: Boolean, val of: Boolean, val iterable: Expr, val body: Stmt) : Stmt(line)
internal class WhileStmt(line: Int, val test: Expr, val body: Stmt, val doWhile: Boolean) : Stmt(line)
internal class ReturnStmt(line: Int, val value: Expr?) : Stmt(line)
internal class BreakStmt(line: Int) : Stmt(line)
internal class ContinueStmt(line: Int) : Stmt(line)
internal class ThrowStmt(line: Int, val value: Expr) : Stmt(line)
internal class TryStmt(line: Int, val body: BlockStmt, val param: String?, val handler: BlockStmt?, val finalizer: BlockStmt?) : Stmt(line)
internal class EmptyStmt(line: Int) : Stmt(line)

internal sealed class Expr(line: Int) : Node(line)
internal class Lit(line: Int, val value: Any?) : Expr(line)
internal class TemplateExpr(line: Int, val parts: List<String>, val exprs: List<Expr>) : Expr(line)
internal class Ident(line: Int, val name: String) : Expr(line)
internal class ThisExpr(line: Int) : Expr(line)
internal class ArrayExpr(line: Int, val items: List<Expr>) : Expr(line)
internal class ObjectExpr(line: Int, val props: List<Pair<Expr, Expr>>) : Expr(line) // key is Lit(String) or computed
internal class FunExpr(line: Int, val name: String?, val params: List<Pair<String, Expr?>>, val body: BlockStmt?, val exprBody: Expr?, val arrow: Boolean) : Expr(line)
internal class Member(line: Int, val obj: Expr, val prop: Expr, val computed: Boolean, val optional: Boolean) : Expr(line)
internal class Call(line: Int, val callee: Expr, val args: List<Expr>, val optional: Boolean) : Expr(line)
internal class New(line: Int, val callee: Expr, val args: List<Expr>) : Expr(line)
internal class Unary(line: Int, val op: String, val arg: Expr) : Expr(line)
internal class Update(line: Int, val op: String, val prefix: Boolean, val target: Expr) : Expr(line)
internal class Binary(line: Int, val op: String, val left: Expr, val right: Expr) : Expr(line)
internal class Logical(line: Int, val op: String, val left: Expr, val right: Expr) : Expr(line)
internal class Cond(line: Int, val test: Expr, val then: Expr, val otherwise: Expr) : Expr(line)
internal class Assign(line: Int, val op: String, val target: Expr, val value: Expr) : Expr(line)
internal class Seq(line: Int, val items: List<Expr>) : Expr(line)

internal class JsParser(source: String, firstLine: Int = 1) {
  private val toks = JsLexer(source, firstLine).tokens()
  private var p = 0

  private val tok get() = toks[p]
  private fun peek(n: Int = 1) = toks[minOf(p + n, toks.size - 1)]
  private fun isP(s: String) = tok.type == T.PUNCT && tok.text == s
  private fun isK(s: String) = tok.type == T.IDENT && tok.text == s
  private fun advance(): Tok = toks[p].also { if (p < toks.size - 1) p++ }
  private fun eatP(s: String): Boolean = if (isP(s)) { advance(); true } else false
  private fun expectP(s: String): Tok {
    if (!isP(s)) fail("Expected '$s' but found '${tok.text.ifEmpty { "end of script" }}'")
    return advance()
  }
  private fun fail(message: String): Nothing = throw JsSyntaxError(message, tok.line)
  private fun ident(): String {
    if (tok.type != T.IDENT || tok.text in RESERVED) fail("Expected a name but found '${tok.text}'")
    return advance().text
  }

  fun program(): List<Stmt> {
    val out = ArrayList<Stmt>()
    while (tok.type != T.EOF) out.add(statement())
    return out
  }

  private fun semicolon() {
    if (eatP(";")) return
    if (isP("}") || tok.type == T.EOF || tok.newlineBefore) return
    fail("Expected ';' but found '${tok.text}'")
  }

  private fun statement(): Stmt {
    val line = tok.line
    if (tok.type == T.PUNCT) when (tok.text) {
      "{" -> return block()
      ";" -> { advance(); return EmptyStmt(line) }
    }
    if (tok.type == T.IDENT) when (tok.text) {
      "var", "let", "const" -> { val s = varDecl(); semicolon(); return s }
      "function" -> { advance(); return FunStmt(line, function(line, ident(), arrow = false)) }
      "if" -> {
        advance(); expectP("(")
        val test = expression(); expectP(")")
        val then = statement()
        val otherwise = if (isK("else")) { advance(); statement() } else null
        return IfStmt(line, test, then, otherwise)
      }
      "for" -> return forStatement()
      "while" -> {
        advance(); expectP("(")
        val test = expression(); expectP(")")
        return WhileStmt(line, test, statement(), doWhile = false)
      }
      "do" -> {
        advance()
        val body = statement()
        if (!isK("while")) fail("Expected 'while'")
        advance(); expectP("(")
        val test = expression(); expectP(")"); eatP(";")
        return WhileStmt(line, test, body, doWhile = true)
      }
      "return" -> {
        advance()
        val value = if (isP(";") || isP("}") || tok.type == T.EOF || tok.newlineBefore) null else expression()
        semicolon()
        return ReturnStmt(line, value)
      }
      "break" -> { advance(); semicolon(); return BreakStmt(line) }
      "continue" -> { advance(); semicolon(); return ContinueStmt(line) }
      "throw" -> { advance(); val v = expression(); semicolon(); return ThrowStmt(line, v) }
      "try" -> {
        advance()
        val body = block()
        var param: String? = null
        var handler: BlockStmt? = null
        if (isK("catch")) {
          advance()
          if (eatP("(")) { param = ident(); expectP(")") }
          handler = block()
        }
        val finalizer = if (isK("finally")) { advance(); block() } else null
        if (handler == null && finalizer == null) fail("Expected 'catch' or 'finally'")
        return TryStmt(line, body, param, handler, finalizer)
      }
      "class", "import", "export", "async", "await", "yield", "switch" -> if (tok.text != "async" || peek().text == "function") {
        fail("'${tok.text}' is not supported in handler scripts")
      }
    }
    val e = expression()
    semicolon()
    return ExprStmt(line, e)
  }

  private fun block(): BlockStmt {
    val line = expectP("{").line
    val body = ArrayList<Stmt>()
    while (!isP("}")) {
      if (tok.type == T.EOF) fail("Expected '}'")
      body.add(statement())
    }
    advance()
    return BlockStmt(line, body)
  }

  private fun varDecl(): VarStmt {
    val line = tok.line
    val const = advance().text == "const"
    val decls = ArrayList<Pair<String, Expr?>>()
    do {
      if (isP("{") || isP("[")) fail("Destructuring is not supported in handler scripts")
      val name = ident()
      decls.add(name to if (eatP("=")) assignment() else null)
    } while (eatP(","))
    return VarStmt(line, const, decls)
  }

  private fun forStatement(): Stmt {
    val line = advance().line
    expectP("(")
    // for (const x of xs) / for (k in obj)
    val declares = tok.type == T.IDENT && tok.text in setOf("var", "let", "const")
    val nameAt = if (declares) 1 else 0
    if (peek(nameAt).type == T.IDENT && peek(nameAt + 1).type == T.IDENT && peek(nameAt + 1).text in setOf("of", "in")) {
      if (declares) advance()
      val name = ident()
      val of = advance().text == "of"
      val iterable = expression()
      expectP(")")
      return ForEachStmt(line, name, declares, of, iterable, statement())
    }
    val init = when {
      isP(";") -> null
      declares -> varDecl()
      else -> ExprStmt(tok.line, expression())
    }
    expectP(";")
    val test = if (isP(";")) null else expression()
    expectP(";")
    val update = if (isP(")")) null else expression()
    expectP(")")
    return ForStmt(line, init, test, update, statement())
  }

  fun expression(): Expr {
    val line = tok.line
    val first = assignment()
    if (!isP(",")) return first
    val items = arrayListOf(first)
    while (eatP(",")) items.add(assignment())
    return Seq(line, items)
  }

  private fun arrowAhead(): Boolean {
    if (tok.type == T.IDENT && tok.text !in RESERVED && peek().type == T.PUNCT && peek().text == "=>") return true
    if (!isP("(")) return false
    var depth = 0
    var k = p
    while (k < toks.size) {
      val t = toks[k]
      if (t.type == T.PUNCT) when (t.text) {
        "(", "[", "{" -> depth++
        ")", "]", "}" -> { depth--; if (depth == 0) return toks.getOrNull(k + 1)?.let { it.type == T.PUNCT && it.text == "=>" } == true }
      }
      if (t.type == T.EOF) return false
      k++
    }
    return false
  }

  private fun assignment(): Expr {
    val line = tok.line
    if (arrowAhead()) return arrow()
    val left = conditional()
    if (tok.type == T.PUNCT && tok.text in ASSIGN_OPS) {
      if (left !is Ident && left !is Member) fail("Invalid assignment target")
      val op = advance().text
      return Assign(line, op, left, assignment())
    }
    return left
  }

  private fun arrow(): Expr {
    val line = tok.line
    val params = if (isP("(")) params() else listOf(ident() to null)
    expectP("=>")
    return if (isP("{")) FunExpr(line, null, params, block(), null, arrow = true)
    else FunExpr(line, null, params, null, assignment(), arrow = true)
  }

  private fun params(): List<Pair<String, Expr?>> {
    expectP("(")
    val out = ArrayList<Pair<String, Expr?>>()
    while (!isP(")")) {
      if (isP("...") || isP("{") || isP("[")) fail("Rest and destructured parameters are not supported in handler scripts")
      val name = ident()
      out.add(name to if (eatP("=")) assignment() else null)
      if (!eatP(",")) break
    }
    expectP(")")
    return out
  }

  private fun function(line: Int, name: String?, arrow: Boolean): FunExpr = FunExpr(line, name, params(), block(), null, arrow)

  private fun conditional(): Expr {
    val line = tok.line
    val test = binary(0)
    if (!eatP("?")) return test
    val then = assignment()
    expectP(":")
    return Cond(line, test, then, assignment())
  }

  private fun binary(minPrec: Int): Expr {
    var left = unary()
    while (true) {
      val op = tok.text
      val prec = when {
        tok.type == T.PUNCT -> BINARY_PREC[op]
        tok.type == T.IDENT && (op == "instanceof" || op == "in") -> 7
        else -> null
      } ?: return left
      if (prec < minPrec) return left
      val line = advance().line
      val right = if (op == "**") binary(prec) else binary(prec + 1)
      left = if (op == "&&" || op == "||" || op == "??") Logical(line, op, left, right) else Binary(line, op, left, right)
    }
  }

  private fun unary(): Expr {
    val line = tok.line
    if (tok.type == T.PUNCT && tok.text in setOf("!", "-", "+", "~")) return Unary(line, advance().text, unary())
    if (tok.type == T.PUNCT && (tok.text == "++" || tok.text == "--")) return Update(line, advance().text, true, unary())
    if (tok.type == T.IDENT && tok.text in setOf("typeof", "void", "delete")) return Unary(line, advance().text, unary())
    if (isK("await")) fail("'await' is not supported in handler scripts")
    var e = postfix(primary())
    if (tok.type == T.PUNCT && (tok.text == "++" || tok.text == "--") && !tok.newlineBefore) e = Update(line, advance().text, false, e)
    return e
  }

  private fun postfix(start: Expr): Expr {
    var e = start
    while (true) {
      val line = tok.line
      e = when {
        isP(".") -> { advance(); Member(line, e, Lit(line, propName()), computed = false, optional = false) }
        isP("?.") -> {
          advance()
          when {
            isP("(") -> Call(line, e, args(), optional = true)
            isP("[") -> { advance(); val k = expression(); expectP("]"); Member(line, e, k, computed = true, optional = true) }
            else -> Member(line, e, Lit(line, propName()), computed = false, optional = true)
          }
        }
        isP("[") -> { advance(); val k = expression(); expectP("]"); Member(line, e, k, computed = true, optional = false) }
        isP("(") -> Call(line, e, args(), optional = false)
        tok.type == T.TEMPLATE && !tok.newlineBefore -> fail("Tagged templates are not supported in handler scripts")
        else -> return e
      }
    }
  }

  private fun propName(): String {
    if (tok.type != T.IDENT) fail("Expected a property name but found '${tok.text}'")
    return advance().text
  }

  private fun args(): List<Expr> {
    expectP("(")
    val out = ArrayList<Expr>()
    while (!isP(")")) {
      if (isP("...")) fail("Spread is not supported in handler scripts")
      out.add(assignment())
      if (!eatP(",")) break
    }
    expectP(")")
    return out
  }

  private fun primary(): Expr {
    val t = tok
    val line = t.line
    return when (t.type) {
      T.NUM -> { advance(); return Lit(line, t.num) }
      T.STR -> { advance(); return Lit(line, t.text) }
      T.TEMPLATE -> {
        advance()
        return TemplateExpr(line, t.parts, t.exprs.map { JsParser(it, line).let { p -> p.expression().also { p.end() } } })
      }
      T.EOF -> fail("Unexpected end of script")
      T.PUNCT -> when (t.text) {
        "(" -> { advance(); val e = expression(); expectP(")"); return e }
        "[" -> {
          advance()
          val items = ArrayList<Expr>()
          while (!isP("]")) {
            if (isP("...")) fail("Spread is not supported in handler scripts")
            items.add(assignment())
            if (!eatP(",")) break
          }
          expectP("]")
          return ArrayExpr(line, items)
        }
        "{" -> return objectLiteral()
        "/" -> fail("Regular expression literals are not supported in handler scripts")
        else -> fail("Unexpected '${t.text}'")
      }
      T.IDENT -> when (t.text) {
        "true" -> { advance(); return Lit(line, true) }
        "false" -> { advance(); return Lit(line, false) }
        "null" -> { advance(); return Lit(line, JsNull) }
        "undefined" -> { advance(); return Lit(line, Undefined) }
        "this" -> { advance(); return ThisExpr(line) }
        "function" -> { advance(); val name = if (tok.type == T.IDENT) advance().text else null; return function(line, name, arrow = false) }
        "new" -> {
          advance()
          var callee: Expr = Ident(tok.line, ident())
          while (isP(".")) { advance(); callee = Member(line, callee, Lit(line, propName()), computed = false, optional = false) }
          val args = if (isP("(")) args() else emptyList()
          return postfix(New(line, callee, args))
        }
        else -> { if (t.text in RESERVED) fail("Unexpected '${t.text}'"); advance(); return Ident(line, t.text) }
      }
    }
  }

  private fun objectLiteral(): Expr {
    val line = expectP("{").line
    val props = ArrayList<Pair<Expr, Expr>>()
    while (!isP("}")) {
      val keyLine = tok.line
      if (isP("...")) fail("Spread is not supported in handler scripts")
      val key: Expr = when (tok.type) {
        T.STR -> Lit(keyLine, advance().text)
        T.NUM -> Lit(keyLine, JsValues.toStr(advance().num))
        T.IDENT -> Lit(keyLine, advance().text)
        else -> if (eatP("[")) expression().also { expectP("]") } else fail("Expected a property name")
      }
      val value: Expr = when {
        eatP(":") -> assignment()
        isP("(") -> function(keyLine, (key as? Lit)?.value as? String, arrow = false)
        key is Lit && (isP(",") || isP("}")) -> Ident(keyLine, key.value as String)
        else -> fail("Expected ':'")
      }
      props.add(key to value)
      if (!eatP(",")) break
    }
    expectP("}")
    return ObjectExpr(line, props)
  }

  fun end() {
    if (tok.type != T.EOF) fail("Unexpected '${tok.text}'")
  }

  companion object {
    private val ASSIGN_OPS = setOf("=", "+=", "-=", "*=", "/=", "%=", "**=", "??=", "||=", "&&=")
    private val RESERVED = setOf("var", "let", "const", "function", "if", "else", "for", "while", "do", "return", "break",
                                 "continue", "throw", "try", "catch", "finally", "new", "typeof", "void", "delete", "in",
                                 "instanceof", "class", "switch", "case", "default")
    private val BINARY_PREC = mapOf(
      "??" to 1, "||" to 2, "&&" to 3, "|" to 4, "^" to 4, "&" to 4,
      "==" to 6, "!=" to 6, "===" to 6, "!==" to 6,
      "<" to 7, ">" to 7, "<=" to 7, ">=" to 7,
      "<<" to 8, ">>" to 8, ">>>" to 8,
      "+" to 9, "-" to 9, "*" to 10, "/" to 10, "%" to 10, "**" to 11,
    )
  }
}
