// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.jvm

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.SqlSchema
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbExecutor
import dev.phantom.db.core.exec.DbMetadataCache
import kotlinx.coroutines.runBlocking

class JpaSchemaTest : LightJavaCodeInsightFixtureTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  override fun getProjectDescriptor() = RealJdk

  private val dataSources = ArrayList<DbDataSource>()

  override fun tearDown() {
    try {
      for (ds in dataSources) {
        DbConnectionService.getInstance(project).disconnect(ds.id)
        DbDataSourceService.getInstance(project).remove(ds.id)
      }
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  override fun setUp() {
    super.setUp()
    val p = "jakarta.persistence"
    // like the real ones: on fields and methods, so Kotlin puts them on the backing field of a constructor property
    val member = "@java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.METHOD})"
    for (name in listOf("Id", "Transient", "Version", "EmbeddedId", "Embedded")) {
      myFixture.addClass("package $p; $member public @interface $name {}")
    }
    for (name in listOf("Embeddable", "MappedSuperclass")) myFixture.addClass("package $p; public @interface $name {}")
    myFixture.addClass("package $p; public @interface Entity { String name() default \"\"; }")
    myFixture.addClass("package $p; public @interface Table { String name() default \"\"; String schema() default \"\"; String catalog() default \"\"; }")
    myFixture.addClass("package $p; $member public @interface Column { String name() default \"\"; boolean nullable() default true; String table() default \"\"; }")
    myFixture.addClass("package $p; $member public @interface JoinColumn { String name() default \"\"; String referencedColumnName() default \"\"; String table() default \"\"; }")
    myFixture.addClass("package $p; $member public @interface ManyToOne {}")
    myFixture.addClass("package $p; $member public @interface OneToOne { String mappedBy() default \"\"; }")
    myFixture.addClass("package $p; $member public @interface OneToMany { String mappedBy() default \"\"; }")
    myFixture.addClass("package $p; $member public @interface AttributeOverride { String name(); Column column(); }")
    myFixture.addClass("package $p; public enum InheritanceType { SINGLE_TABLE, JOINED, TABLE_PER_CLASS }")
    myFixture.addClass("package $p; public @interface Inheritance { InheritanceType strategy() default InheritanceType.SINGLE_TABLE; }")
  }

  /** An H2 data source with the hotel schema, its metadata loaded (highlighting in tests runs on the EDT, which never waits). */
  private fun hotel(): DbDataSource {
    val ds = DbDataSource().apply {
      name = "hotel"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:jpa_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    dataSources.add(ds)
    runBlocking {
      DbExecutor.execute(project, ds, listOf(
        "CREATE TABLE guest (id BIGINT PRIMARY KEY, full_name VARCHAR(40) NOT NULL, email VARCHAR(80), city VARCHAR(40), created_at TIMESTAMP)",
        "CREATE TABLE room_booking (id BIGINT PRIMARY KEY, guest_id BIGINT NOT NULL, nights INT, owner_ref BIGINT, version INT)",
        "CREATE TABLE party (id BIGINT PRIMARY KEY, dtype VARCHAR(20), kind VARCHAR(20), vip_level INT)",
      ))
      val snapshot = SqlSchema.getInstance(project).load(ds)!!
      for (table in snapshot.tables) DbMetadataCache.getInstance(project).columns(ds, table)
    }
    return ds
  }

  private fun findings(): List<String> =
    myFixture.doHighlighting(HighlightSeverity.INFORMATION)
      .filter { it.inspectionToolId?.startsWith("PhantomJpa") == true }
      .sortedBy { it.startOffset }
      .map { "${it.inspectionToolId!!.removePrefix("PhantomJpa")}: ${it.text}" }

  private fun enable() = myFixture.enableInspections(JpaTableUnknownInspection(), JpaColumnUnknownInspection(), JpaColumnNullabilityInspection())

  fun testSpringBootNamingInJava() {
    hotel()
    // Spring Boot present: CamelCaseToUnderscoresNamingStrategy unless the configuration says otherwise
    myFixture.addClass("package org.springframework.boot.autoconfigure; public @interface SpringBootApplication {}")
    enable()
    myFixture.addClass("""
      package app;
      import jakarta.persistence.*;
      @MappedSuperclass public abstract class Base {
        @Id Long id;
        java.time.LocalDateTime createdAt;
        String updatedBy;
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      import jakarta.persistence.*;
      @Embeddable public class Address { String city; String zipCode; }
    """.trimIndent())
    myFixture.configureByText("Guest.java", """
      package app;
      import jakarta.persistence.*;
      @Entity
      public class Guest extends Base {
        String fullName;
        @Column(name = "email") String mail;
        @Column(name = "phone_no") String phone;
        @Embedded @AttributeOverride(name = "zipCode", column = @Column(name = "city")) Address address;
        @Transient String cached;
        static int counter;
        @OneToMany(mappedBy = "guest") java.util.List<RoomBooking> bookings;
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      import jakarta.persistence.*;
      @Entity public class RoomBooking { @Id Long id; @ManyToOne Guest guest; int nights; }
    """.trimIndent())
    assertEquals(listOf("ColumnUnknown: Guest", "ColumnUnknown: \"phone_no\""), findings())

    myFixture.configureByText("Booking.java", """
      package app;
      import jakarta.persistence.*;
      @Entity @Table(name = "room_booking")
      public class Booking {
        @Id Long id;
        @ManyToOne Guest guest;
        @ManyToOne @JoinColumn(name = "owner_ref") Guest owner;
        @ManyToOne @JoinColumn(name = "payer_id") Guest payer;
        int nights;
        int version;
      }
    """.trimIndent())
    assertEquals(listOf("ColumnUnknown: \"payer_id\"", "ColumnNullability: nights", "ColumnNullability: version"), findings())

    myFixture.configureByText("Missing.java", """
      package app;
      import jakarta.persistence.*;
      @Entity @Table(name = "invoices") public class Missing { @Id Long id; String total; }
    """.trimIndent())
    assertEquals(listOf("TableUnknown: \"invoices\""), findings())

    myFixture.configureByText("Implicit.java", """
      package app;
      import jakarta.persistence.*;
      @Entity public class RoomBookingArchive { @Id Long id; }
    """.trimIndent())
    assertEquals(listOf("TableUnknown: RoomBookingArchive"), findings())
  }

  fun testInheritanceAndStandardNaming() {
    hotel()
    // no Spring Boot: Hibernate keeps names as written; the database compares names without case
    enable()
    // no package: a file configured here sits at the source root
    myFixture.configureByText("Party.java", """
      import jakarta.persistence.*;
      @Entity public class Party { @Id Long id; String kind; }
    """.trimIndent())
    assertEmpty(findings())
    myFixture.configureByText("Vip.java", """
      import jakarta.persistence.*;
      @Entity public class Vip extends Party { Integer vip_level; Integer vipLevel; }
    """.trimIndent())
    // a SINGLE_TABLE subclass maps to its root's table; it has no table of its own to report
    assertEquals(listOf("ColumnUnknown: vipLevel"), findings())
  }

  private val guestEntity = """
    package app;
    import jakarta.persistence.*;
    @Entity @Table(name = "guest") public class G { @Id Long id; String fullName; }
  """.trimIndent()

  fun testConfigurationNamingAnotherDatabase() {
    hotel()
    myFixture.addClass("package org.springframework.boot.autoconfigure; public @interface SpringBootApplication {}")
    enable()
    // the configuration names another database: the entities do not belong to this data source
    myFixture.addFileToProject("application.properties", "spring.datasource.url=jdbc:mysql://db.example:3306/shop\n")
    myFixture.configureByText("G.java", guestEntity.replace("fullName", "nope"))
    assertEmpty(findings())
  }

  fun testConfigurationSettingStandardNaming() {
    val ds = hotel()
    myFixture.addClass("package org.springframework.boot.autoconfigure; public @interface SpringBootApplication {}")
    enable()
    // an in-memory URL (here the data source's own) says nothing about which data source; naming kept as written
    myFixture.addFileToProject("persistence/hibernate.yaml", """
      spring:
        datasource:
          url: ${ds.url}
        jpa:
          hibernate:
            naming:
              physical-strategy: org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
    """.trimIndent())
    myFixture.configureByText("G.java", guestEntity)
    assertEquals(listOf("ColumnUnknown: fullName"), findings())
  }

  fun testConfigurationWithItsOwnNamingStrategy() {
    hotel()
    enable()
    // names cannot be predicted: nothing is checked
    myFixture.addFileToProject("application.properties", "spring.jpa.hibernate.naming.physical-strategy=com.acme.MyNaming\n")
    myFixture.configureByText("G.java", guestEntity.replace("fullName", "nope"))
    assertEmpty(findings())
  }

  fun testKotlinEntity() {
    hotel()
    myFixture.addClass("package org.springframework.boot.autoconfigure; public @interface SpringBootApplication {}")
    enable()
    myFixture.configureByText("Booking.kt", """
      package app
      import jakarta.persistence.*
      @Entity
      @Table(name = "room_booking")
      class Booking(
          @Id var id: Long? = null,
          @Column(name = "guest_id") var guest: Long = 0,
          var nights: Int = 0,
          var ownerRef: Long? = null,
          var discountCode: String? = null,
      ) {
          @Transient var note: String = ""
          @kotlin.jvm.Transient private var isNewEntity: Boolean = true
          lateinit var version: String
      }
    """.trimIndent())
    assertEquals(listOf("ColumnNullability: nights", "ColumnUnknown: discountCode", "ColumnNullability: version"), findings())
  }

  fun testNoDataSourceNoWork() {
    enable()
    myFixture.configureByText("X.java", """
      import jakarta.persistence.*;
      @Entity @Table(name = "whatever") public class X { @Id Long id; String nope; }
    """.trimIndent())
    assertEmpty(findings())
  }

  fun testCompletion() {
    hotel()
    myFixture.addClass("package org.springframework.boot.autoconfigure; public @interface SpringBootApplication {}")
    myFixture.configureByText("C.java", """
      import jakarta.persistence.*;
      @Entity @Table(name = "ro<caret>") public class C { @Id Long id; }
    """.trimIndent())
    myFixture.completeBasic()
    assertEquals("@Table(name = \"room_booking\")", myFixture.editor.document.text.lines()[1].substringAfter("@Entity ").substringBefore(" public"))

    myFixture.configureByText("D.kt", """
      import jakarta.persistence.*
      @Entity @Table(name = "guest") class D(@Id var id: Long? = null, @Column(name = "fu<caret>") var name: String = "")
    """.trimIndent())
    myFixture.completeBasic()
    assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("@Column(name = \"full_name\")"))

    myFixture.configureByText("E.java", """
      import jakarta.persistence.*;
      @Entity @Table(name = "guest") public class E { @Id Long id; @Column(name = "<caret>") String x; }
    """.trimIndent())
    // H2 keeps unquoted names in upper case; other contributors may add words of the file
    val items = myFixture.completeBasic()?.map { it.lookupString.lowercase() }.orEmpty()
    assertTrue(items.toString(), items.containsAll(listOf("id", "full_name", "email", "city", "created_at")))
  }

  fun testNamingRules() {
    val snake = JpaNaming(standard = false, implicitKnown = true)
    assertEquals("full_name", snake.physical("fullName"))
    assertEquals("room_booking", snake.physical("RoomBooking"))
    assertEquals("userid", snake.physical("userID"))
    assertEquals("address_zip_code", snake.physical("address.zipCode"))
    assertEquals("order2_item", snake.physical("order2Item"))
    assertEquals("Order", snake.physical("`Order`"))
    assertEquals("fullName", JpaNaming(standard = true, implicitKnown = true).physical("fullName"))
    assertEquals("mysql://localhost:3306/app", JpaSpringConfig.key("jdbc:mysql://127.0.0.1/app?useSSL=false"))
    assertEquals("mysql://localhost:3306/app", JpaSpringConfig.key("jdbc:mariadb://localhost:3306/app"))
  }
}
