// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http

import com.intellij.lexer.Lexer
import com.intellij.testFramework.LexerTestCase
import dev.phantom.http.core.HttpLexer

class HttpLexerTest : LexerTestCase() {
  override fun createLexer(): Lexer = HttpLexer()
  override fun getDirPath(): String = ""

  private val sample = """
    > {% client.log(1) %}
    > {%
      client.log(2);
    %}
    @host = http://localhost:8080

    ### Login
    # @no-redirect
    POST {{host}}/login HTTP/1.1
    Content-Type: application/json

    {"id": "{{${'$'}uuid}}"}

    > {%
      client.global.set("token", response.body.token);
    %}

    ###
    GET {{host}}/items
        ?page=0
    Authorization: Bearer {{token}}

    < ./body.json
    ### Half
    PO
  """.trimIndent()

  fun testRestartFromEveryZeroState() {
    checkCorrectRestart(sample)
  }

  fun testRequestLineTokens() {
    doTest("GET {{host}}/a?x=1 HTTP/2\nAccept: */*\n", """
      METHOD ('GET')
      WHITE_SPACE (' ')
      {{ ('{{')
      VARIABLE_NAME ('host')
      }} ('}}')
      URL_TEXT ('/a?x=1')
      WHITE_SPACE (' ')
      HTTP_VERSION ('HTTP/2')
      WHITE_SPACE ('\n')
      HEADER_NAME ('Accept')
      : (':')
      WHITE_SPACE (' ')
      HEADER_VALUE ('*/*')
      WHITE_SPACE ('\n')
    """.trimIndent())
  }
}
