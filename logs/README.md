# Phantom Log Console

`dev.phantom.logs` · [한국어](./KOR.md)

Readable logs in Run and Debug consoles.

- **JSON logs:** a JSON log line (logstash-logback-encoder, ECS, Log4j2 JSON layouts) is shown as
  `time LEVEL [thread] logger : message`, followed by the trace id and the other MDC values (`userId=42`), with the
  stack trace on the lines below. A line that is not a JSON object with a message is shown as printed.
- **Level colors:** a plain text line with WARN, ERROR or DEBUG near its start takes that level's console color
  (Settings | Editor | Color Scheme | Console Colors | Log console). Lines the program colors itself (ANSI) keep their colors.
- **Trace ids:** a trace id (`traceId=…`, a JSON field, the Spring Boot 3.2+ `[traceId-spanId]` pattern, Sleuth's
  `[app,traceId,spanId]`) is a link. Clicking it lists every line of that console with the same id; picking one scrolls
  to it. The list has speed search.

Only whole lines are rewritten: a line the program prints in several pieces is shown as printed.
