// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core.script

import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64
import kotlin.math.*

object Undefined { override fun toString() = "undefined" }
object JsNull { override fun toString() = "null" }

/** A plain object. Insertion order is kept, as in JavaScript for string keys. */
open class JsObject(val props: MutableMap<String, Any?> = LinkedHashMap()) {
  open fun get(key: String): Any? = if (props.containsKey(key)) props[key] else Undefined
  open fun set(key: String, value: Any?) { props[key] = value }
}

class JsArray(val items: MutableList<Any?> = ArrayList()) : JsObject()

fun interface JsFunction {
  fun call(self: Any?, args: List<Any?>): Any?
}

/** A function the script itself defines. */
internal class Closure(val fn: FunExpr, val scope: Scope, val boundThis: Any?, private val interp: JsInterpreter) : JsFunction {
  val props = LinkedHashMap<String, Any?>()
  override fun call(self: Any?, args: List<Any?>): Any? = interp.invoke(this, if (fn.arrow) boundThis else self, args)
}

class JsDate(val epochMs: Double) : JsObject()

/** A `throw` from the script, or an error the interpreter raises the way JavaScript would. */
class JsThrow(val value: Any?, val line: Int) : RuntimeException(null, null, false, false) {
  override val message: String
    get() = (value as? JsObject)?.let { o ->
      val name = JsValues.toStr(o.get("name")).takeIf { it != "undefined" }
      val msg = JsValues.toStr(o.get("message")).takeIf { it != "undefined" }
      listOfNotNull(name, msg).joinToString(": ").ifEmpty { null }
    } ?: JsValues.toStr(value)
}

class JsLimitExceeded(message: String) : RuntimeException(message)

internal class Scope(val parent: Scope?) {
  val vars = HashMap<String, Any?>()
  val consts = HashSet<String>()

  fun lookup(name: String): Scope? {
    var s: Scope? = this
    while (s != null) {
      if (s.vars.containsKey(name)) return s
      s = s.parent
    }
    return null
  }
}

private class BreakSignal : RuntimeException(null, null, false, false)
private class ContinueSignal : RuntimeException(null, null, false, false)
private class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)

/**
 * Tree-walking interpreter. Runs on the caller's thread with a step budget and a deadline, so a runaway loop in a
 * handler cannot hang the HTTP client.
 */
class JsInterpreter(private val maxSteps: Long = 5_000_000, private val timeoutMs: Long = 10_000) {
  val globals: MutableMap<String, Any?> get() = root.vars
  private val root = Scope(null)
  private var steps = 0L
  private var deadline = 0L
  var line: Int = 0
    private set

  init {
    Builtins.install(this, root.vars)
  }

  /** Runs [source]; returns the value of the last expression statement. */
  fun run(source: String, firstLine: Int = 1): Any? {
    val program = JsParser(source, firstLine).program()
    deadline = System.currentTimeMillis() + timeoutMs
    steps = 0
    hoist(program, root)
    var last: Any? = Undefined
    for (s in program) {
      if (s is ExprStmt) { line = s.line; tick(); last = eval(s.expr, root) } else exec(s, root)
    }
    return last
  }

  fun callFunction(fn: Any?, self: Any?, args: List<Any?>): Any? {
    if (fn !is JsFunction) throw typeError("${JsValues.typeOf(fn)} is not a function")
    return fn.call(self, args)
  }

  fun typeError(message: String): JsThrow = JsThrow(errorObject("TypeError", message), line)
  fun errorObject(name: String, message: String): JsObject = JsObject().apply { set("name", name); set("message", message) }

  private fun tick() {
    if (++steps > maxSteps) throw JsLimitExceeded("The script ran more than $maxSteps steps")
    if (steps and 0x3FF == 0L) {
      if (System.currentTimeMillis() > deadline) throw JsLimitExceeded("The script ran longer than ${timeoutMs / 1000} s")
      if (Thread.currentThread().isInterrupted) throw JsLimitExceeded("Cancelled")
    }
  }

  private fun hoist(body: List<Stmt>, scope: Scope) {
    for (s in body) if (s is FunStmt) scope.vars[s.fn.name!!] = Closure(s.fn, scope, null, this)
  }

  internal fun invoke(c: Closure, self: Any?, args: List<Any?>): Any? {
    val scope = Scope(c.scope)
    if (!c.fn.arrow) {
      scope.vars["this"] = self
      scope.vars["arguments"] = JsArray(args.toMutableList())
    }
    c.fn.params.forEachIndexed { i, (name, default) ->
      var v = args.getOrElse(i) { Undefined }
      if (v === Undefined && default != null) v = eval(default, scope)
      scope.vars[name] = v
    }
    c.fn.exprBody?.let { return eval(it, scope) }
    val body = c.fn.body!!.body
    hoist(body, scope)
    try {
      for (s in body) exec(s, scope)
    }
    catch (r: ReturnSignal) {
      return r.value
    }
    return Undefined
  }

