// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package de.plushnikov.intellij.plugin.lombokconfig;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LombokNullAnnotationLibraryCustomTest {

  @Test
  public void testParseCustom() {
    String valueCustom = "custom:type_use:some.nonNull:some.nullable";

    LombokNullAnnotationLibrary result = LombokNullAnnotationLibraryCustom.parseCustom(valueCustom);

    assertNotNull("The result should not be null", result);
    assertTrue(result.isTypeUse());
    assertEquals("some.nonNull", result.getNonNullAnnotation());
    assertEquals("some.nullable", result.getNullableAnnotation());
  }

  @Test
  public void testParseCustomWithoutType() {
    String valueCustom = "custom:some.nonNull:some.nullable";

    LombokNullAnnotationLibrary result = LombokNullAnnotationLibraryCustom.parseCustom(valueCustom);

    assertNotNull("The result should not be null", result);
    assertFalse(result.isTypeUse());
    assertEquals("some.nonNull", result.getNonNullAnnotation());
    assertEquals("some.nullable", result.getNullableAnnotation());
  }

  @Test
  public void testParseCustomWithoutCustom() {
    String valueCustom = "type_use:some.nonNull:some.nullable";

    LombokNullAnnotationLibrary result = LombokNullAnnotationLibraryCustom.parseCustom(valueCustom);

    assertNull("The result should be null as the input string doesn't start with 'custom:'", result);
  }

  @Test
  public void testParseCustomWithoutValidAnnotations() {
    String valueCustom = "custom:type_use:some%nonNull:some.1nullable";

    LombokNullAnnotationLibrary result = LombokNullAnnotationLibraryCustom.parseCustom(valueCustom);

    assertNull("The result should be null as the annotations are not valid", result);
  }
}