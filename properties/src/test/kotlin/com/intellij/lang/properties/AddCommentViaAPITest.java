// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.lang.properties;

import com.intellij.codeInsight.daemon.quickFix.ActionHint;
import com.intellij.codeInsight.daemon.quickFix.LightQuickFixParameterizedTestCase;
import com.intellij.lang.properties.psi.Property;
import com.intellij.openapi.application.WriteAction;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiParserFacade;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class AddCommentViaAPITest extends LightQuickFixParameterizedTestCase {

  private PsiParserFacade myParser;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myParser = PsiParserFacade.getInstance(getProject());
  }

  // Phantom-plugins: this data is java/java-tests/testData/properties in IntelliJ, copied to testData/properties
  @Override
  protected @NotNull String getTestDataPath() {
    return com.intellij.lang.properties.PropertiesTestRoot.PATH + "/testData";
  }

  @Override
  protected @NonNls String getBasePath() {
    return "/properties/addcomment";
  }

  @Override
  protected void doAction(@NotNull ActionHint actionHint, @NotNull String testFullPath, @NotNull String testName) {
    final int offset = getEditor().getCaretModel().getOffset();
    final PsiElement psiElement = getFile().findElementAt(offset);
    assert psiElement != null;
    final Property property = PsiTreeUtil.getParentOfType(psiElement, Property.class);

    final PsiComment comment = myParser.createLineCommentFromText(PropertiesFileType.INSTANCE, actionHint.getExpectedText());
    WriteAction.runAndWait(() -> property.getParent().addBefore(comment, property));

    final String expectedFilePath = getBasePath() + "/after" + testName;
    checkResultByFile("In file: " + expectedFilePath, expectedFilePath, false);
  }
}
