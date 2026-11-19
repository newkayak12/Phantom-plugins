// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
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
    fixture.addClass("package jakarta.persistence; public enum FetchType { LAZY, EAGER }")
    fixture.addClass("package jakarta.persistence; public enum CascadeType { ALL, PERSIST, MERGE, REMOVE, REFRESH, DETACH }")
    fixture.addClass("package jakarta.persistence; public enum EnumType { ORDINAL, STRING }")
    fixture.addClass("package jakarta.persistence; $member public @interface ManyToOne { FetchType fetch() default FetchType.EAGER; CascadeType[] cascade() default {}; boolean optional() default true; }")
    fixture.addClass("package jakarta.persistence; $member public @interface OneToMany { String mappedBy() default \"\"; Class targetEntity() default void.class; FetchType fetch() default FetchType.LAZY; CascadeType[] cascade() default {}; }")
    fixture.addClass("package jakarta.persistence; $member public @interface OneToOne { String mappedBy() default \"\"; FetchType fetch() default FetchType.EAGER; CascadeType[] cascade() default {}; }")
    fixture.addClass("package jakarta.persistence; $member public @interface ManyToMany { String mappedBy() default \"\"; Class targetEntity() default void.class; FetchType fetch() default FetchType.LAZY; CascadeType[] cascade() default {}; }")
    fixture.addClass("package jakarta.persistence; $member public @interface Column { String name() default \"\"; boolean nullable() default true; int length() default 255; }")
    fixture.addClass("package jakarta.persistence; $member public @interface JoinColumn { String name() default \"\"; boolean nullable() default true; }")
    fixture.addClass("package jakarta.persistence; $member public @interface Enumerated { EnumType value() default EnumType.ORDINAL; }")
    fixture.addClass("package jakarta.persistence; $member public @interface ElementCollection {}")
    fixture.addClass("package jakarta.persistence; $member public @interface Transient {}")
    fixture.addClass("package jakarta.persistence; $member public @interface GeneratedValue {}")
    fixture.addClass("package jakarta.persistence; $member public @interface Convert { Class converter() default void.class; }")
    fixture.addClass("package jakarta.persistence; $type public @interface IdClass { Class value(); }")
    fixture.addClass("package jakarta.persistence; $type public @interface Converter { boolean autoApply() default false; }")
    fixture.addClass("package jakarta.persistence; public interface AttributeConverter<X, Y> { Y convertToDatabaseColumn(X a); X convertToEntityAttribute(Y d); }")
    fixture.addClass("package jakarta.persistence; $runtime public @interface NamedAttributeNode { String value(); String subgraph() default \"\"; }")
    fixture.addClass("package jakarta.persistence; $type public @interface NamedEntityGraph { String name() default \"\"; NamedAttributeNode[] attributeNodes() default {}; }")
    fixture.addClass("package jakarta.persistence; $type public @interface NamedEntityGraphs { NamedEntityGraph[] value(); }")
    fixture.addClass("package jakarta.persistence; $type public @interface EntityListeners { Class[] value(); }")
    for (event in listOf("PrePersist", "PostPersist", "PreUpdate", "PostUpdate", "PreRemove", "PostRemove", "PostLoad")) {
      fixture.addClass("package jakarta.persistence; $runtime @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD) public @interface $event {}")
    }
    fixture.addClass("package org.springframework.data.jpa.repository; $runtime public @interface EntityGraph { String value() default \"\"; String[] attributePaths() default {}; }")
    fixture.addClass("package org.springframework.data.domain; public interface Pageable {}")
    fixture.addClass("package org.springframework.data.domain; public interface Slice<T> extends Iterable<T> {}")
    fixture.addClass("package org.springframework.data.domain; public interface Page<T> extends Slice<T> {}")
    val lombokType = "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)"
    for (name in listOf("Data", "Builder", "NoArgsConstructor", "AllArgsConstructor", "Getter", "Setter")) {
      fixture.addClass("package lombok; $lombokType public @interface $name {}")
    }
    fixture.addClass("package lombok; $lombokType public @interface ToString { String[] exclude() default {}; boolean onlyExplicitlyIncluded() default false; @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD) @interface Exclude {} }")
    fixture.addClass("package lombok; $lombokType public @interface EqualsAndHashCode { String[] exclude() default {}; boolean onlyExplicitlyIncluded() default false; @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD) @interface Exclude {} }")
    fixture.addClass("package org.springframework.data.jpa.repository; $runtime public @interface Query { String value() default \"\"; boolean nativeQuery() default false; }")
    fixture.addClass("package org.springframework.data.jpa.repository; $runtime public @interface Modifying { boolean clearAutomatically() default false; }")
    fixture.addClass("package org.springframework.data.repository; public interface Repository<T, ID> {}")
    fixture.addClass("package org.springframework.data.repository; public interface CrudRepository<T, ID> extends Repository<T, ID> {}")
    fixture.addClass("package org.springframework.data.jpa.repository; public interface JpaRepository<T, ID> extends org.springframework.data.repository.CrudRepository<T, ID> {}")
  }
}
