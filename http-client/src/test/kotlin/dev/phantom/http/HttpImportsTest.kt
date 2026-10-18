// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.http.core.*
import dev.phantom.http.core.exec.HttpRequestBuilder

class HttpImportsTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.http")
    }
  }

  private fun msg(key: String, vararg params: Any) = PhantomHttpBundle.message(key, *params)

  private fun file(text: String): HttpRequestFile = myFixture.configureByText("api.http", text) as HttpRequestFile

  private fun problems(): List<Pair<String, String>> {
    val doc = myFixture.editor.document.text
    return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
      .filter { it.description != null }
      .sortedBy { it.startOffset }
      .map { it.description to doc.substring(it.startOffset, it.endOffset) }
  }

  private fun addAuth() = myFixture.addFileToProject("auth.http", """
    @host = http://localhost:8080
    @user = guest

    ### Login
    POST {{host}}/login?user={{user}}

    ###
    # @name GET request with one var
    GET {{host}}/me
  """.trimIndent())

  fun testParsesImportAndRunLines() {
    val file = file("""
      import ./auth.http
      import auth.http

      ### step
      run #GET request with one var (@host=http://example.com, @user = admin)

      ###
      run ./auth.http

      ###
      import.example.com/x
    """.trimIndent())
    assertEquals(listOf("./auth.http", "auth.http"), file.imports.map { it.path })
    val runs = PsiTreeUtil.findChildrenOfType(file, HttpRun::class.java).toList()
    assertEquals(listOf("GET request with one var", null), runs.map { it.requestName })
    assertEquals(listOf(null, "./auth.http"), runs.map { it.path })
    assertEquals(mapOf("host" to "http://example.com", "user" to "admin"), runs[0].overrides)
    // a request whose URL starts with "import" is still a request
    assertEquals(listOf("GET"), file.requests.map { it.requestLine!!.method })
  }

  fun testInspections() {
    addAuth()
    myFixture.enableInspections(HttpDuplicateImportInspection(), HttpUnresolvedImportInspection(), HttpRunRequestNameInspection())
    file("""
      import ./auth.http
      import auth.http
      import ./missing.http

      ### Local
      GET http://localhost/local

      ###
      run #Login

      ###
      run #Local

      ###
      run #GET request with one var (@user=admin)

      ###
      run #Logout

      ###
      run ./gone.http
    """.trimIndent())
    assertEquals(listOf(
      msg("http.inspection.import.duplicate", "auth.http") to "auth.http",
      msg("http.inspection.import.unresolved", "./missing.http") to "./missing.http",
      msg("http.inspection.run.name.unknown", "Logout") to "Logout",
      msg("http.inspection.import.unresolved", "./gone.http") to "./gone.http",
    ), problems())
  }

  fun testImportedVariablesResolve() {
    addAuth()
    val file = file("import ./auth.http\n\nGET {{host}}/x?u={{user}}&z={{nope}}\n")
    val unresolved = myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull { it.description }.filter { it.startsWith("Unresolved variable") }
    assertEquals(listOf(msg("http.variable.unresolved", "nope")), unresolved)
    val block = file.requests.single()
    val resolver = HttpVariables.resolver(file, emptyMap(), null)
    assertEquals("http://localhost:8080/x?u=guest&z={{nope}}", resolver.substitute("{{host}}/x?u={{user}}&z={{nope}}"))
    assertNotNull(block)
  }

  fun testRunNameNavigationAndCompletion() {
    val auth = addAuth()
    file("import ./auth.http\n\n### Mine\nGET http://localhost/mine\n\n###\nrun #Log<caret>in\n")
    val target = myFixture.elementAtCaret
    assertEquals(auth.virtualFile, target.containingFile.virtualFile)
    assertEquals("Login", (target as HttpRequestBlock).requestName)

    file("import ./auth.http\n\n### Mine\nGET http://localhost/mine\n\n###\nrun #<caret>\n")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, "Mine", "Login", "GET request with one var")
  }

  fun testImportPathNavigatesToFile() {
    val auth = addAuth()
    file("import ./au<caret>th.http\n")
    assertEquals(auth, myFixture.elementAtCaret)
  }

  fun testStepsExpandRunLinesWithOverrides() {
    addAuth()
    myFixture.addFileToProject("flow.http", "import ./auth.http\n\n###\nrun #Login (@user=nested)\n\n###\nGET {{host}}/flow\n")
    val file = file("""
      import ./auth.http

      ### First
      GET http://localhost/first

      ###
      run #Login (@user=admin, @host=http://example.com)

      ###
      run ./flow.http (@host=http://outer)
    """.trimIndent())
    val steps = HttpImports.steps(file)
    val urls = steps.map { step ->
      HttpRequestBuilder.build(step.block, HttpVariables.resolver(step.block.containingFile, emptyMap(), null, step.overrides)).uri.toString()
    }
    assertEquals(listOf(
      "http://localhost/first",
      "http://example.com/login?user=admin",
      // the nested line's own value wins over the outer one for the same name; the outer one fills the rest
      "http://outer/login?user=nested",
      "http://outer/flow",
    ), urls)
  }

  fun testRunGutterOnRunLines() {
    addAuth()
    file("import ./auth.http\n\n###\nr<caret>un #Login\n\n###\nGET http://localhost/a\n")
    assertEquals(1, myFixture.findGuttersAtCaret().size)
    assertEquals(2, myFixture.findAllGutters().size)
  }
}