  private fun exec(s: Stmt, scope: Scope) {
    line = s.line
    tick()
    when (s) {
      is ExprStmt -> eval(s.expr, scope)
      is VarStmt -> for ((name, init) in s.decls) {
        scope.vars[name] = init?.let { eval(it, scope) } ?: Undefined
        if (s.const) scope.consts.add(name)
      }
      is FunStmt -> {}
      is BlockStmt -> {
        val inner = Scope(scope)
        hoist(s.body, inner)
        for (x in s.body) exec(x, inner)
      }
      is IfStmt -> if (JsValues.truthy(eval(s.test, scope))) exec(s.then, scope) else s.otherwise?.let { exec(it, scope) }
      is ForStmt -> {
        val loop = Scope(scope)
        s.init?.let { exec(it, loop) }
        while (s.test == null || JsValues.truthy(eval(s.test, loop))) {
          try { exec(s.body, loop) } catch (_: BreakSignal) { break } catch (_: ContinueSignal) {}
          s.update?.let { eval(it, loop) }
          tick()
        }
      }
      is ForEachStmt -> {
        val target = eval(s.iterable, scope)
        val values: List<Any?> = if (s.of) iterate(target) else keys(target)
        for (v in values) {
          val loop = Scope(scope)
          if (s.declares) loop.vars[s.name] = v else assign(Ident(s.line, s.name), v, scope)
          try { exec(s.body, loop) } catch (_: BreakSignal) { break } catch (_: ContinueSignal) {}
          tick()
        }
      }
      is WhileStmt -> {
        if (s.doWhile) {
          do {
            try { exec(s.body, scope) } catch (_: BreakSignal) { break } catch (_: ContinueSignal) {}
            tick()
          } while (JsValues.truthy(eval(s.test, scope)))
        }
        else {
          while (JsValues.truthy(eval(s.test, scope))) {
            try { exec(s.body, scope) } catch (_: BreakSignal) { break } catch (_: ContinueSignal) {}
            tick()
          }
        }
      }
      is ReturnStmt -> throw ReturnSignal(s.value?.let { eval(it, scope) } ?: Undefined)
      is BreakStmt -> throw BreakSignal()
      is ContinueStmt -> throw ContinueSignal()
      is ThrowStmt -> throw JsThrow(eval(s.value, scope), s.line)
      is TryStmt -> try {
        try {
          exec(s.body, scope)
        }
        catch (e: JsThrow) {
          val handler = s.handler ?: throw e
          val inner = Scope(scope)
          s.param?.let { inner.vars[it] = e.value }
          exec(handler, inner)
        }
      }
      finally {
        s.finalizer?.let { exec(it, scope) }
      }
      is EmptyStmt -> {}
    }
  }

  private fun iterate(v: Any?): List<Any?> = when (v) {
    is JsArray -> v.items.toList()
    is String -> v.codePoints().toArray().map { String(Character.toChars(it)) }
    is JsObject -> throw typeError("object is not iterable")
    else -> throw typeError("${JsValues.toStr(v)} is not iterable")
  }

  private fun keys(v: Any?): List<Any?> = when (v) {
    is JsArray -> v.items.indices.map { it.toString() }
    is JsObject -> v.props.keys.toList()
    is String -> v.indices.map { it.toString() }
    else -> emptyList()
  }

  internal fun eval(e: Expr, scope: Scope): Any? {
    return when (e) {
    is Lit -> e.value
    is TemplateExpr -> buildString {
      for (i in e.parts.indices) {
        append(e.parts[i])
        if (i < e.exprs.size) append(JsValues.toStr(eval(e.exprs[i], scope)))
      }
    }
    is Ident -> {
      val s = scope.lookup(e.name) ?: throw JsThrow(errorObject("ReferenceError", "${e.name} is not defined"), e.line)
      s.vars[e.name]
    }
    is ThisExpr -> scope.lookup("this")?.vars?.get("this") ?: Undefined
    is ArrayExpr -> JsArray(e.items.mapTo(ArrayList()) { eval(it, scope) })
    is ObjectExpr -> JsObject().also { o -> for ((k, v) in e.props) o.set(JsValues.toStr(eval(k, scope)), eval(v, scope)) }
    is FunExpr -> Closure(e, scope, if (e.arrow) eval(ThisExpr(e.line), scope) else null, this)
    is Member -> {
      val obj = eval(e.obj, scope)
      if (e.optional && (obj === Undefined || obj === JsNull)) Undefined
      else getProp(obj, JsValues.toPropertyKey(eval(e.prop, scope)))
    }
    is Call -> call(e, scope)
    is New -> construct(eval(e.callee, scope), e.args.map { eval(it, scope) })
    is Unary -> unary(e, scope)
    is Update -> {
      val old = JsValues.toNumber(eval(e.target, scope))
      val new = if (e.op == "++") old + 1 else old - 1
      assign(e.target, new, scope)
      if (e.prefix) new else old
    }
    is Binary -> JsValues.binary(e.op, eval(e.left, scope), eval(e.right, scope), this)
    is Logical -> {
      val l = eval(e.left, scope)
      when (e.op) {
        "&&" -> if (JsValues.truthy(l)) eval(e.right, scope) else l
        "||" -> if (JsValues.truthy(l)) l else eval(e.right, scope)
        else -> if (l === Undefined || l === JsNull) eval(e.right, scope) else l
      }
    }
    is Cond -> if (JsValues.truthy(eval(e.test, scope))) eval(e.then, scope) else eval(e.otherwise, scope)
    is Assign -> {
      val value = when (e.op) {
        "=" -> eval(e.value, scope)
        "??=" -> eval(e.target, scope).let { if (it === Undefined || it === JsNull) eval(e.value, scope) else return it }
        "||=" -> eval(e.target, scope).let { if (!JsValues.truthy(it)) eval(e.value, scope) else return it }
        "&&=" -> eval(e.target, scope).let { if (JsValues.truthy(it)) eval(e.value, scope) else return it }
        else -> JsValues.binary(e.op.dropLast(1), eval(e.target, scope), eval(e.value, scope), this)
      }
      assign(e.target, value, scope)
      value
    }
    is Seq -> { var last: Any? = Undefined; for (x in e.items) last = eval(x, scope); last }
    }
  }

  private fun unary(e: Unary, scope: Scope): Any? {
    if (e.op == "typeof" && e.arg is Ident && scope.lookup(e.arg.name) == null) return "undefined"
    if (e.op == "delete") {
      val m = e.arg as? Member ?: return true
      val obj = eval(m.obj, scope)
      val key = JsValues.toPropertyKey(eval(m.prop, scope))
      if (obj is JsArray) key.toIntOrNull()?.takeIf { it in obj.items.indices }?.let { obj.items[it] = Undefined }
      else if (obj is JsObject) obj.props.remove(key)
      return true
    }
    val v = eval(e.arg, scope)
    return when (e.op) {
      "!" -> !JsValues.truthy(v)
      "-" -> -JsValues.toNumber(v)
      "+" -> JsValues.toNumber(v)
      "~" -> JsValues.toInt32(v).inv().toDouble()
      "typeof" -> JsValues.typeOf(v)
      "void" -> Undefined
      else -> throw typeError("Unknown operator ${e.op}")
    }
  }

