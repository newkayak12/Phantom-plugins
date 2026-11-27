// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.validation

import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture

/** Source stubs of Bean Validation and the Spring annotations, with the real targets so Kotlin places them as it would. */
object ValidationStubs {
  private const val RUNTIME = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
  private const val CONSTRAINT_TARGETS = "@java.lang.annotation.Target({java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.FIELD, " +
                                         "java.lang.annotation.ElementType.ANNOTATION_TYPE, java.lang.annotation.ElementType.CONSTRUCTOR, " +
                                         "java.lang.annotation.ElementType.PARAMETER, java.lang.annotation.ElementType.TYPE_USE})"

  fun add(fixture: JavaCodeInsightTestFixture) {
    fixture.addClass("package jakarta.validation; $RUNTIME @java.lang.annotation.Target(java.lang.annotation.ElementType.ANNOTATION_TYPE) public @interface Constraint { Class<?>[] validatedBy(); }")
    fixture.addClass("package jakarta.validation; $RUNTIME $CONSTRAINT_TARGETS public @interface Valid {}")
    val constraint = "$RUNTIME $CONSTRAINT_TARGETS @jakarta.validation.Constraint(validatedBy = {})"
    for (name in listOf("NotBlank", "NotNull", "NotEmpty", "Email", "Positive", "PositiveOrZero", "Past", "Future", "AssertTrue")) {
      fixture.addClass("package jakarta.validation.constraints; $constraint public @interface $name {}")
    }
    fixture.addClass("package jakarta.validation.constraints; $constraint public @interface Pattern { String regexp(); }")
    fixture.addClass("package jakarta.validation.constraints; $constraint public @interface Size { int min() default 0; int max() default Integer.MAX_VALUE; }")
    fixture.addClass("package jakarta.validation.constraints; $constraint public @interface Min { long value(); }")
    fixture.addClass("package jakarta.validation.constraints; $constraint public @interface Max { long value(); }")
    fixture.addClass("package jakarta.validation.constraints; $constraint public @interface DecimalMin { String value(); }")
    fixture.addClass("package jakarta.validation.constraints; $constraint public @interface DecimalMax { String value(); }")
    fixture.addClass("package org.hibernate.validator.constraints; $constraint public @interface Length { int min() default 0; int max() default Integer.MAX_VALUE; }")
    val type = "$RUNTIME @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)"
    // the stereotypes are meta-annotated as in Spring, so stereotypes of a project's own are recognised through them
    fixture.addClass("package org.springframework.stereotype; $type public @interface Component {}")
    for (name in listOf("Service", "Repository", "Controller")) {
      fixture.addClass("package org.springframework.stereotype; $type @Component public @interface $name {}")
    }
    fixture.addClass("package org.springframework.web.bind.annotation; $type @org.springframework.stereotype.Controller public @interface RestController {}")
    fixture.addClass("package org.springframework.web.bind.annotation; $RUNTIME public @interface RequestBody {}")
    fixture.addClass("package org.springframework.validation.annotation; $RUNTIME public @interface Validated {}")
  }
}
