package com.intellij.htmltools;

import java.io.File;

/** Phantom-plugins: the tests run in the plugin's folder (testData/ there), not in the IntelliJ monorepo. */
public final class HtmlToolsTestRoot {
  public static final String PATH = new File("").getAbsolutePath();

  private HtmlToolsTestRoot() { }
}