  private fun call(e: Call, scope: Scope): Any? {
    val (self, fn) = when (val callee = e.callee) {
      is Member -> {
        val obj = eval(callee.obj, scope)
        if (callee.optional && (obj === Undefined || obj === JsNull)) return Undefined
        val key = JsValues.toPropertyKey(eval(callee.prop, scope))
        val f = getProp(obj, key)
        if (f !is JsFunction) {
          if (e.optional && (f === Undefined || f === JsNull)) return Undefined
          throw typeError("${describe(callee)} is not a function")
        }
        obj to f
      }
      else -> {
        val f = eval(callee, scope)
        if (e.optional && (f === Undefined || f === JsNull)) return Undefined
        if (f !is JsFunction) throw typeError("${describe(callee)} is not a function")
        Undefined to f
      }
    }
    val args = e.args.map { eval(it, scope) }
    return fn.call(self, args)
  }

  private fun describe(e: Expr): String = when (e) {
    is Ident -> e.name
    is Member -> describe(e.obj) + if (e.computed) "[…]" else "." + (e.prop as Lit).value
    is ThisExpr -> "this"
    is Call -> describe(e.callee) + "(…)"
    else -> "expression"
  }

  private fun construct(ctor: Any?, args: List<Any?>): Any? = when {
    Builtins.tag(ctor) == "Date" -> JsDate(Builtins.dateArg(args))
    Builtins.tag(ctor) == "Error" -> errorObject("Error", args.firstOrNull()?.let(JsValues::toStr) ?: "")
    ctor is Closure -> {
      val obj = JsObject()
      val result = ctor.call(obj, args)
      if (result is JsObject) result else obj
    }
    ctor is JsFunction -> ctor.call(Undefined, args)
    else -> throw typeError("${JsValues.toStr(ctor)} is not a constructor")
  }

  private fun assign(target: Expr, value: Any?, scope: Scope) {
    when (target) {
      is Ident -> {
        val s = scope.lookup(target.name)
        if (s != null && target.name in s.consts) throw typeError("Assignment to constant variable '${target.name}'")
        (s ?: root).vars[target.name] = value
      }
      is Member -> setProp(eval(target.obj, scope), JsValues.toPropertyKey(eval(target.prop, scope)), value)
      else -> throw typeError("Invalid assignment target")
    }
  }

  fun getProp(obj: Any?, key: String): Any? = when (obj) {
    Undefined, JsNull -> throw typeError("Cannot read properties of ${JsValues.toStr(obj)} (reading '$key')")
    is String -> Builtins.stringMember(this, obj, key)
    is Double -> Builtins.numberMember(obj, key)
    is Boolean -> if (key == "toString") JsFunction { _, _ -> obj.toString() } else Undefined
    is JsArray -> key.toIntOrNull()?.let { obj.items.getOrElse(it) { Undefined } }
                  ?: if (key == "length") obj.items.size.toDouble() else Builtins.arrayMember(this, obj, key)
    is JsDate -> Builtins.dateMember(obj, key)
    is JsObject -> obj.get(key).let { if (it === Undefined && !obj.props.containsKey(key)) Builtins.objectMember(obj, key) else it }
    is Closure -> obj.props[key] ?: Undefined
    is JsFunction -> Undefined
    else -> Undefined
  }

  fun setProp(obj: Any?, key: String, value: Any?) {
    when (obj) {
      Undefined, JsNull -> throw typeError("Cannot set properties of ${JsValues.toStr(obj)} (setting '$key')")
      is JsArray -> {
        val index = key.toIntOrNull()
        when {
          index != null && index >= 0 -> {
            while (obj.items.size <= index) obj.items.add(Undefined)
            obj.items[index] = value
          }
          key == "length" -> {
            val n = JsValues.toNumber(value).toInt()
            while (obj.items.size > n) obj.items.removeAt(obj.items.size - 1)
            while (obj.items.size < n) obj.items.add(Undefined)
          }
          else -> obj.set(key, value)
        }
      }
      is JsObject -> obj.set(key, value)
      is Closure -> obj.props[key] = value
      else -> {} // writes to primitives are ignored, as in sloppy-mode JavaScript
    }
  }
}

object JsValues {
  fun truthy(v: Any?): Boolean = when (v) {
    null, Undefined, JsNull -> false
    is Boolean -> v
    is Double -> v != 0.0 && !v.isNaN()
    is String -> v.isNotEmpty()
    else -> true
  }

  fun typeOf(v: Any?): String = when (v) {
    null, Undefined -> "undefined"
    is Boolean -> "boolean"
    is Double -> "number"
    is String -> "string"
    is JsFunction -> "function"
    else -> "object"
  }

  fun toNumber(v: Any?): Double = when (v) {
    null, Undefined -> Double.NaN
    JsNull -> 0.0
    is Boolean -> if (v) 1.0 else 0.0
    is Double -> v
    is String -> v.trim().let { s ->
      when {
        s.isEmpty() -> 0.0
        s.startsWith("0x") || s.startsWith("0X") -> s.substring(2).toLongOrNull(16)?.toDouble() ?: Double.NaN
        s == "Infinity" || s == "+Infinity" -> Double.POSITIVE_INFINITY
        s == "-Infinity" -> Double.NEGATIVE_INFINITY
        s.any { it.isLetter() && it != 'e' && it != 'E' } -> Double.NaN
        else -> s.toDoubleOrNull() ?: Double.NaN
      }
    }
    is JsDate -> v.epochMs
    is JsArray -> when (v.items.size) { 0 -> 0.0; 1 -> toNumber(v.items[0]); else -> Double.NaN }
    else -> Double.NaN
  }

