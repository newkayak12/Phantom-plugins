// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.core.DerivedJpql

/** Derived query JPQL tooltips, entity graph and attribute-usage gutters (Java and Kotlin). */
class JpaQueryMarkersTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    JpaStubs.add(myFixture)
    myFixture.addClass("""
      package app;
      @jakarta.persistence.Entity
      public class Address { @jakarta.persistence.Id Long id; String city; }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      import jakarta.persistence.*;
      @Entity
      public class User {
        @Id Long id;
        String lastname;
        String firstname;
        int age;
        boolean active;
        @ManyToOne Address address;
        String unused;
      }
    """.trimIndent())
  }

  /** Tooltip -> names of the targets. */
  private fun gutters(filter: (String) -> Boolean = { true }): List<String> = myFixture.findAllGutters().mapNotNull { g ->
    val tooltip = g.tooltipText ?: return@mapNotNull null
    if (!filter(tooltip)) return@mapNotNull null
    val info = (g as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.lineMarkerInfo as? RelatedItemLineMarkerInfo<*> ?: return@mapNotNull null
    "$tooltip -> " + info.createGotoRelatedItems().mapNotNull { (it.element as? PsiNamedElement)?.name }.sorted().joinToString()
  }.sorted()

  private fun ours(t: String) = t.startsWith("Derived query") || t.startsWith("Entity graph") || "repository method" in t

  fun testJpqlOfDerivedNames() {
    val user = myFixture.findClass("app.User")
    assertEquals("select u from User u where u.lastname = ?1 and u.age > ?2 order by u.firstname desc",
                 DerivedJpql.of(user, "findByLastnameAndAgeGreaterThanOrderByFirstnameDesc"))
    assertEquals("select u from User u where upper(u.lastname) = upper(?1) or u.address.city in ?2",
                 DerivedJpql.of(user, "findByLastnameIgnoreCaseOrAddressCityIn"))
    assertEquals("select count(u) from User u where u.active = true", DerivedJpql.of(user, "countByActiveTrue"))
    assertEquals("select u from User u where u.age between ?1 and ?2 (first 3)", DerivedJpql.of(user, "findTop3ByAgeBetween"))
    assertEquals("delete from User u where u.firstname is null", DerivedJpql.of(user, "deleteByFirstnameIsNull"))
    assertEquals("select distinct u from User u where u.lastname like ?1 (%…%)", DerivedJpql.of(user, "findDistinctByLastnameContaining"))
    assertNull("unknown property", DerivedJpql.of(user, "findByNickname"))
    assertNull("no JPQL form", DerivedJpql.of(user, "findByLastnameRegex"))
  }

  fun testRepositoryMethodGutters() {
    myFixture.configureByText("UserRepository.java", """
      package app;
      import org.springframework.data.jpa.repository.*;
      import java.util.List;
      public interface UserRepository extends JpaRepository<User, Long> {
        List<User> findByLastnameAndAgeGreaterThan(String lastname, int age);
        List<User> findByNickname(String n);
        @Query("select u from User u where u.firstname = ?1")
        List<User> byFirst(String f);
        @EntityGraph(attributePaths = {"address", "address.city"})
        List<User> findByActiveTrue();
        default int helper() { return 1; }
      }
    """.trimIndent())
    assertEquals(listOf(
      "Derived query: select u from User u where u.active = true -> active",
      "Derived query: select u from User u where u.lastname = ?1 and u.age &gt; ?2 -> age, lastname",
      "Entity graph fetches: address, address.city -> address, city",
    ), gutters(::ours))
  }

  fun testAttributeUsages() {
    myFixture.addClass("""
      package app;
      import org.springframework.data.jpa.repository.*;
      import java.util.List;
      public interface UserRepository extends JpaRepository<User, Long> {
        List<User> findByLastnameOrderByAgeAsc(String lastname);
        long countByLastname(String lastname);
        @Query("select u from User u join u.address a where u.firstname = :f and a.city = :c")
        List<User> byFirstAndCity(String f, String c);
        @Query(value = "select * from user where unused = 1", nativeQuery = true)
        List<User> nativeOne();
      }
    """.trimIndent())
    myFixture.configureFromExistingVirtualFile(myFixture.findClass("app.User").containingFile.virtualFile)
    assertEquals(listOf(
      "1 repository method uses address -> byFirstAndCity",
      "1 repository method uses age -> findByLastnameOrderByAgeAsc",
      "1 repository method uses firstname -> byFirstAndCity",
      "2 repository methods use lastname -> countByLastname, findByLastnameOrderByAgeAsc",
    ), gutters(::ours))
  }

  fun testKotlin() {
    myFixture.addFileToProject("app/Ticket.kt", """
      package app
      import jakarta.persistence.*
      @Entity
      class Ticket(
          @Id val id: Long,
          val owner: String,
          val seat: Int,
      )
    """.trimIndent())
    myFixture.addFileToProject("app/TicketRepository.kt", """
      package app
      import org.springframework.data.jpa.repository.JpaRepository
      interface TicketRepository : JpaRepository<Ticket, Long> {
          fun findByOwnerAndSeatLessThan(owner: String, seat: Int): List<Ticket>
      }
    """.trimIndent())
    myFixture.configureFromTempProjectFile("app/TicketRepository.kt")
    assertEquals(listOf("Derived query: select t from Ticket t where t.owner = ?1 and t.seat &lt; ?2 -> owner, seat"), gutters(::ours))
    myFixture.configureFromTempProjectFile("app/Ticket.kt")
    assertEquals(listOf(
      "1 repository method uses owner -> findByOwnerAndSeatLessThan",
      "1 repository method uses seat -> findByOwnerAndSeatLessThan",
    ), gutters(::ours))
  }
}
