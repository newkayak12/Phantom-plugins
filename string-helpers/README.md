# Phantom String Helpers

`dev.phantom.string-helpers` · [한국어](./KOR.md)

Checks for strings that are code in disguise. Works offline and only in Java projects that have Spring scheduling or
Quartz on the classpath; nothing runs at startup.

## Cron expressions

String literals that Spring or Quartz parse as cron expressions are checked the way that library parses them:

- Spring: `@Scheduled(cron = ...)`, `CronExpression.parse(...)`, `new CronTrigger(...)`. Six fields (second minute
  hour day-of-month month day-of-week) or a macro (`@daily`, `@hourly` ...); day-of-week 0-7, 0 and 7 are Sunday.
- Quartz: `CronScheduleBuilder.cronSchedule(...)`, `new CronExpression(...)`,
  `CronTriggerFactoryBean.setCronExpression(...)`. Six fields and an optional year; day-of-week 1-7 from Sunday; `?`
  in exactly one of day-of-month and day-of-week.

An error (**Invalid cron expression** inspection) names the field and value: a wrong field count, a value out of range,
a range that ends before it starts (Spring), a step of zero, malformed `L`, `W` or `#`. Placeholders (`${...}`) and
the disabled marker `-` are skipped. With Phantom for Spring enabled, a wrong field count or unknown macro on
`@Scheduled` is left to its own check so it is not reported twice.

A clock icon in the gutter of a valid expression lists the next five runs when hovered, in the `zone` of
`@Scheduled` if given as a literal, otherwise the IDE's time zone. Runs are local times; a daylight-saving change is not
accounted for.

Java only; constants and concatenated strings are not followed.
