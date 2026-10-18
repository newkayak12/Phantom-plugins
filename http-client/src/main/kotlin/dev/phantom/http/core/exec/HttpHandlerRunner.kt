// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core.exec

import dev.phantom.http.core.script.*
import kotlinx.serialization.json.Json

/** One `> {% ... %}` block or `> handler.js` file, read out of PSI. [line] is where the script starts in its file. */
data class HttpHandlerSource(val script: String, val line: Int, val origin: String)

data class HttpTestResult(val name: String, val passed: Boolean, val message: String?)

/** What the response handlers did: test results, `client.log` lines, and the first error that stopped a script. */
data class HttpHandlerReport(val tests: List<HttpTestResult>, val logs: List<String>, val errors: List<String>) {
  val failed: Int get() = tests.count { !it.passed }
}

/**
 * Runs response handler scripts with the JetBrains HTTP Client API that handlers use most: `client.global`,
 * `client.test`, `client.assert`, `client.log`, `client.exit`, `response.status/body/headers/contentType`,
 * `request.environment/variables`. Every handler of a request shares one interpreter, as in IntelliJ IDEA Ultimate.
 */
object HttpHandlerRunner {
  private class ExitSignal : RuntimeException(null, null, false, false)
  private class AssertionFailed(message: String) : RuntimeException(message, null, false, false)

  fun run(
    handlers: List<HttpHandlerSource>,
    request: HttpRequestModel,
    response: HttpResponseModel,
    globals: MutableMap<String, String>,
    variable: (String) -> String?,
  ): HttpHandlerReport {
    val tests = ArrayList<HttpTestResult>()
    val logs = ArrayList<String>()
    val errors = ArrayList<String>()
    val interp = JsInterpreter()

    val log = JsFunction { _, args -> logs.add(args.joinToString(" ") { JsValues.display(it) }); Undefined }
    val client = JsObject().apply {
      set("global", JsObject().apply {
        set("set", JsFunction { _, a ->
          val name = JsValues.toStr(a.getOrElse(0) { Undefined })
          when (val value = a.getOrElse(1) { Undefined }) {
            Undefined, JsNull -> globals.remove(name)
            else -> globals[name] = JsValues.display(value)
          }
          Undefined
        })
        set("get", JsFunction { _, a -> globals[JsValues.toStr(a.getOrElse(0) { Undefined })] ?: JsNull })
        set("isEmpty", JsFunction { _, _ -> globals.isEmpty() })
        set("clear", JsFunction { _, a -> globals.remove(JsValues.toStr(a.getOrElse(0) { Undefined })); Undefined })
        set("clearAll", JsFunction { _, _ -> globals.clear(); Undefined })
      })
      set("log", log)
      set("exit", JsFunction { _, _ -> throw ExitSignal() })
      set("assert", JsFunction { _, a ->
        if (!JsValues.truthy(a.getOrElse(0) { Undefined })) {
          throw AssertionFailed(a.getOrNull(1)?.takeIf { it !== Undefined }?.let(JsValues::toStr) ?: "Assertion failed")
        }
        Undefined
      })
      set("test", JsFunction { _, a ->
        val name = JsValues.toStr(a.getOrElse(0) { Undefined })
        val fn = a.getOrNull(1)
        tests.add(try {
          interp.callFunction(fn, Undefined, emptyList())
          HttpTestResult(name, true, null)
        }
        catch (e: AssertionFailed) {
          HttpTestResult(name, false, e.message)
        }
        catch (e: JsThrow) {
          HttpTestResult(name, false, e.message)
        })
        Undefined
      })
    }
    interp.globals["client"] = client
    interp.globals["console"] = JsObject().apply { set("log", log); set("info", log); set("warn", log); set("error", log) }
    interp.globals["response"] = responseObject(response)
    interp.globals["request"] = requestObject(request, variable)

    for (handler in handlers) {
      try {
        interp.run(handler.script, handler.line)
      }
      catch (_: ExitSignal) {
      }
      catch (e: AssertionFailed) {
        errors.add("${handler.origin}, line ${interp.line}: ${e.message}")
      }
      catch (e: JsThrow) {
        errors.add("${handler.origin}, line ${e.line}: ${e.message}")
      }
      catch (e: JsSyntaxError) {
        errors.add("${handler.origin}: syntax error: ${e.message}")
      }
      catch (e: JsLimitExceeded) {
        errors.add("${handler.origin}: ${e.message}")
      }
    }
    return HttpHandlerReport(tests, logs, errors)
  }

  private fun responseObject(response: HttpResponseModel): JsObject {
    val mime = response.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    val charset = response.contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "UTF-8"
    val text = String(response.body, runCatching { charset(charset) }.getOrDefault(Charsets.UTF_8))
    val body: Any? = if (mime.endsWith("json") || mime.endsWith("+json") || (mime.isEmpty() && text.trimStart().let { it.startsWith("{") || it.startsWith("[") })) {
      runCatching { JsValues.fromJson(Json.parseToJsonElement(text)) }.getOrDefault(text)
    }
    else text
    return JsObject().apply {
      set("status", response.status.toDouble())
      set("body", body)
      set("headers", JsObject().apply {
        set("valueOf", JsFunction { _, a -> response.header(JsValues.toStr(a.getOrElse(0) { Undefined })) ?: JsNull })
        set("valuesOf", JsFunction { _, a ->
          val name = JsValues.toStr(a.getOrElse(0) { Undefined })
          JsArray(response.headers.filter { it.first.equals(name, ignoreCase = true) }.mapTo(ArrayList()) { it.second })
        })
      })
      set("contentType", JsObject().apply { set("mimeType", mime); set("charset", charset) })
    }
  }

  private fun requestObject(request: HttpRequestModel, variable: (String) -> String?): JsObject {
    val lookup = JsFunction { _, a -> variable(JsValues.toStr(a.getOrElse(0) { Undefined })) ?: JsNull }
    val url = request.uri.toString()
    return JsObject().apply {
      set("method", request.method)
      set("url", JsObject().apply {
        set("getRaw", JsFunction { _, _ -> url })
        set("tryGetSubstituted", JsFunction { _, _ -> url })
      })
      set("headers", JsObject().apply {
        set("findByName", JsFunction { _, a ->
          val name = JsValues.toStr(a.getOrElse(0) { Undefined })
          request.headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second ?: JsNull
        })
      })
      set("environment", JsObject().apply { set("get", lookup) })
      set("variables", JsObject().apply { set("get", lookup) })
    }
  }
}
