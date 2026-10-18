// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.kotlin

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.JpaStubs
import dev.phantom.jpa.KotlinStdlibJdk
import dev.phantom.jpa.core.JpaAttributeInspection
import dev.phantom.jpa.core.JpaEntityInspection
import dev.phantom.jpa.core.KotlinCompilerPlugins
import dev.phantom.jpa.core.KotlinJpaEntityInspection
import dev.phantom.jpa.core.RepositoryMethodInspection

/** Kotlin entities and repositories shaped like a Spring Boot + JPA project with a version catalog and allOpen. */
class KotlinJpaEntityTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = KotlinStdlibJdk

  override fun setUp() {
    super.setUp()
    JpaStubs.add(myFixture)
    myFixture.enableInspections(JpaEntityInspection::class.java, JpaAttributeInspection::class.java, KotlinJpaEntityInspection::class.java,
                                RepositoryMethodInspection::class.java)
  }

  private val catalog = """
    [versions]
    kotlin = "2.1.20"

    [plugins]
    kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
    kotlin-jpa = { id = "org.jetbrains.kotlin.plugin.jpa", version.ref = "kotlin" }
  """.trimIndent()

  private val configuredBuild = """
    plugins {
        alias(libs.plugins.kotlin.jvm)
        alias(libs.plugins.kotlin.jpa)
    }

    allOpen {
        annotation("jakarta.persistence.Entity")
        annotation("jakarta.persistence.MappedSuperclass")
        annotation("jakarta.persistence.Embeddable")
    }
  """.trimIndent()

  private val entities = """
    package app

    import jakarta.persistence.*

    @MappedSuperclass
    abstract class BaseEntity {
        @Id
        @GeneratedValue
        var id: Long? = null
            protected set
    }

    enum class Status { OPEN, CLOSED }

    @Entity
    class Restaurant(
        @Column(name = "name") var name: String,
        @Enumerated(EnumType.STRING) var status: Status,
    ) : BaseEntity() {
        @OneToMany(mappedBy = "restaurant")
        val menus: MutableList<Menu> = mutableListOf()
    }

    @Entity
    class Menu(
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "restaurant_id")
        var restaurant: Restaurant,
        var price: Long,
    ) : BaseEntity()
  """.trimIndent()

  /** Our problems only: Kotlin compiler diagnostics are reported as "[CODE] …". */
  private fun problems(severity: HighlightSeverity = HighlightSeverity.WEAK_WARNING): List<String> =
    myFixture.doHighlighting(severity).mapNotNull { it.description }.filterNot { it.startsWith("[") }.sorted()

  fun testCatalogAliasesAndAllOpenAreRead() {
    val config = KotlinCompilerPlugins.parse(listOf(configuredBuild), KotlinCompilerPlugins.catalogPlugins(catalog))
    assertTrue(config.noArgForEntities)
    assertTrue(config.opens(listOf("jakarta.persistence.Entity")))
    // a root script only declares versions with `apply false`
    val root = KotlinCompilerPlugins.parse(listOf("plugins {\n  alias(libs.plugins.kotlin.jpa) apply false\n}"), KotlinCompilerPlugins.catalogPlugins(catalog))
    assertFalse(root.noArgForEntities)
    assertTrue(KotlinCompilerPlugins.parse(listOf("plugins { kotlin(\"plugin.jpa\") version \"2.1.20\" }"), emptyMap()).noArgForEntities)
  }

  fun testConfiguredProjectIsClean() {
    myFixture.addFileToProject("build.gradle.kts", configuredBuild)
    myFixture.addFileToProject("gradle/libs.versions.toml", catalog)
    myFixture.configureByText("Entities.kt", entities)
    assertEmpty(problems())
  }

  fun testWithoutCompilerPlugins() {
    myFixture.addFileToProject("build.gradle.kts", "plugins {\n    kotlin(\"jvm\") version \"2.1.20\"\n}")
    myFixture.configureByText("Entities.kt", """
      package app

      import jakarta.persistence.*

      @Entity
      data class Reservation(
          @Id val id: Long,
          val guests: Int,
          @OneToMany(mappedBy = "reservation") var lines: List<Line> = listOf(),
      )

      @Entity
      class Line(@Id var id: Long, @ManyToOne var reservation: Reservation)
    """.trimIndent())
    assertEquals(listOf(
      "'guests' is a val, but Hibernate assigns it when loading and refreshing",
      "Entity 'Line' and its members are final, so Hibernate cannot proxy it for lazy loading. Add allOpen { annotation(\"jakarta.persistence.Entity\") } to the build",
      "Entity 'Line' has no constructor without parameters. Apply the kotlin(\"plugin.jpa\") Gradle plugin",
      "Entity 'Reservation' and its members are final, so Hibernate cannot proxy it for lazy loading. Add allOpen { annotation(\"jakarta.persistence.Entity\") } to the build",
      "Entity 'Reservation' has no constructor without parameters. Apply the kotlin(\"plugin.jpa\") Gradle plugin",
      "Entity 'Reservation' is a data class: equals, hashCode and toString read every property, lazy associations included",
      "Hibernate changes association collections in place; declare a MutableList",
    ), problems())

    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("class Line") + "class ".length)
    myFixture.launchAction(myFixture.findSingleIntention("Make class open"))
    assertTrue(myFixture.file.text.contains("@Entity\nopen class Line("))
    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("val guests"))
    myFixture.launchAction(myFixture.findSingleIntention("Change to var"))
    assertTrue(myFixture.file.text.contains("var guests: Int"))
  }

  fun testNoBuildScriptKeepsPluginChecksSilent() {
    myFixture.configureByText("Plain.kt", """
      package app

      import jakarta.persistence.*

      @Entity
      class Plain(@Id var id: Long)
    """.trimIndent())
    assertEmpty(problems())
  }

  fun testRedundantOpenAndConstructor() {
    myFixture.addFileToProject("build.gradle.kts", configuredBuild)
    myFixture.addFileToProject("gradle/libs.versions.toml", catalog)
    myFixture.configureByText("Coupon.kt", """
      package app

      import jakarta.persistence.*

      @Entity
      open class Coupon(@Id var id: Long, var code: String) {
          constructor() : this(0, "")
      }
    """.trimIndent())
    assertEquals(listOf("Redundant constructor: the kotlin-jpa plugin generates one without parameters",
                        "Redundant open: the allOpen compiler plugin opens this class"), problems())
    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("open class"))
    myFixture.launchAction(myFixture.findSingleIntention("Remove redundant open"))
    assertTrue(myFixture.file.text.contains("@Entity\nclass Coupon("))
  }

  fun testAttributeMappings() {
    myFixture.addFileToProject("build.gradle.kts", configuredBuild)
    myFixture.addFileToProject("gradle/libs.versions.toml", catalog)
    myFixture.addFileToProject("app/Entities.kt", entities)
    myFixture.configureByText("Order.kt", """
      package app

      import jakarta.persistence.CascadeType
      import jakarta.persistence.Column
      import jakarta.persistence.Entity
      import jakarta.persistence.FetchType
      import jakarta.persistence.ManyToMany
      import jakarta.persistence.ManyToOne
      import jakarta.persistence.OneToOne

      @Entity
      class Order(
          @ManyToOne @Column(name = "restaurant_id") var restaurant: Restaurant,
          var status: Status,
          var tags: MutableList<String>,
          var menu: Menu,
      ) : BaseEntity() {
          @ManyToMany(cascade = [CascadeType.ALL])
          var menus: MutableSet<Menu> = mutableSetOf()

          @OneToOne(mappedBy = "order", fetch = FetchType.LAZY)
          var receipt: Receipt? = null
      }

      @Entity
      class Receipt(@OneToOne var order: Order) : BaseEntity()
    """.trimIndent())
    assertEquals(listOf(
      "'menu' has entity type 'Menu' but no @ManyToOne or @OneToOne",
      "'status' is stored as the enum's ordinal; reordering the constants changes what stored rows mean",
      "'tags' is a collection without @OneToMany, @ManyToMany, @ElementCollection or a converter",
      "@Column on a @ManyToOne association: the join column is set with @JoinColumn",
      "Cascading REMOVE over @ManyToMany deletes rows that other owners still refer to",
      "LAZY has no effect on the mappedBy side of a @OneToOne: Hibernate loads it eagerly to know whether it is null",
    ), problems())

    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("@Column"))
    myFixture.launchAction(myFixture.findSingleIntention("Replace @Column with @JoinColumn"))
    assertTrue(myFixture.file.text.contains("@ManyToOne @JoinColumn(name = \"restaurant_id\")"))
    assertTrue(myFixture.file.text.contains("import jakarta.persistence.Column\nimport jakarta.persistence.JoinColumn\n"))
  }

  fun testRepositorySignatures() {
    myFixture.addFileToProject("app/Entities.kt", entities)
    myFixture.configureByText("MenuRepository.kt", """
      package app

      import org.springframework.data.domain.Page
      import org.springframework.data.domain.Pageable
      import org.springframework.data.jpa.repository.JpaRepository

      interface MenuRepository : JpaRepository<Menu, Long> {
          fun findByPrice(price: Long): List<Menu>
          fun findAllByRestaurant(restaurant: Restaurant, pageable: Pageable): Page<Menu>
          fun findByRestaurantName(name: String): Menu?
          fun countByPrice(price: Long): Long
          fun existsByPrice(price: Long): Boolean
          fun findByIdIn(ids: Collection<Long>): List<Menu>

          fun findAllByPrice(price: Long): Page<Menu>
          fun countByRestaurant(restaurant: Restaurant): String
          fun existsByRestaurant(restaurant: Restaurant): Int
          fun findFirstByPrice(price: Long): Restaurant?
          fun findByRestaurantId(id: Int): List<Menu>
      }
    """.trimIndent())
    assertEquals(listOf(
      "Argument 'id' of type 'int' does not match property 'restaurantId' of type 'Long'",
      "Returning Page needs a Pageable parameter",
      "Returns entity 'Restaurant' but the repository manages 'Menu'",
      "count query returns a number, not 'String'",
      "exists query returns a boolean, not 'int'",
    ), problems())
  }
}
