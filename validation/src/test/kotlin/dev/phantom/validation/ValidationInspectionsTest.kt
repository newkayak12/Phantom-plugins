// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.validation

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.validation.core.ConstraintBoundsInspection
import dev.phantom.validation.core.ConstraintTypeInspection
import dev.phantom.validation.core.KotlinConstraintTargetInspection
import dev.phantom.validation.core.MissingValidInspection
import dev.phantom.validation.core.MissingValidatedInspection

class ValidationInspectionsTest : LightJavaCodeInsightFixtureTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.validation,org.jetbrains.kotlin")
    }
  }

  override fun getProjectDescriptor() = KotlinStdlibJdk

  override fun setUp() {
    super.setUp()
    ValidationStubs.add(myFixture)
    myFixture.enableInspections(ConstraintBoundsInspection::class.java, ConstraintTypeInspection::class.java, KotlinConstraintTargetInspection::class.java,
                                MissingValidInspection::class.java, MissingValidatedInspection::class.java)
  }

  private fun problems(): List<String> = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
    .filter { it.severity >= HighlightSeverity.WEAK_WARNING && it.inspectionToolId?.startsWith("Phantom") == true }
    .map { "${highlighted(it)}: ${it.description}" }.sorted()

  private fun highlighted(info: HighlightInfo): String = myFixture.file.text.substring(info.startOffset, info.endOffset).substringBefore('(')

  private fun descriptions(): List<String> = problems().map { it.substringAfter(": ") }

  fun testBounds() {
    myFixture.configureByText("Booking.java", """
      import jakarta.validation.constraints.*;
      import org.hibernate.validator.constraints.Length;
      import java.math.BigDecimal;

      public class Booking {
          static final int LIMIT = 5;
          @Min(10) @Max(LIMIT) int guests;
          @Min(1) @Max(10) int nights;
          @Size(min = 5, max = 2) String code;
          @Size(min = -1) String note;
          @Length(min = 1, max = 20) String title;
          @DecimalMin("1.5") @DecimalMax("1.0") BigDecimal price;
          @DecimalMin("0.0") @DecimalMax("100") BigDecimal rate;
      }
    """.trimIndent())
    assertEquals(listOf(
      "@DecimalMin: @DecimalMin(1.5) is above @DecimalMax(1.0): no value can pass both",
      "@Min: @Min(10) is above @Max(5): no value can pass both",
      "@Size: @Size min -1 is negative: validation fails with an exception",
      "@Size: @Size min 5 is above max 2: no value can pass",
    ), problems())
  }

  fun testTypes() {
    myFixture.configureByText("Profile.java", """
      import jakarta.validation.constraints.*;
      import java.math.BigDecimal;
      import java.time.LocalDate;
      import java.util.*;

      public class Profile<T> {
          @NotBlank int age;
          @Past String birthday;
          @Size(max = 3) LocalDate joined;
          @AssertTrue String accepted;
          @Email Long contact;

          @Min(1) String digits;
          @NotBlank Optional<String> nickname;
          @Past LocalDate since;
          @AssertTrue boolean adult;
          @Positive BigDecimal balance;
          @Size(max = 3) List<String> tags;
          @Size(max = 3) Map<String, String> labels;
          @Size(max = 3) int[] scores;
          @NotEmpty CharSequence bio;
          @NotNull Object anything;
          @NotBlank T generic;

          @NotBlank public Integer getLevel() { return 1; }
          public void rename(@Past String name) {}
      }
    """.trimIndent())
    assertEquals(listOf(
      "@AssertTrue: @AssertTrue does not apply to String: it validates booleans, so validation fails with UnexpectedTypeException",
      "@Email: @Email does not apply to Long: it validates text (CharSequence), so validation fails with UnexpectedTypeException",
      "@NotBlank: @NotBlank does not apply to Integer: it validates text (CharSequence), so validation fails with UnexpectedTypeException",
      "@NotBlank: @NotBlank does not apply to int: it validates text (CharSequence), so validation fails with UnexpectedTypeException",
      "@Past: @Past does not apply to String: it validates dates and times, so validation fails with UnexpectedTypeException",
      "@Past: @Past does not apply to String: it validates dates and times, so validation fails with UnexpectedTypeException",
      "@Size: @Size does not apply to LocalDate: it validates text, collections, maps and arrays, so validation fails with UnexpectedTypeException",
    ), problems())
  }

  /** The shape of a Kotlin request DTO in a Spring Boot project. */
  fun testKotlinConstructorPropertyTarget() {
    myFixture.addClass("""
      package app;
      @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)
      @jakarta.validation.Constraint(validatedBy = {})
      public @interface FieldOnly {}
    """.trimIndent())
    myFixture.configureByText("SignUpRequest.kt", """
      package app

      import jakarta.validation.Valid
      import jakarta.validation.constraints.Email
      import jakarta.validation.constraints.NotBlank
      import jakarta.validation.constraints.Size

      data class Address(@field:NotBlank val street: String)

      data class SignUpRequest(
          @NotBlank val name: String,
          @field:Email val email: String,
          @get:Size(max = 3) val code: String,
          @param:NotBlank val explicitParam: String,
          @FieldOnly val legacy: String,
          @Valid val address: Address,
          @NotBlank plainParameter: String,
      ) {
          @NotBlank val nickname: String = plainParameter
      }
    """.trimIndent())
    assertEquals(listOf(
      "@NotBlank on a constructor property goes to the constructor parameter, so @Valid does not check it; use @field:NotBlank",
      "@Valid on a constructor property goes to the constructor parameter, so @Valid does not check it; use @field:Valid",
    ), descriptions())
    // the fix puts it on the field
    val fix = myFixture.getAllQuickFixes().first { it.familyName.contains("@field:") }
    myFixture.launchAction(fix)
    assertTrue(myFixture.file.text, myFixture.file.text.contains("@field:NotBlank val name: String") || myFixture.file.text.contains("@field:Valid val address"))
  }

  fun testMissingValid() {
    myFixture.addClass("""
      package app;
      import jakarta.validation.constraints.NotBlank;
      public class Address { @NotBlank String street; }
    """.trimIndent())
    myFixture.addClass("package app; public class Note { String text; }")
    myFixture.configureByText("Order.java", """
      package app;
      import jakarta.validation.Valid;
      import jakarta.validation.constraints.NotNull;
      import java.util.*;

      public class Order {
          @NotNull String id;
          Address shipping;
          @Valid Address billing;
          List<Address> stops;
          List<@Valid Address> checkedStops;
          Map<String, Address> byName;
          Note note;
          String comment;
      }
    """.trimIndent())
    val found = problems()
    assertEquals(found.toString(), listOf("byName", "shipping", "stops"), found.map { it.substringBefore(':') })

    // Kotlin: the user's projects write @field: on constructor properties
    myFixture.configureByText("Order2.kt", """
      package app

      import jakarta.validation.Valid
      import jakarta.validation.constraints.NotNull

      class Order2(
          @field:NotNull val id: String?,
          val shipping: Address,
          @field:Valid val billing: Address,
      )
    """.trimIndent())
    assertEquals(listOf("shipping"), problems().map { it.substringBefore(':') })
  }

  fun testMissingValidated() {
    myFixture.configureByText("Services.kt", """
      package app

      import jakarta.validation.Valid
      import jakarta.validation.constraints.Min
      import org.springframework.stereotype.Service
      import org.springframework.validation.annotation.Validated
      import org.springframework.web.bind.annotation.RequestBody
      import org.springframework.web.bind.annotation.RestController

      @Service
      class ReservationService {
          fun find(@Min(1) id: Long): String = ""
      }

      @Validated
      @Service
      class CheckedService {
          fun find(@Min(1) id: Long): String = ""
      }

      @Service
      class PlainService {
          fun find(id: Long): String = ""
          private fun hidden(@Min(1) id: Long) {}
      }

      @RestController
      class ReservationController {
          fun create(@Valid @RequestBody body: String) {}
      }
    """.trimIndent())
    assertEquals(listOf("ReservationService"), problems().map { it.substringBefore(':') })
  }

  /** Common shapes that must stay quiet: Kotlin DTOs with @field:, services without constraints, value classes. */
  fun testNoFalsePositivesOnTypicalKotlinCode() {
    myFixture.addClass("package jakarta.validation.constraints; @jakarta.validation.Constraint(validatedBy = {}) public @interface PastOrPresent {}")
    myFixture.configureByText("Reservation.kt", """
      package app

      import jakarta.validation.Valid
      import jakarta.validation.constraints.*
      import java.time.LocalDate
      import java.time.LocalDateTime

      data class Guest(
          @field:NotBlank @field:Size(max = 20) val name: String,
          @field:Email val email: String?,
      )

      data class CreateReservationRequest(
          @field:NotNull @field:Min(1) val restaurantId: Long?,
          @field:Future val date: LocalDate,
          @field:Positive val people: Int,
          @field:Valid val guest: Guest,
          @field:Size(max = 5) val requests: List<String> = emptyList(),
          @field:PastOrPresent val requestedAt: LocalDateTime? = null,
      )
    """.trimIndent())
    assertEmpty(problems())
  }
}
