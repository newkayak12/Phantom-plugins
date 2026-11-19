package com.intellij.lang.properties;

import java.io.File;

/** Phantom-plugins: the tests run in the plugin's folder (testData/ there), not in the IntelliJ monorepo. */
public final class PropertiesTestRoot {
  public static final String PATH = new File("").getAbsolutePath().replace(File.separatorChar, '/');

  private PropertiesTestRoot() { }
}
