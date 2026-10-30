// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.strings

import dev.phantom.strings.core.StringTransforms
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class StringTransformsTest {
  @Test
  fun `words split on case, acronyms and separators`() {
    assertEquals(listOf("HTTP", "Server2", "foo", "Bar", "baz", "qux"), StringTransforms.words("HTTPServer2_fooBar-baz.qux"))
    assertEquals(listOf("utf8", "String"), StringTransforms.words("utf8String"))
  }

  @Test
  fun `case conversions`() {
    assertEquals("userId", StringTransforms.camel("user_id"))
    assertEquals("UserId", StringTransforms.pascal("user-id"))
    assertEquals("user_id", StringTransforms.snake("userId"))
    assertEquals("USER_ID", StringTransforms.screamingSnake("userId"))
    assertEquals("http-server", StringTransforms.kebab("HTTPServer"))
    assertEquals("user.id", StringTransforms.dot("UserId"))
    assertEquals("user id", StringTransforms.spaced("userId"))
  }

  @Test
  fun `switch case goes around`() {
    var s = "userId"
    val seen = ArrayList<String>()
    repeat(5) { s = StringTransforms.cycleCase(s); seen += s }
    assertEquals(listOf("user_id", "USER_ID", "user-id", "UserId", "userId"), seen)
  }

  @Test
  fun `switch case skips styles that change nothing`() {
    assertEquals("FOO", StringTransforms.cycleCase("foo"))
  }

  @Test
  fun `escapes round trip`() {
    val s = "a\"b\n<c&d> é/?="
    assertEquals(s, StringTransforms.unescapeJava(StringTransforms.escapeJava(s)))
    assertEquals(s, StringTransforms.unescapeHtml(StringTransforms.escapeHtml(s)))
    assertEquals(s, StringTransforms.decodeUrl(StringTransforms.encodeUrl(s)))
    assertEquals(s, StringTransforms.decodeBase64(StringTransforms.encodeBase64(s)))
    assertEquals("YQ==", StringTransforms.encodeBase64("a"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `invalid base64 throws`() {
    StringTransforms.decodeBase64("not base64!")
  }

  @Test
  fun `line operations keep a trailing line break`() {
    assertEquals("a2\na10\nb\n", StringTransforms.sortLines("b\na10\na2\n"))
    assertEquals("b\na10\na2", StringTransforms.sortLinesDescending("a2\nb\na10"))
    assertEquals("c\nb\na", StringTransforms.reverseLines("a\nb\nc"))
    assertEquals("a\nb\n", StringTransforms.removeDuplicateLines("a\nb\na\n"))
    assertEquals("a\nb", StringTransforms.removeBlankLines("a\n  \n\nb"))
    assertEquals("a\nb", StringTransforms.trimLines("  a \n\tb"))
    assertEquals(setOf("a", "b", "c"), StringTransforms.shuffleLines("a\nb\nc", Random(1)).split("\n").toSet())
  }

  @Test
  fun `case conversions keep leading and trailing separators`() {
    assertEquals("_foo_bar", StringTransforms.snake("_fooBar"))
    assertEquals("__init__", StringTransforms.camel("__init__"))
    assertEquals("\$user_name", StringTransforms.snake("\$userName"))
    assertEquals("_FOO_", StringTransforms.screamingSnake("_foo_"))
    assertEquals(" user id ", StringTransforms.spaced(" userId "))
    assertEquals("---", StringTransforms.camel("---"))
    assertEquals("_user_id", StringTransforms.cycleCase("_userId"))
    assertEquals("_userId", StringTransforms.cycleCase("_UserId"))
  }

  @Test
  fun `numeric character references are unescaped`() {
    assertEquals("'' <&lt;😀", StringTransforms.unescapeHtml("&#39;&#x27; &lt;&amp;lt;&#128512;"))
    assertEquals("&#xZZ; &#99999999; &bogus;", StringTransforms.unescapeHtml("&#xZZ; &#99999999; &bogus;"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `base64 that is not UTF-8 text throws instead of inserting replacement characters`() {
    StringTransforms.decodeBase64("test")
  }
}