  fun toInt32(v: Any?): Int {
    val d = toNumber(v)
    return if (d.isNaN() || d.isInfinite()) 0 else d.toLong().toInt()
  }

  fun toStr(v: Any?): String = when (v) {
    null, Undefined -> "undefined"
    JsNull -> "null"
    is Boolean -> v.toString()
    is Double -> numberToString(v)
    is String -> v
    is JsArray -> v.items.joinToString(",") { if (it === Undefined || it === JsNull) "" else toStr(it) }
    is JsDate -> Instant.ofEpochMilli(v.epochMs.toLong()).atZone(java.time.ZoneId.systemDefault()).toString()
    is JsObject -> if (v.props.containsKey("message") && v.props.containsKey("name")) "${toStr(v.get("name"))}: ${toStr(v.get("message"))}" else "[object Object]"
    is JsFunction -> "function"
    else -> v.toString()
  }

  fun numberToString(d: Double): String = when {
    d.isNaN() -> "NaN"
    d == Double.POSITIVE_INFINITY -> "Infinity"
    d == Double.NEGATIVE_INFINITY -> "-Infinity"
    d == 0.0 -> "0"
    d == floor(d) && abs(d) < 1e21 -> d.toLong().toString()
    else -> {
      val s = d.toString()
      if (s.contains('E')) {
        val (m, exp) = s.split('E')
        val mant = m.removeSuffix(".0")
        mant + "e" + (if (exp.startsWith("-")) exp else "+$exp")
      }
      else s
    }
  }

  fun toPropertyKey(v: Any?): String = if (v is Double && v == floor(v) && !v.isInfinite()) v.toLong().toString() else toStr(v)

  private fun toPrimitive(v: Any?): Any? = when (v) {
    is JsDate -> toStr(v)
    is JsObject, is JsFunction -> toStr(v)
    else -> v
  }

  fun strictEquals(a: Any?, b: Any?): Boolean = when {
    a is Double && b is Double -> a == b // NaN != NaN, 0.0 == -0.0
    a is String && b is String -> a == b
    a is Boolean && b is Boolean -> a == b
    (a === Undefined || a == null) && (b === Undefined || b == null) -> true
    else -> a === b
  }

  fun looseEquals(a: Any?, b: Any?): Boolean {
    val an = a === Undefined || a === JsNull || a == null
    val bn = b === Undefined || b === JsNull || b == null
    if (an || bn) return an && bn
    if (typeOf(a) == typeOf(b) && a !is JsDate) return strictEquals(a, b)
    if (a is JsObject && b is JsObject) return a === b
    val pa = if (a is Boolean) toNumber(a) else toPrimitive(a)
    val pb = if (b is Boolean) toNumber(b) else toPrimitive(b)
    if (pa is String && pb is String) return pa == pb
    return toNumber(pa) == toNumber(pb)
  }

  private fun compare(a: Any?, b: Any?): Int? {
    val pa = toPrimitive(a)
    val pb = toPrimitive(b)
    if (pa is String && pb is String) return pa.compareTo(pb)
    val x = toNumber(pa)
    val y = toNumber(pb)
    if (x.isNaN() || y.isNaN()) return null
    return x.compareTo(y)
  }

  internal fun binary(op: String, a: Any?, b: Any?, interp: JsInterpreter): Any? = when (op) {
    "+" -> {
      val pa = toPrimitive(a)
      val pb = toPrimitive(b)
      if (pa is String || pb is String) toStr(pa) + toStr(pb) else toNumber(pa) + toNumber(pb)
    }
    "-" -> toNumber(a) - toNumber(b)
    "*" -> toNumber(a) * toNumber(b)
    "/" -> toNumber(a) / toNumber(b)
    "%" -> toNumber(a) % toNumber(b)
    "**" -> toNumber(a).pow(toNumber(b))
    "==" -> looseEquals(a, b)
    "!=" -> !looseEquals(a, b)
    "===" -> strictEquals(a, b)
    "!==" -> !strictEquals(a, b)
    "<" -> compare(a, b)?.let { it < 0 } ?: false
    ">" -> compare(a, b)?.let { it > 0 } ?: false
    "<=" -> compare(a, b)?.let { it <= 0 } ?: false
    ">=" -> compare(a, b)?.let { it >= 0 } ?: false
    "&" -> (toInt32(a) and toInt32(b)).toDouble()
    "|" -> (toInt32(a) or toInt32(b)).toDouble()
    "^" -> (toInt32(a) xor toInt32(b)).toDouble()
    "<<" -> (toInt32(a) shl (toInt32(b) and 31)).toDouble()
    ">>" -> (toInt32(a) shr (toInt32(b) and 31)).toDouble()
    ">>>" -> (toInt32(a).toUInt() shr (toInt32(b) and 31)).toDouble()
    "in" -> when (b) {
      is JsArray -> toPropertyKey(a).toIntOrNull()?.let { it in b.items.indices } ?: (toStr(a) == "length")
      is JsObject -> b.props.containsKey(toPropertyKey(a))
      else -> throw interp.typeError("Cannot use 'in' operator to search for '${toStr(a)}' in ${toStr(b)}")
    }
    "instanceof" -> when (Builtins.tag(b)) {
      "Array" -> a is JsArray
      "Date" -> a is JsDate
      "Object" -> a is JsObject
      "Error" -> a is JsObject && a.props.containsKey("message")
      else -> if (b is JsFunction) false else throw interp.typeError("Right-hand side of 'instanceof' is not callable")
    }
    else -> throw interp.typeError("Unknown operator $op")
  }

