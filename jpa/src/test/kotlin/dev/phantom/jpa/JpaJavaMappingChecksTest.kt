// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.core.JpaAttributeInspection
import dev.phantom.jpa.core.JpaEntityInspection
import dev.phantom.jpa.core.LombokEntityInspection
import dev.phantom.jpa.core.RepositoryMethodInspection

/** Java entities: startup errors, attribute warnings, Lombok and repository signatures. */
class JpaJavaMappingChecksTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    JpaStubs.add(myFixture)
    myFixture.enableInspections(JpaEntityInspection::class.java, JpaAttributeInspection::class.java, LombokEntityInspection::class.java,
                                RepositoryMethodInspection::class.java)
  }

  private fun problems(): List<String> = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).mapNotNull { it.description }.sorted()

  fun testStartupErrors() {
    myFixture.addClass("package app; @jakarta.persistence.Entity public class Owner { @jakarta.persistence.Id Long id; }")
    myFixture.addClass("package app; @jakarta.persistence.Entity public class Pet { @jakarta.persistence.Id Long id; @jakarta.persistence.ManyToOne Owner owner; }")
    myFixture.addClass("package app; public class Money { }")
    myFixture.configureByText("Clinic.java", """
      package app;
      import jakarta.persistence.*;
      import java.util.List;

      @Entity class Visit {
        @Id Long a;
        @Id Long b;
        public Visit(Long a) { this.a = a; }
        @OneToMany(mappedBy = "owner") List<Pet> pets;
        @OneToMany List rawItems;
        @Convert(converter = Money.class) String price;
      }

      @Entity @IdClass(VisitKey.class) class Keyed { @Id Long a; @Id Long b; }
      class VisitKey { Long a; Long b; }

      @Converter class NotAConverter { }
      @Converter class GoodConverter implements AttributeConverter<String, String> {
        public String convertToDatabaseColumn(String a) { return a; }
        public String convertToEntityAttribute(String d) { return d; }
      }
    """.trimIndent())
    assertEquals(listOf(
      "'Money' does not implement AttributeConverter",
      "'NotAConverter' does not implement AttributeConverter",
      "Entity 'Visit' has no constructor without parameters; Hibernate needs one to create instances",
      "Entity 'Visit' has several @Id attributes but no @IdClass",
      "Raw collection 'rawItems': give it an element type or set targetEntity",
      "mappedBy 'owner' in 'Pet' refers to 'Owner', not to 'Visit'",
    ), problems())
  }

  fun testLombok() {
    myFixture.addClass("package app; @jakarta.persistence.Entity public class Shop { @jakarta.persistence.Id Long id; }")
    myFixture.configureByText("Item.java", """
      package app;
      import jakarta.persistence.*;
      import java.util.List;

      @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
      @Entity class Item {
        @Id Long id;
        @ManyToOne(fetch = FetchType.LAZY) Shop shop;
        @ManyToOne Shop eagerShop;
      }

      @lombok.ToString(exclude = "items") @lombok.Getter
      @Entity class Basket {
        @Id Long id;
        @OneToMany List<Item> items;
        @ManyToMany @lombok.EqualsAndHashCode.Exclude List<Item> others;
      }
    """.trimIndent())
    assertEquals(listOf("@Builder on an entity also needs @AllArgsConstructor",
                        "@Data reads lazy association(s) shop; exclude them",
                        "@ToString reads lazy association(s) others; exclude them"), problems())
    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("@lombok.Builder") + 2)
    myFixture.launchAction(myFixture.availableIntentions.first { it.text.contains("AllArgsConstructor") })
    assertTrue(myFixture.file.text.contains("AllArgsConstructor"))
  }

  fun testJavaRepositoryAndColumnFix() {
    myFixture.addClass("package app; @jakarta.persistence.Entity public class Shop { @jakarta.persistence.Id Long id; String name; }")
    myFixture.configureByText("ShopRepository.java", """
      package app;
      import org.springframework.data.domain.*;
      import java.util.Optional;

      interface ShopRepository extends org.springframework.data.jpa.repository.JpaRepository<Shop, Long> {
        Optional<Shop> findByName(String name);
        Slice<Shop> findByNameStartingWith(String prefix);
        long countByName(String name);
        java.util.List<Shop> findById(int id);
      }
    """.trimIndent())
    assertEquals(listOf("Argument 'id' of type 'int' does not match property 'id' of type 'Long'",
                        "Returning Slice needs a Pageable parameter"), problems())
  }
}
