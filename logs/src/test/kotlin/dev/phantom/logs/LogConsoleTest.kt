// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.logs

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LogConsoleTest : BasePlatformTestCase() {
  fun `test a logstash line becomes readable`() {
    val line = """{"@timestamp":"2026-11-11T10:00:00.123+09:00","@version":"1","message":"order saved","logger_name":"a.b.OrderService","thread_name":"http-nio-8080-exec-1","level":"WARN","level_value":30000,"traceId":"64f1a2b3c4d5e6f7","userId":"42"}"""
    val log = LogLines.json(line)!!
    assertEquals(LogLevel.WARN, log.level)
    assertEquals("2026-11-11T10:00:00.123+09:00 WARN  [http-nio-8080-exec-1] a.b.OrderService : order saved  traceId=64f1a2b3c4d5e6f7  userId=42", log.text)
  }

  fun `test an ECS line with a stack trace`() {
    val line = """{"@timestamp":"t","log":{"level":"ERROR","logger":"x.Y"},"message":"boom","ecs":{"version":"8.11"},"process":{"thread":{"name":"main"}},"error":{"type":"java.lang.IllegalStateException","stack_trace":"java.lang.IllegalStateException: boom\n\tat x.Y.run(Y.java:3)\n"}}"""
    val log = LogLines.json(line)!!
    assertEquals(LogLevel.ERROR, log.level)
    assertEquals("t ERROR [main] x.Y : boom\njava.lang.IllegalStateException: boom\n\tat x.Y.run(Y.java:3)", log.text)
  }

  fun `test lines that are not JSON logs stay`() {
    assertNull(LogLines.json("""{"a":1}"""))
    assertNull(LogLines.json("""{"message": broken"""))
    assertNull(LogLines.json("plain"))
  }

  fun `test plain levels`() {
    assertEquals(LogLevel.WARN, LogLines.level("2026-11-11 10:00:00.000  WARN 123 --- [main] a.B : x"))
    assertEquals(LogLevel.ERROR, LogLines.level("10:00:00 ERROR c.Foo - bad"))
    assertNull(LogLines.level("loading com.ERROR.Thing"))
  }

  fun `test the input filter rewrites whole lines only`() {
    val json = """{"message":"hi","level":"ERROR"}"""
    val parts = LogInputFilter.applyFilter("$json\nok INFO fine\n{\"message\"", ConsoleViewContentType.NORMAL_OUTPUT)!!
    assertEquals(listOf("ERROR hi\n", "ok INFO fine\n{\"message\""), parts.map { it.first })
    assertEquals(ConsoleViewContentType.LOG_ERROR_OUTPUT, parts[0].second)
    assertEquals(ConsoleViewContentType.NORMAL_OUTPUT, parts[1].second)
    assertNull(LogInputFilter.applyFilter("nothing here\n", ConsoleViewContentType.NORMAL_OUTPUT))
    assertNull(LogInputFilter.applyFilter("$json\n", ConsoleViewContentType.SYSTEM_OUTPUT))
  }

  fun `test trace ids`() {
    val boot = "2026 INFO [app] [main] [803b448a0489f84084905d3093480352-3425f23bb2432450] a.B : x"
    assertEquals(listOf("803b448a0489f84084905d3093480352"), LogLines.traceIds(boot).map { boot.substring(it) })
    val sleuth = "INFO [app,64f1a2b3c4d5e6f7,1a2b3c4d5e6f7a8b] x"
    assertEquals(listOf("64f1a2b3c4d5e6f7"), LogLines.traceIds(sleuth).map { sleuth.substring(it) })
    val kv = "x traceId=64f1a2b3c4d5e6f7 y"
    assertEquals(listOf("64f1a2b3c4d5e6f7"), LogLines.traceIds(kv).map { kv.substring(it) })
    assertEquals(listOf(0, 2), TraceLines.of("a abc\nb\nc abc", "abc").map { it.number })
  }

  fun `test the trace filter links each id`() {
    val line = "x traceId=64f1a2b3c4d5e6f7\n"
    val result = TraceIdFilter.applyFilter(line, 100 + line.length)!!
    val item = result.resultItems.single()
    assertEquals(110, item.highlightStartOffset)
    assertEquals("64f1a2b3c4d5e6f7", (item.hyperlinkInfo as TraceIdLink).traceId)
  }
}
