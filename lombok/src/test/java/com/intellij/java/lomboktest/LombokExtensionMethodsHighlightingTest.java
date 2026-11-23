// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.java.lomboktest;

import com.intellij.codeInsight.daemon.LightDaemonAnalyzerTestCase;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.PlatformTestUtil;
import de.plushnikov.intellij.plugin.LombokTestUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class LombokExtensionMethodsHighlightingTest extends LightDaemonAnalyzerTestCase {

  public void testArray() { doTest(); }
  public void testNestedExtensions() { doTest(); }
  public void testAssignability() { doTest(); }
  public void testConflictingOwnMethod() { doTest(); }

  public void testExtensionMethodAutoboxing() { doTest(); }

  public void testExtensionMethodFunctional() { doTest(); }
  public void testExtensionMethodNames() { doTest(); }
  public void testExtensionMethodPlain() { doTest(); }
  public void testExtensionMethodSuppress() { doTest(); }
  public void testExtensionMethodVarargs() { doTest(); }
  public void testExtensionMethodVarargsOverloaded() { doTest(); }
  public void testExtensionMethodWrongArguments() { doTest(); }

  @NotNull
  @Override
  protected LightProjectDescriptor getProjectDescriptor() {
    return LombokTestUtil.LOMBOK_JAVA21_DESCRIPTOR;
  }

  private void doTest() {
    doTest("/testData/highlighting/extensionMethods/" + getTestName(false) + ".java", true, false);
  }

  @Override
  protected @NonNls @NotNull String getTestDataPath() {
    return de.plushnikov.intellij.plugin.LombokTestRoot.PATH;
  }
}
