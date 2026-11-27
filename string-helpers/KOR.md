# Phantom String Helpers

`dev.phantom.string-helpers` · [English](./README.md)

코드처럼 쓰이는 문자열을 검사합니다. 오프라인으로 동작하며, 클래스패스에 Spring 스케줄링이나 Quartz가 있는 Java 프로젝트에서만
동작합니다. 시작할 때 아무것도 실행하지 않습니다.

## cron 식

Spring이나 Quartz가 cron 식으로 해석하는 문자열 리터럴을 그 라이브러리와 같은 규칙으로 검사합니다.

- Spring: `@Scheduled(cron = ...)`, `CronExpression.parse(...)`, `new CronTrigger(...)`. 필드 6개(초 분 시 일 월 요일)
  또는 매크로(`@daily`, `@hourly` ...). 요일은 0-7이고 0과 7이 일요일입니다.
- Quartz: `CronScheduleBuilder.cronSchedule(...)`, `new CronExpression(...)`,
  `CronTriggerFactoryBean.setCronExpression(...)`. 필드 6개와 선택적인 연도. 요일은 일요일부터 1-7이고, 일과 요일 중
  정확히 하나가 `?`여야 합니다.

오류(**Invalid cron expression** 검사)는 필드와 값을 알려 줍니다: 필드 개수, 범위를 벗어난 값, 끝이 시작보다 앞선
범위(Spring), 0인 간격, 잘못된 `L`, `W`, `#`. 플레이스홀더(`${...}`)와 비활성 표시 `-`는 건너뜁니다. Phantom for
Spring이 켜져 있으면 `@Scheduled`의 필드 개수와 알 수 없는 매크로 오류는 그 플러그인의 검사에 맡겨 두 번 표시하지
않습니다.

올바른 식의 거터에 있는 시계 아이콘에 마우스를 올리면 다음 실행 5번을 보여 줍니다. 시간대는 `@Scheduled`의 `zone`이
리터럴이면 그것, 아니면 IDE의 시간대입니다. 실행 시각은 현지 시각이며 일광 절약 시간 전환은 반영하지 않습니다.

Java만 지원하며, 상수나 이어 붙인 문자열은 따라가지 않습니다.
