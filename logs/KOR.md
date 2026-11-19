# Phantom Log Console

`dev.phantom.logs` · [English](./README.md)

Run·Debug 콘솔의 로그를 읽기 쉽게 보여 줍니다.

- **JSON 로그:** JSON 로그 한 줄(logstash-logback-encoder, ECS, Log4j2 JSON 레이아웃)을
  `시간 LEVEL [thread] logger : message` 형태로 보여 주고, 뒤에 traceId와 다른 MDC 값(`userId=42`)을 붙입니다.
  스택트레이스는 아래 줄에 펼칩니다. message가 있는 JSON 객체가 아니면 출력된 그대로 둡니다.
- **레벨 색:** 앞부분에 WARN, ERROR, DEBUG가 있는 일반 텍스트 줄은 그 레벨의 콘솔 색으로 보여 줍니다
  (Settings | Editor | Color Scheme | Console Colors | Log console). 프로그램이 직접 색을 넣은 줄(ANSI)은 그대로 둡니다.
- **traceId:** traceId(`traceId=…`, JSON 필드, Spring Boot 3.2+의 `[traceId-spanId]`, Sleuth의 `[app,traceId,spanId]`)를
  링크로 표시합니다. 누르면 그 콘솔에서 같은 traceId가 있는 줄 목록이 뜨고, 고르면 그 줄로 이동합니다. 목록에서 바로 검색할 수 있습니다.

한 줄이 통째로 들어온 경우만 바꿉니다. 프로그램이 한 줄을 여러 번에 나눠 출력하면 그대로 보여 줍니다.
