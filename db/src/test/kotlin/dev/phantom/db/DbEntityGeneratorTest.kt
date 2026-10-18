// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import dev.phantom.db.core.exec.DbColumnInfo
import dev.phantom.db.core.ui.DbEntityGenerator
import dev.phantom.db.core.ui.DbEntityGenerator.Language
import junit.framework.TestCase

class DbEntityGeneratorTest : TestCase() {
  private val columns = listOf(
    DbColumnInfo("id", "BINARY(16)", nullable = false, primaryKey = true),
    DbColumnInfo("restaurant_id", "bigint unsigned", nullable = false, primaryKey = false),
    DbColumnInfo("startsAt", "TIME", nullable = false, primaryKey = false),
    DbColumnInfo("price", "DECIMAL(10,2)", nullable = true, primaryKey = false),
    DbColumnInfo("created_at", "timestamptz", nullable = true, primaryKey = false),
  )

  fun testNames() {
    assertEquals("TimeTable", DbEntityGenerator.className("time_table"))
    assertEquals("ReservationHistory", DbEntityGenerator.className("RESERVATION_HISTORY"))
    assertEquals("restaurantId", DbEntityGenerator.propertyName("restaurant_id"))
    assertEquals("startsAt", DbEntityGenerator.propertyName("startsAt"))
    assertEquals("_2fa", DbEntityGenerator.propertyName("2fa"))
  }

  fun testKotlin() {
    val text = DbEntityGenerator.source(Language.KOTLIN, "shop.domain", "TimeTable", "time_table",
                                        DbEntityGenerator.properties(columns, Language.KOTLIN), jakarta = true)
    assertEquals("""
      package shop.domain

      import jakarta.persistence.Column
      import jakarta.persistence.Entity
      import jakarta.persistence.Id
      import jakarta.persistence.Table
      import java.math.BigDecimal
      import java.time.LocalTime
      import java.time.OffsetDateTime

      @Entity
      @Table(name = "time_table")
      class TimeTable(
          @Id
          @Column(name = "id", nullable = false)
          var id: ByteArray,

          @Column(name = "restaurant_id", nullable = false)
          var restaurantId: Long,

          @Column(name = "startsAt", nullable = false)
          var startsAt: LocalTime,

          @Column(name = "price")
          var price: BigDecimal? = null,

          @Column(name = "created_at")
          var createdAt: OffsetDateTime? = null,
      )

    """.trimIndent(), text)
  }

  fun testJava() {
    val text = DbEntityGenerator.source(Language.JAVA, "", "Price", "price",
                                        DbEntityGenerator.properties(columns.drop(3).take(1), Language.JAVA), jakarta = false)
    assertTrue(text, text.startsWith("import java.math.BigDecimal;\nimport javax.persistence.Column;"))
    assertTrue(text, text.contains("    private BigDecimal price;\n"))
    assertTrue(text, text.contains("    protected Price() {\n    }\n"))
    assertTrue(text, text.contains("    public void setPrice(BigDecimal price) {\n        this.price = price;\n    }\n"))
  }
}