  // ---- JSON ----

  fun fromJson(e: JsonElement): Any? = when (e) {
    is JsonNull -> JsNull
    is JsonPrimitive -> when {
      e.isString -> e.content
      e.booleanOrNull != null -> e.boolean
      else -> e.content.toDouble()
    }
    is JsonArray -> JsArray(e.mapTo(ArrayList()) { fromJson(it) })
    is JsonObject -> JsObject(e.entries.associateTo(LinkedHashMap()) { it.key to fromJson(it.value) })
  }

  /** `JSON.stringify`: `undefined` and functions are left out of objects, become `null` in arrays. */
  fun stringify(v: Any?, indent: String = "", current: String = ""): String? = when (v) {
    null, Undefined, is JsFunction -> null
    JsNull -> "null"
    is Boolean -> v.toString()
    is Double -> if (v.isNaN() || v.isInfinite()) "null" else numberToString(v)
    is String -> quote(v)
    is JsDate -> quote(toStr(v))
    is JsArray -> {
      if (v.items.isEmpty()) "[]"
      else {
        val inner = current + indent
        val items = v.items.map { stringify(it, indent, inner) ?: "null" }
        if (indent.isEmpty()) items.joinToString(",", "[", "]")
        else items.joinToString(",\n$inner", "[\n$inner", "\n$current]")
      }
    }
    is JsObject -> {
      val inner = current + indent
      val entries = v.props.mapNotNull { (k, x) -> stringify(x, indent, inner)?.let { quote(k) + (if (indent.isEmpty()) ":" else ": ") + it } }
      when {
        entries.isEmpty() -> "{}"
        indent.isEmpty() -> entries.joinToString(",", "{", "}")
        else -> entries.joinToString(",\n$inner", "{\n$inner", "\n$current}")
      }
    }
    else -> quote(v.toString())
  }

  private fun quote(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
      '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
      '\b' -> append("\\b"); '\u000c' -> append("\\f")
      else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
  }

  /** For `client.log` and test messages: objects as JSON, everything else as `String(v)`. */
  fun display(v: Any?): String = when (v) {
    is JsObject -> if (v is JsDate) toStr(v) else stringify(v) ?: toStr(v)
    else -> toStr(v)
  }
}

internal object Builtins {
  fun tag(v: Any?): String? = (v as? JsObjectFunction)?.tag

  private fun num(args: List<Any?>, i: Int) = JsValues.toNumber(args.getOrElse(i) { Undefined })
  private fun str(args: List<Any?>, i: Int) = JsValues.toStr(args.getOrElse(i) { Undefined })
  private fun arg(args: List<Any?>, i: Int) = args.getOrElse(i) { Undefined }

