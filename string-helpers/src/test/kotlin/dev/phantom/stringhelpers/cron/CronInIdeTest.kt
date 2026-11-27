// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.stringhelpers.cron

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.time.LocalDateTime
import java.time.ZoneId

class CronInIdeTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = JAVA_LATEST

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.springframework.scheduling.annotation; public @interface Scheduled { String cron() default \"\"; String zone() default \"\"; }")
    myFixture.addClass("package org.springframework.scheduling.support; public class CronExpression { public static CronExpression parse(String s) { return null; } }")
    myFixture.addClass("package org.quartz; public class CronExpression { public CronExpression(String s) {} }")
    myFixture.addClass("package org.quartz; public class CronScheduleBuilder { public static CronScheduleBuilder cronSchedule(String s) { return null; } }")
    myFixture.enableInspections(CronInspection())
  }

  fun testReportsInvalidExpressions() {
    myFixture.configureByText(
      "Jobs.java",
      """
      import org.springframework.scheduling.annotation.Scheduled;
      import org.quartz.CronScheduleBuilder;
      class Jobs {
        @Scheduled(cron = <error descr="Invalid cron expression: '24' is outside the hour range 0-23">"0 0 24 * * *"</error>) void late() {}
        @Scheduled(cron = <error descr="Invalid cron expression: 5 fields; Spring cron has 6: second minute hour day-of-month month day-of-week">"0 * * * *"</error>) void unix() {}
        @Scheduled(cron = "0 0 9 * * MON-FRI") void morning() {}
        @Scheduled(cron = "${'$'}{jobs.cron}") void configured() {}
        @Scheduled(cron = "-") void off() {}
        void build() {
          CronScheduleBuilder.cronSchedule(<error descr="Invalid cron expression: Quartz needs ? in exactly one of day-of-month and day-of-week">"0 0 12 * * *"</error>);
          CronScheduleBuilder.cronSchedule("0 0 12 ? * MON");
          new org.quartz.CronExpression(<error descr="Invalid cron expression: '0' is outside the day-of-week range 1-7">"0 0 12 ? * 0"</error>);
          org.springframework.scheduling.support.CronExpression.parse(<error descr="Invalid cron expression: unknown macro '@often'">"@often"</error>);        }
      }
      """.trimIndent(),
    )
    myFixture.checkHighlighting()
  }

  fun testGutterListsNextRuns() {
    myFixture.configureByText(
      "Jobs.java",
      """
      import org.springframework.scheduling.annotation.Scheduled;
      class Jobs {
        @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Seoul") void morning() {}
        @Scheduled(cron = "0 0 24 * * *") void broken() {}
        String plain = "0 0 9 * * MON-FRI";
      }
      """.trimIndent(),
    )
    val gutters = myFixture.findAllGutters()
    assertEquals(1, gutters.count { it.tooltipText?.contains("Next runs (Asia/Seoul):") == true })

    val schedule = Cron.parse("0 0 9 * * MON-FRI", CronDialect.SPRING).schedule!!
    val tooltip = CronLineMarkerProvider.tooltip(schedule, ZoneId.of("Asia/Seoul"), LocalDateTime.of(2026, 11, 27, 10, 0))
    assertEquals(
      "<html>Next runs (Asia/Seoul):<br>2026-11-30 09:00:00 Mon<br>2026-12-01 09:00:00 Tue<br>2026-12-02 09:00:00 Wed" +
        "<br>2026-12-03 09:00:00 Thu<br>2026-12-04 09:00:00 Fri</html>",
      tooltip,
    )
  }
}
