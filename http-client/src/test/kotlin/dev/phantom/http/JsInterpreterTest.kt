// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http

import dev.phantom.http.core.script.*
import junit.framework.TestCase

class JsInterpreterTest : TestCase() {
  private fun eval(source: String): String = JsValues.display(JsInterpreter().run(source))

  fun testExpressionsAndCoercion() {
    assertEquals("7", eval("1 + 2 * 3"))
    assertEquals("12", eval("'1' + 2"))
    assertEquals("-1", eval("'1' - 2"))
    assertEquals("true", eval("1 == '1' && null == undefined && 1 !== '1'"))
    assertEquals("0.30000000000000004", eval("0.1 + 0.2"))
    assertEquals("2.5", eval("5 / 2"))
    assertEquals("1", eval("7 % 3"))
    assertEquals("default", eval("let a = null; a ?? 'default'"))
    assertEquals("object", eval("typeof null"))
    assertEquals("undefined", eval("typeof notDeclared"))
    assertEquals("b", eval("const x = 3; x > 2 ? 'b' : 'c'"))
  }

  fun testFunctionsClosuresAndArrows() {
    assertEquals("6", eval("function add(a, b = 1) { return a + b } add(5)"))
    assertEquals("3", eval("""
      const counter = () => { let n = 0; return () => ++n };
      const next = counter(); next(); next(); next()
    """))
    assertEquals("[2,4,6]", eval("[1, 2, 3].map(x => x * 2)"))
    assertEquals("10", eval("[1, 2, 3, 4].reduce((a, b) => a + b, 0)"))
    assertEquals("hoisted", eval("f(); function f() { return 'hoisted' }"))
    assertEquals("{\"a\":1,\"b\":{\"c\":[1,\"x\"]}}", eval("const b = { c: [1, 'x'] }; ({ a: 1, b })"))
  }

  fun testControlFlow() {
    assertEquals("10", eval("let s = 0; for (let i = 0; i < 5; i++) { s += i } s"))
    assertEquals("a=1,b=2", eval("""
      const o = { a: 1, b: 2 }; const out = [];
      for (const k in o) out.push(k + '=' + o[k]);
      out.join(',')
    """))
    assertEquals("3", eval("let n = 0; for (const x of [1, 2, 3, 4]) { if (x == 4) break; n++ } n"))
    assertEquals("caught: boom", eval("let r; try { throw new Error('boom') } catch (e) { r = 'caught: ' + e.message } r"))
    assertEquals("TypeError", eval("let r; try { null.x } catch (e) { r = e.name } r"))
  }

  fun testStringsJsonAndTemplates() {
    assertEquals("Bearer abc", eval("const t = 'abc'; `Bearer ${'$'}{t}`"))
    assertEquals("42", eval("JSON.parse('{\"a\":{\"b\":42}}').a.b"))
    assertEquals("{\n  \"x\": 1\n}", JsValues.toStr(JsInterpreter().run("JSON.stringify({ x: 1, f: () => 1 }, null, 2)")))
    assertEquals("[\"a\",\"b\"]", eval("'a,b'.split(',')"))
    assertEquals("true", eval("'application/json'.startsWith('application') && 'x'.padStart(3, '0') === '00x'"))
    assertEquals("{\"sub\":\"1\"}", eval("JSON.parse(atob('eyJzdWIiOiIxIn0='))"))
  }

  fun testRunawayLoopsStop() {
    try {
      JsInterpreter(maxSteps = 10_000).run("while (true) {}")
      fail()
    }
    catch (_: JsLimitExceeded) {
    }
  }

  fun testDeepRecursionStopsWithALimitError() {
    try {
      JsInterpreter().run("function f(n) { return f(n + 1) } f(0)")
      fail()
    }
    catch (e: JsLimitExceeded) {
      assertTrue(e.message, e.message!!.contains("call depth"))
    }
  }

  fun testCancelledRunStops() {
    try {
      JsInterpreter(cancelled = { true }).run("while (true) {}")
      fail()
    }
    catch (_: java.util.concurrent.CancellationException) {
    }
  }

  fun testUnsupportedSyntaxIsReportedWithALine() {
    try {
      JsInterpreter().run("let a = 1;\nclass B {}")
      fail()
    }
    catch (e: JsSyntaxError) {
      assertEquals(2, e.line)
    }
  }
}
