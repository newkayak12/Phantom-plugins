// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.validation

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.validation.core.ConstraintDefinitionInspection

class ConstraintDefinitionTest : LightJavaCodeInsightFixtureTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.validation,org.jetbrains.kotlin")
    }
  }

  override fun getProjectDescriptor() = KotlinStdlibJdk

  override fun setUp() {
    super.setUp()
    ValidationStubs.add(myFixture)
    myFixture.addClass("package jakarta.validation; public interface Payload {}")
    myFixture.addClass("package jakarta.validation; public interface ConstraintValidatorContext {}")
    myFixture.addClass("""
      package jakarta.validation;
      public interface ConstraintValidator<A extends java.lang.annotation.Annotation, T> {
        default void initialize(A a) {}
        boolean isValid(T value, ConstraintValidatorContext context);
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      public class PhoneValidator implements jakarta.validation.ConstraintValidator<Phone, String> {
        public boolean isValid(String v, jakarta.validation.ConstraintValidatorContext c) { return true; }
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      public class EmailValidator implements jakarta.validation.ConstraintValidator<jakarta.validation.constraints.Email, String> {
        public boolean isValid(String v, jakarta.validation.ConstraintValidatorContext c) { return true; }
      }
    """.trimIndent())
    myFixture.addClass("package app; public class NotAValidator {}")
    myFixture.enableInspections(ConstraintDefinitionInspection::class.java)
  }

  private fun problems(): List<String> = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
    .filter { it.inspectionToolId == "PhantomConstraintDefinition" }
    .map { "${myFixture.file.text.substring(it.startOffset, it.endOffset)}: ${it.description}" }.sorted()

  fun testJavaDefinitions() {
    myFixture.configureByText("Defs.java", """
      package app;
      import jakarta.validation.Constraint;
      import jakarta.validation.Payload;

      @Constraint(validatedBy = PhoneValidator.class)
      @interface Phone {
        String message() default "x";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
      }

      @Constraint(validatedBy = {})
      @interface NoPayload {
        String message() default "x";
        Class<?>[] groups() default {};
      }

      @Constraint(validatedBy = {NotAValidator.class, EmailValidator.class})
      @interface Wrong {
        String message() default "x";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
      }

      @interface NotAConstraint {}
    """.trimIndent())
    assertEquals(listOf(
      "EmailValidator.class: 'EmailValidator' validates @Email, not @Wrong",
      "NoPayload: Constraint 'NoPayload' lacks payload: the validator refuses a constraint without message, groups and payload",
      "NotAValidator.class: 'NotAValidator' is not a ConstraintValidator",
    ), problems())
  }

  fun testKotlinDefinition() {
    myFixture.configureByText("Defs.kt", """
      package app
      import jakarta.validation.Constraint
      import kotlin.reflect.KClass

      @Constraint(validatedBy = [])
      annotation class Code(val message: String = "x", val groups: Array<KClass<*>> = [])

      @Constraint(validatedBy = [])
      annotation class Fine(val message: String = "x", val groups: Array<KClass<*>> = [], val payload: Array<KClass<out jakarta.validation.Payload>> = [])
    """.trimIndent())
    assertEquals(listOf(
      "Code: Constraint 'Code' lacks payload: the validator refuses a constraint without message, groups and payload",
    ), problems())
  }
}
