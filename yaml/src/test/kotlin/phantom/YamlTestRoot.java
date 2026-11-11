package org.jetbrains.yaml;

import java.io.File;

/** Phantom-plugins: the tests run in the plugin's folder (testData/ there), not in the IntelliJ monorepo. */
public final class YamlTestRoot {
  public static final String PATH = new File("").getAbsolutePath();

  private YamlTestRoot() { }
}
