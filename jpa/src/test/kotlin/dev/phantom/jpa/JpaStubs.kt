// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture

/** Source stubs of the JPA and Spring Data annotations, with the real targets so Kotlin places them on fields. */
object JpaStubs {
  fun add(fixture: JavaCodeInsightTestFixture) {
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    val member = "$runtime @java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.METHOD})"
    val type = "$runtime @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)"
    fixture.addClass("package jakarta.persistence; $type public @interface Entity { String name() default \"\"; }")
    fixture.addClass("package jakarta.persistence; $type public @interface MappedSuperclass {}")
    fixture.addClass("package jakarta.persistence; $type public @interface Embeddable {}")
    fixture.addClass("package jakarta.persistence; $member public @interface Id {}")
    fixture.addClass("package jakarta.persistence; $member public @interface EmbeddedId {}")
    fixture.addClass("package jakarta.persistence; $member public @interface ManyToOne {}")
    fixture.addClass("package jakarta.persistence; $member public @interface OneToMany { String mappedBy() default \"\"; Class targetEntity() default void.class; }")
    fixture.addClass("package jakarta.persistence; $member public @interface OneToOne { String mappedBy() default \"\"; }")
    fixture.addClass("package org.springframework.data.jpa.repository; $runtime public @interface Query { String value() default \"\"; boolean nativeQuery() default false; }")
    fixture.addClass("package org.springframework.data.jpa.repository; $runtime public @interface Modifying { boolean clearAutomatically() default false; }")
    fixture.addClass("package org.springframework.data.repository; public interface Repository<T, ID> {}")
    fixture.addClass("package org.springframework.data.repository; public interface CrudRepository<T, ID> extends Repository<T, ID> {}")
    fixture.addClass("package org.springframework.data.jpa.repository; public interface JpaRepository<T, ID> extends org.springframework.data.repository.CrudRepository<T, ID> {}")
  }
}
