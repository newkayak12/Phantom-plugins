// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package de.plushnikov.intellij.plugin.intention;

import com.intellij.modcommand.ModCommandAction;
import de.plushnikov.intellij.plugin.AbstractLombokLightCodeInsightTestCase;

public abstract class LombokIntentionActionTest extends AbstractLombokLightCodeInsightTestCase {

  public static final String TEST_DATA_INTENTION_DIRECTORY = "/testData/intention";

  @Override
  protected String getBasePath() {
    return TEST_DATA_INTENTION_DIRECTORY;
  }

  public abstract ModCommandAction getAction();

  public abstract boolean wasInvocationSuccessful();

  public void doTest() {
    doTest(true);
  }

  public void doTest(boolean intentionAvailable) {
    loadToPsiFile(getTestName(false) + ".java");
    ModCommandAction intentionAction = getAction();

    boolean isActuallyAvailable = intentionAction.getPresentation(myFixture.getActionContext()) != null;
    assertEquals("Intention \"" + intentionAction.getFamilyName() + "\" was not available at caret",
                 intentionAvailable, isActuallyAvailable);

    if (isActuallyAvailable) {
      myFixture.launchAction(intentionAction.asIntention());
    }

    assertTrue("Intention \"" + intentionAction.getFamilyName() + "\" was not properly invoked",
      wasInvocationSuccessful());
  }
}
