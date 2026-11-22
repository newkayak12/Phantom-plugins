// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.core.DerivedQuery
import dev.phantom.jpa.core.EntityAttributeUsagesLineMarkerProvider
import dev.phantom.jpa.core.JpaModel
import dev.phantom.jpa.core.JpaRepositoryLineMarkerProvider

/**
 * The attribute-usage gutter's shared repository scan: same usages as the former per-entity search, refreshed by code
 * edits, files created and deleted, and left alone by SQL and YAML edits.
 */
class JpaAttributeUsagesCacheTest : LightJavaCodeInsightFixtureTestCase() {
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
        @ManyToOne Address address;
      }
    """.trimIndent())
  }

  private fun user() = myFixture.findClass("app.User")

  private fun usages(entity: PsiClass = user()): Map<String, List<String>> =
    EntityAttributeUsagesLineMarkerProvider.usages(entity).mapValues { e -> e.value.map { it.name } }

  private fun scans() = EntityAttributeUsagesLineMarkerProvider.repositoryScans.get()

  fun testMatchesFormerComputationOnMixedRepositories() {
    myFixture.addClass("""
      package app;
      import jakarta.persistence.*;
      @Entity(name = "Customer")
      public class Client { @Id Long id; String email; @ManyToOne Address address; }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      import org.springframework.data.jpa.repository.*;
      import java.util.List;
      public interface UserRepository extends JpaRepository<User, Long> {
        List<User> findByLastnameAndAddressCityOrderByAgeDesc(String l, String c);
        List<User> findByNickname(String n);
        List<User> findByAddress_City(String c);
        @Query("SELECT x FROM app.User AS x JOIN x.address a WHERE x.firstname = ?1 AND a.city = ?2")
        List<User> byFirst(String f, String c);
        @Query("select u from User where u.age > 1")
        List<User> noAlias();
        @Query(value = "select * from user where lastname = 1", nativeQuery = true)
        List<User> nativeOne();
        default int helper() { return 1; }
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      import org.springframework.data.repository.CrudRepository;
      public interface UserReadRepository extends CrudRepository<User, Long> {
        long countByAge(int age);
        @org.springframework.data.jpa.repository.Query("select u from User u, Address a where u.address = a and a.city = ?1")
        long inCity(String c);
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      import org.springframework.data.repository.Repository;
      public interface BaseRepository<T> extends Repository<T, Long> { long countById(Long id); }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      public interface AddressRepository extends BaseRepository<Address> {
        java.util.List<Address> findByCity(String c);
        @org.springframework.data.jpa.repository.Query("select a from Address a join User u on u.address = a where u.age > 1 and a.city = 'x'")
        java.util.List<Address> joined();
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      import org.springframework.data.jpa.repository.*;
      public interface ClientRepository extends JpaRepository<Client, Long> {
        @Query("select c from Customer c where c.email = ?1 and c.address.city = ?2")
        Client byEmail(String e, String city);
        Client findByEmail(String e);
      }
    """.trimIndent())
    myFixture.addFileToProject("app/UserKtRepository.kt", """
      package app
      import org.springframework.data.jpa.repository.*
      interface UserKtRepository : JpaRepository<User, Long> {
          fun findByFirstnameOrLastname(f: String, l: String): List<User>
          @Query("select k from User k where k.age = ?1")
          fun byAge(age: Int): List<User>
      }
    """.trimIndent())
    LegacyAttributeUsages.assertSame(myFixture, "app.User", "app.Address", "app.Client")
    assertEquals(listOf("byFirst", "findByAddress_City", "findByLastnameAndAddressCityOrderByAgeDesc", "inCity"), usages()["address"])
  }

  fun testRenamingRepositoryMethodUpdatesUsages() {
    myFixture.configureByText("UserRepository.java", """
      package app;
      public interface UserRepository extends org.springframework.data.jpa.repository.JpaRepository<User, Long> {
        java.util.List<User> findByLastname(String l);
      }
    """.trimIndent())
    assertEquals(mapOf("lastname" to listOf("findByLastname")), usages())
    val document = myFixture.editor.document
    WriteCommandAction.runWriteCommandAction(project) {
      val at = document.text.indexOf("findByLastname")
      document.replaceString(at, at + "findByLastname".length, "findByFirstname")
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertEquals(mapOf("firstname" to listOf("findByFirstname")), usages())
    LegacyAttributeUsages.assertSame(myFixture, "app.User")
  }

  fun testRepositoryChangedOnDiskUpdatesUsages() {
    val file = myFixture.addFileToProject("app/UserRepository.java", """
      package app;
      public interface UserRepository extends org.springframework.data.jpa.repository.JpaRepository<User, Long> {
        java.util.List<User> findByLastname(String l);
      }
    """.trimIndent()).virtualFile
    assertEquals(mapOf("lastname" to listOf("findByLastname")), usages())
    WriteAction.run<Throwable> { VfsUtil.saveText(file, VfsUtil.loadText(file).replace("findByLastname", "findByAge")) }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertEquals(mapOf("age" to listOf("findByAge")), usages())
  }

  fun testNewRepositoryIsPickedUpAndDeletedOneDropped() {
    myFixture.addClass("""
      package app;
      public interface UserRepository extends org.springframework.data.jpa.repository.JpaRepository<User, Long> {
        java.util.List<User> findByLastname(String l);
      }
    """.trimIndent())
    assertEquals(mapOf("lastname" to listOf("findByLastname")), usages())
    val added = myFixture.addFileToProject("app/UserAgeRepository.java", """
      package app;
      public interface UserAgeRepository extends org.springframework.data.repository.Repository<User, Long> {
        long countByAge(int age);
      }
    """.trimIndent()).virtualFile
    assertEquals(mapOf("lastname" to listOf("findByLastname"), "age" to listOf("countByAge")), usages())
    WriteAction.run<Throwable> { added.delete(this) }
    assertEquals(mapOf("lastname" to listOf("findByLastname")), usages())
  }

  fun testSqlAndYamlEditsDoNotRescan() {
    myFixture.addClass("""
      package app;
      public interface UserRepository extends org.springframework.data.jpa.repository.JpaRepository<User, Long> {
        java.util.List<User> findByLastname(String l);
      }
    """.trimIndent())
    myFixture.addFileToProject("schema.sql", "create table users (id bigint);\n")
    myFixture.addFileToProject("application.yml", "spring:\n  jpa:\n    show-sql: true\n")
    val expected = mapOf("lastname" to listOf("findByLastname"))
    assertEquals(expected, usages())
    val before = scans()
    for (name in listOf("application.yml", "schema.sql")) {
      myFixture.configureFromTempProjectFile(name)
      myFixture.type("x")
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      assertEquals(expected, usages())
      assertEquals("an edit of $name must not rescan the repositories", before, scans())
    }
    // and a Java edit does, so the counter above is live
    myFixture.addClass("package app; public class Unrelated {}")
    assertEquals(expected, usages())
    assertEquals(before + 1, scans())
  }
}

/** The usages as computed before the shared scan: one inheritor search per entity. Kept here to compare against. */
object LegacyAttributeUsages {
  private val QUERY = listOf("org.springframework.data.jpa.repository.Query", "org.springframework.data.jpa.repository.NativeQuery")
  private val KEYWORDS = setOf("where", "join", "left", "inner", "order", "group", "on", "fetch", "having")

  fun compute(entity: PsiClass): Map<String, List<PsiMethod>> {
    val result = LinkedHashMap<String, MutableSet<PsiMethod>>()
    val entityName = JpaModel.entityName(entity) ?: entity.name ?: return emptyMap()
    for (repository in JpaRepositoryLineMarkerProvider.repositoriesOf(entity)) {
      for (method in repository.methods) {
        if (DerivedQuery.isDerivedQueryMethod(method)) {
          val parsed = DerivedQuery.parse(method.name) ?: continue
          for (part in parsed.parts + parsed.orderBy) {
            val head = DerivedQuery.resolvePath(entity, part.propertySource)?.firstOrNull()?.name ?: continue
            result.getOrPut(head) { LinkedHashSet() }.add(method)
          }
          continue
        }
        val query = QUERY.firstNotNullOfOrNull { method.getAnnotation(it) } ?: continue
        if (AnnotationUtil.getBooleanAttributeValue(query, "nativeQuery") == true) continue
        val text = AnnotationUtil.getStringAttributeValue(query, "value") ?: continue
        val aliases = Regex("(?i)\\b(?:from|join)\\s+(?:\\w+\\.)*${Regex.escape(entityName)}\\s+(?:as\\s+)?(\\w+)").findAll(text)
          .map { it.groupValues[1] }.filter { it.lowercase() !in KEYWORDS }.toSet()
        for (alias in aliases) {
          for (m in Regex("(?<![\\w.])${Regex.escape(alias)}\\.(\\w+)").findAll(text)) {
            val attribute = m.groupValues[1]
            if (JpaModel.attribute(entity, attribute) != null) result.getOrPut(attribute) { LinkedHashSet() }.add(method)
          }
        }
      }
    }
    return result.mapValues { it.value.sortedBy { m -> m.name } }
  }

  /** The cached usages of each entity equal the former computation, method for method. */
  fun assertSame(fixture: JavaCodeInsightTestFixture, vararg entities: String) {
    for (fqn in entities) {
      val entity = fixture.findClass(fqn)
      val legacy = compute(entity)
      val cached = EntityAttributeUsagesLineMarkerProvider.usages(entity)
      if (cached != legacy) {
        throw AssertionError("$fqn: cached ${cached.mapValues { e -> e.value.map { it.name } }} != former ${legacy.mapValues { e -> e.value.map { it.name } }}")
      }
    }
  }
}