  fun dateArg(args: List<Any?>): Double = when (val a = args.firstOrNull()) {
    null, Undefined -> System.currentTimeMillis().toDouble()
    is Double -> a
    is JsDate -> a.epochMs
    else -> runCatching { Instant.parse(JsValues.toStr(a)).toEpochMilli().toDouble() }
      .recoverCatching { java.time.LocalDate.parse(JsValues.toStr(a)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli().toDouble() }
      .getOrDefault(Double.NaN)
  }

  fun install(interp: JsInterpreter, g: MutableMap<String, Any?>) {
    g["NaN"] = Double.NaN
    g["Infinity"] = Double.POSITIVE_INFINITY
    g["globalThis"] = JsObject(g)
    g["Array"] = JsObjectFunction("Array") { _, a -> JsArray(a.toMutableList()) }.apply { set("isArray", JsFunction { _, a -> arg(a, 0) is JsArray }) }
    g["Object"] = JsObjectFunction("Object") { _, a -> a.firstOrNull()?.takeIf { it is JsObject } ?: JsObject() }.apply {
      set("keys", JsFunction { _, a -> JsArray(keysOf(arg(a, 0)).toMutableList()) })
      set("values", JsFunction { _, a -> val o = arg(a, 0); JsArray(keysOf(o).mapTo(ArrayList()) { interp.getProp(o, it) }) })
      set("entries", JsFunction { _, a -> val o = arg(a, 0); JsArray(keysOf(o).mapTo(ArrayList()) { JsArray(mutableListOf(it, interp.getProp(o, it))) }) })
      set("assign", JsFunction { _, a ->
        val target = arg(a, 0) as? JsObject ?: throw interp.typeError("Cannot convert to object")
        for (src in a.drop(1)) for (k in keysOf(src)) interp.setProp(target, k, interp.getProp(src, k))
        target
      })
    }
    g["Error"] = JsObjectFunction("Error") { _, a -> interp.errorObject("Error", a.firstOrNull()?.let(JsValues::toStr) ?: "") }
    g["Date"] = JsObjectFunction("Date") { _, _ -> Instant.now().toString() }.apply {
      set("now", JsFunction { _, _ -> System.currentTimeMillis().toDouble() })
      set("parse", JsFunction { _, a -> dateArg(a) })
    }
    g["String"] = JsFunction { _, a -> if (a.isEmpty()) "" else JsValues.toStr(a[0]) }
    g["Number"] = JsObjectFunction("Number") { _, a -> if (a.isEmpty()) 0.0 else JsValues.toNumber(a[0]) }.apply {
      set("isInteger", JsFunction { _, a -> (arg(a, 0) as? Double)?.let { !it.isInfinite() && it == floor(it) } ?: false })
      set("isNaN", JsFunction { _, a -> (arg(a, 0) as? Double)?.isNaN() ?: false })
      set("parseFloat", JsFunction { _, a -> parseFloat(str(a, 0)) })
      set("parseInt", JsFunction { _, a -> parseInt(str(a, 0), arg(a, 1)) })
    }
    g["Boolean"] = JsFunction { _, a -> JsValues.truthy(arg(a, 0)) }
    g["parseInt"] = JsFunction { _, a -> parseInt(str(a, 0), arg(a, 1)) }
    g["parseFloat"] = JsFunction { _, a -> parseFloat(str(a, 0)) }
    g["isNaN"] = JsFunction { _, a -> num(a, 0).isNaN() }
    g["isFinite"] = JsFunction { _, a -> num(a, 0).isFinite() }
    g["encodeURIComponent"] = JsFunction { _, a -> URLEncoder.encode(str(a, 0), Charsets.UTF_8).replace("+", "%20").replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~") }
    g["decodeURIComponent"] = JsFunction { _, a -> URLDecoder.decode(str(a, 0).replace("+", "%2B"), Charsets.UTF_8) }
    g["btoa"] = JsFunction { _, a -> Base64.getEncoder().encodeToString(str(a, 0).toByteArray(Charsets.ISO_8859_1)) }
    g["atob"] = JsFunction { _, a ->
      val s = str(a, 0).replace("\\s".toRegex(), "")
      String(Base64.getMimeDecoder().decode(s), Charsets.ISO_8859_1)
    }
    g["JSON"] = JsObject().apply {
      set("parse", JsFunction { _, a ->
        try {
          JsValues.fromJson(Json.parseToJsonElement(str(a, 0)))
        }
        catch (e: Exception) {
          throw JsThrow(interp.errorObject("SyntaxError", "JSON.parse: ${e.message?.lineSequence()?.firstOrNull()}"), interp.line)
        }
      })
      set("stringify", JsFunction { _, a ->
        val indent = when (val s = arg(a, 2)) {
          is Double -> " ".repeat(s.toInt().coerceIn(0, 10))
          is String -> s.take(10)
          else -> ""
        }
        JsValues.stringify(arg(a, 0), indent) ?: Undefined
      })
    }
    g["Math"] = JsObject().apply {
      set("PI", PI); set("E", E)
      set("floor", JsFunction { _, a -> floor(num(a, 0)) })
      set("ceil", JsFunction { _, a -> ceil(num(a, 0)) })
      set("round", JsFunction { _, a -> floor(num(a, 0) + 0.5) })
      set("trunc", JsFunction { _, a -> truncate(num(a, 0)) })
      set("abs", JsFunction { _, a -> abs(num(a, 0)) })
      set("sign", JsFunction { _, a -> sign(num(a, 0)) })
      set("sqrt", JsFunction { _, a -> sqrt(num(a, 0)) })
      set("pow", JsFunction { _, a -> num(a, 0).pow(num(a, 1)) })
      set("random", JsFunction { _, _ -> Math.random() })
      set("max", JsFunction { _, a -> a.map(JsValues::toNumber).fold(Double.NEGATIVE_INFINITY) { x, y -> if (x.isNaN() || y.isNaN()) Double.NaN else max(x, y) } })
      set("min", JsFunction { _, a -> a.map(JsValues::toNumber).fold(Double.POSITIVE_INFINITY) { x, y -> if (x.isNaN() || y.isNaN()) Double.NaN else min(x, y) } })
    }
  }

  /** A function with properties, like `Object.keys` on `Object`. */
  class JsObjectFunction(val tag: String, private val fn: JsFunction) : JsObject(), JsFunction {
    override fun call(self: Any?, args: List<Any?>): Any? = fn.call(self, args)
  }

  private fun keysOf(o: Any?): List<String> = when (o) {
    is JsArray -> o.items.indices.map { it.toString() }
    is JsObject -> o.props.keys.toList()
    is String -> o.indices.map { it.toString() }
    else -> emptyList()
  }

  private fun parseFloat(s: String): Double {
    val m = Regex("""^\s*[+-]?(Infinity|\d+\.?\d*(e[+-]?\d+)?|\.\d+(e[+-]?\d+)?)""", RegexOption.IGNORE_CASE).find(s) ?: return Double.NaN
    return JsValues.toNumber(m.value)
  }

  private fun parseInt(s: String, radixArg: Any?): Double {
    var t = s.trim()
    var sign = 1
    if (t.startsWith("-")) { sign = -1; t = t.substring(1) } else if (t.startsWith("+")) t = t.substring(1)
    var radix = JsValues.toInt32(radixArg)
    if ((radix == 0 || radix == 16) && (t.startsWith("0x") || t.startsWith("0X"))) { t = t.substring(2); radix = 16 }
    if (radix == 0) radix = 10
    if (radix !in 2..36) return Double.NaN
    val digits = t.takeWhile { Character.digit(it, radix) >= 0 }
    if (digits.isEmpty()) return Double.NaN
    return sign * digits.fold(0.0) { acc, c -> acc * radix + Character.digit(c, radix) }
  }

  private fun relIndex(v: Any?, length: Int, default: Int): Int {
    if (v === Undefined) return default
    val d = JsValues.toNumber(v)
    if (d.isNaN()) return 0
    val i = truncate(d).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    return if (i < 0) maxOf(0, length + i) else minOf(i, length)
  }

  fun stringMember(interp: JsInterpreter, s: String, key: String): Any? {
    key.toIntOrNull()?.let { return if (it in s.indices) s[it].toString() else Undefined }
    return when (key) {
      "length" -> s.length.toDouble()
      "toString", "valueOf" -> JsFunction { _, _ -> s }
      "charAt" -> JsFunction { _, a -> s.getOrNull(JsValues.toInt32(arg(a, 0)))?.toString() ?: "" }
      "charCodeAt" -> JsFunction { _, a -> s.getOrNull(JsValues.toInt32(arg(a, 0)))?.code?.toDouble() ?: Double.NaN }
      "at" -> JsFunction { _, a -> val i = JsValues.toInt32(arg(a, 0)).let { if (it < 0) s.length + it else it }; s.getOrNull(i)?.toString() ?: Undefined }
      "includes" -> JsFunction { _, a -> s.contains(str(a, 0)) }
      "startsWith" -> JsFunction { _, a -> s.startsWith(str(a, 0), relIndex(arg(a, 1), s.length, 0)) }
      "endsWith" -> JsFunction { _, a -> s.substring(0, relIndex(arg(a, 1), s.length, s.length)).endsWith(str(a, 0)) }
      "indexOf" -> JsFunction { _, a -> s.indexOf(str(a, 0), relIndex(arg(a, 1), s.length, 0)).toDouble() }
      "lastIndexOf" -> JsFunction { _, a -> s.lastIndexOf(str(a, 0)).toDouble() }
      "slice" -> JsFunction { _, a ->
        val from = relIndex(arg(a, 0), s.length, 0)
        val to = relIndex(arg(a, 1), s.length, s.length)
        if (from < to) s.substring(from, to) else ""
      }
      "substring" -> JsFunction { _, a ->
        fun clamp(v: Any?, d: Int) = if (v === Undefined) d else JsValues.toNumber(v).let { if (it.isNaN()) 0 else it.coerceIn(0.0, s.length.toDouble()).toInt() }
        val x = clamp(arg(a, 0), 0)
        val y = clamp(arg(a, 1), s.length)
        s.substring(minOf(x, y), maxOf(x, y))
      }
      "substr" -> JsFunction { _, a ->
        val from = relIndex(arg(a, 0), s.length, 0)
        val len = if (arg(a, 1) === Undefined) s.length - from else JsValues.toInt32(arg(a, 1)).coerceIn(0, s.length - from)
        s.substring(from, from + len)
      }
      "toLowerCase", "toLocaleLowerCase" -> JsFunction { _, _ -> s.lowercase() }
      "toUpperCase", "toLocaleUpperCase" -> JsFunction { _, _ -> s.uppercase() }
      "trim" -> JsFunction { _, _ -> s.trim() }
      "trimStart" -> JsFunction { _, _ -> s.trimStart() }
      "trimEnd" -> JsFunction { _, _ -> s.trimEnd() }
      "padStart" -> JsFunction { _, a -> pad(s, JsValues.toInt32(arg(a, 0)), if (arg(a, 1) === Undefined) " " else str(a, 1), true) }
      "padEnd" -> JsFunction { _, a -> pad(s, JsValues.toInt32(arg(a, 0)), if (arg(a, 1) === Undefined) " " else str(a, 1), false) }
      "repeat" -> JsFunction { _, a -> s.repeat(JsValues.toInt32(arg(a, 0)).coerceAtLeast(0)) }
      "split" -> JsFunction { _, a ->
        val sep = arg(a, 0)
        val parts: List<String> = when {
          sep === Undefined -> listOf(s)
          JsValues.toStr(sep).isEmpty() -> s.map { it.toString() }
          else -> s.split(JsValues.toStr(sep))
        }
        val limit = if (arg(a, 1) === Undefined) parts.size else JsValues.toInt32(arg(a, 1))
        JsArray(parts.take(limit).toMutableList())
      }
      "replace", "replaceAll" -> JsFunction { _, a ->
        val pattern = str(a, 0)
        val replacement = arg(a, 1)
        fun with(match: String): String = if (replacement is JsFunction) JsValues.toStr(interp.callFunction(replacement, Undefined, listOf(match))) else JsValues.toStr(replacement)
        if (key == "replace") {
          val at = s.indexOf(pattern)
          if (at < 0) s else s.substring(0, at) + with(pattern) + s.substring(at + pattern.length)
        }
        else if (pattern.isEmpty()) s else s.split(pattern).joinToString(with(pattern))
      }
      "concat" -> JsFunction { _, a -> s + a.joinToString("") { JsValues.toStr(it) } }
      "localeCompare" -> JsFunction { _, a -> s.compareTo(str(a, 0)).sign.toDouble() }
      else -> Undefined
    }
  }

  private fun pad(s: String, length: Int, filler: String, start: Boolean): String {
    if (length <= s.length || filler.isEmpty()) return s
    val fill = buildString { while (this.length < length - s.length) append(filler) }.take(length - s.length)
    return if (start) fill + s else s + fill
  }

  fun numberMember(d: Double, key: String): Any? = when (key) {
    "toFixed" -> JsFunction { _, a -> java.math.BigDecimal(d).setScale(JsValues.toInt32(arg(a, 0)), java.math.RoundingMode.HALF_UP).toPlainString() }
    "toString" -> JsFunction { _, a ->
      val radix = JsValues.toInt32(arg(a, 0))
      if (radix in 2..36 && radix != 10 && d == floor(d)) d.toLong().toString(radix) else JsValues.numberToString(d)
    }
    "valueOf" -> JsFunction { _, _ -> d }
    else -> Undefined
  }

  private val ISO_MILLIS = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

  fun dateMember(date: JsDate, key: String): Any? {
    val instant = Instant.ofEpochMilli(date.epochMs.toLong())
    val utc = instant.atZone(java.time.ZoneOffset.UTC)
    val local = instant.atZone(java.time.ZoneId.systemDefault())
    return when (key) {
      "getTime", "valueOf" -> JsFunction { _, _ -> date.epochMs }
      "toISOString", "toJSON" -> JsFunction { _, _ -> ISO_MILLIS.format(instant) }
      "toString" -> JsFunction { _, _ -> local.toString() }
      "getFullYear" -> JsFunction { _, _ -> local.year.toDouble() }
      "getMonth" -> JsFunction { _, _ -> (local.monthValue - 1).toDouble() }
      "getDate" -> JsFunction { _, _ -> local.dayOfMonth.toDouble() }
      "getDay" -> JsFunction { _, _ -> (local.dayOfWeek.value % 7).toDouble() }
      "getHours" -> JsFunction { _, _ -> local.hour.toDouble() }
      "getMinutes" -> JsFunction { _, _ -> local.minute.toDouble() }
      "getSeconds" -> JsFunction { _, _ -> local.second.toDouble() }
      "getUTCFullYear" -> JsFunction { _, _ -> utc.year.toDouble() }
      "getUTCMonth" -> JsFunction { _, _ -> (utc.monthValue - 1).toDouble() }
      "getUTCDate" -> JsFunction { _, _ -> utc.dayOfMonth.toDouble() }
      "getUTCHours" -> JsFunction { _, _ -> utc.hour.toDouble() }
      else -> date.get(key)
    }
  }

  fun objectMember(o: JsObject, key: String): Any? = when (key) {
    "hasOwnProperty" -> JsFunction { _, a -> o.props.containsKey(JsValues.toPropertyKey(arg(a, 0))) }
    "toString" -> JsFunction { _, _ -> JsValues.toStr(o) }
    else -> Undefined
  }

  fun arrayMember(interp: JsInterpreter, arr: JsArray, key: String): Any? {
    val items = arr.items
    fun cb(a: List<Any?>): JsFunction = arg(a, 0) as? JsFunction ?: throw interp.typeError("${JsValues.toStr(arg(a, 0))} is not a function")
    fun each(a: List<Any?>, body: (Int, Any?, Any?) -> Boolean) {
      val f = cb(a)
      for (i in items.indices.toList()) {
        if (i >= items.size) break
        if (!body(i, items[i], f.call(arg(a, 1), listOf(items[i], i.toDouble(), arr)))) return
      }
    }
    return when (key) {
      "push" -> JsFunction { _, a -> items.addAll(a); items.size.toDouble() }
      "pop" -> JsFunction { _, _ -> if (items.isEmpty()) Undefined else items.removeAt(items.size - 1) }
      "shift" -> JsFunction { _, _ -> if (items.isEmpty()) Undefined else items.removeAt(0) }
      "unshift" -> JsFunction { _, a -> items.addAll(0, a); items.size.toDouble() }
      "forEach" -> JsFunction { _, a -> each(a) { _, _, _ -> true }; Undefined }
      "map" -> JsFunction { _, a -> val out = ArrayList<Any?>(); each(a) { _, _, r -> out.add(r); true }; JsArray(out) }
      "filter" -> JsFunction { _, a -> val out = ArrayList<Any?>(); each(a) { _, v, r -> if (JsValues.truthy(r)) out.add(v); true }; JsArray(out) }
      "find" -> JsFunction { _, a -> var found: Any? = Undefined; each(a) { _, v, r -> if (JsValues.truthy(r)) { found = v; false } else true }; found }
      "findIndex" -> JsFunction { _, a -> var found = -1; each(a) { i, _, r -> if (JsValues.truthy(r)) { found = i; false } else true }; found.toDouble() }
      "some" -> JsFunction { _, a -> var any = false; each(a) { _, _, r -> if (JsValues.truthy(r)) { any = true; false } else true }; any }
      "every" -> JsFunction { _, a -> var all = true; each(a) { _, _, r -> if (!JsValues.truthy(r)) { all = false; false } else true }; all }
      "reduce" -> JsFunction { _, a ->
        val f = cb(a)
        var start = 0
        var acc: Any? = if (a.size > 1) a[1] else {
          if (items.isEmpty()) throw interp.typeError("Reduce of empty array with no initial value")
          start = 1; items[0]
        }
        for (i in start until items.size) acc = f.call(Undefined, listOf(acc, items[i], i.toDouble(), arr))
        acc
      }
      "includes" -> JsFunction { _, a -> val x = arg(a, 0); items.any { JsValues.strictEquals(it, x) || (it is Double && x is Double && it.isNaN() && x.isNaN()) } }
      "indexOf" -> JsFunction { _, a -> val x = arg(a, 0); items.indexOfFirst { JsValues.strictEquals(it, x) }.toDouble() }
      "join" -> JsFunction { _, a -> items.joinToString(if (arg(a, 0) === Undefined) "," else str(a, 0)) { if (it === Undefined || it === JsNull) "" else JsValues.toStr(it) } }
      "slice" -> JsFunction { _, a ->
        val from = relIndex(arg(a, 0), items.size, 0)
        val to = relIndex(arg(a, 1), items.size, items.size)
        JsArray(if (from < to) ArrayList(items.subList(from, to)) else ArrayList())
      }
      "splice" -> JsFunction { _, a ->
        val from = relIndex(arg(a, 0), items.size, 0)
        val count = if (a.size < 2) items.size - from else JsValues.toInt32(a[1]).coerceIn(0, items.size - from)
        val removed = ArrayList(items.subList(from, from + count))
        repeat(count) { items.removeAt(from) }
        items.addAll(from, a.drop(2))
        JsArray(removed)
      }
      "concat" -> JsFunction { _, a -> JsArray(ArrayList(items).apply { for (x in a) if (x is JsArray) addAll(x.items) else add(x) }) }
      "reverse" -> JsFunction { _, _ -> items.reverse(); arr }
      "sort" -> JsFunction { _, a ->
        val f = arg(a, 0) as? JsFunction
        val sorted = if (f != null) items.sortedWith { x, y -> JsValues.toNumber(f.call(Undefined, listOf(x, y))).let { if (it < 0) -1 else if (it > 0) 1 else 0 } }
                     else items.sortedBy { JsValues.toStr(it) }
        items.clear(); items.addAll(sorted); arr
      }
      "at" -> JsFunction { _, a -> val i = JsValues.toInt32(arg(a, 0)).let { if (it < 0) items.size + it else it }; items.getOrElse(i) { Undefined } }
      "flat" -> JsFunction { _, _ -> JsArray(items.flatMapTo(ArrayList()) { if (it is JsArray) it.items else listOf(it) }) }
      "toString" -> JsFunction { _, _ -> JsValues.toStr(arr) }
      else -> arr.get(key)
    }
  }
}
