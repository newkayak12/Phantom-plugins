// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.migrations

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.psi.PsiFileSystemItem
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MigrationMarkersTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.migrations,org.jetbrains.plugins.yaml")
    }
  }

  /** Tooltip -> target file or folder names, for the migration gutters of [path]. */
  private fun gutters(path: String): List<String> {
    myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path) ?: error("no $path"))
    return myFixture.findAllGutters().mapNotNull { g ->
      val tooltip = g.tooltipText ?: return@mapNotNull null
      if (!tooltip.startsWith("Flyway") && !tooltip.startsWith("Includes")) return@mapNotNull null
      val info = (g as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.lineMarkerInfo as? RelatedItemLineMarkerInfo<*> ?: return@mapNotNull null
      "$tooltip -> " + info.createGotoRelatedItems().mapNotNull { (it.element as? PsiFileSystemItem)?.name }.sorted().joinToString()
    }.sorted()
  }

  fun testFlywayNeighbours() {
    for (name in listOf("V1_0__init.sql", "V1_2__b.sql", "V1_10__c.sql", "sub/V2__d.sql", "R__views.sql", "U1_2__undo.sql")) {
      myFixture.addFileToProject("db/migration/$name", "SELECT 1;\n")
    }
    myFixture.addFileToProject("other/V1_5__elsewhere.sql", "SELECT 1;\n")
    assertEquals(listOf("Flyway version 1.2: previous V1_0__init.sql, next V1_10__c.sql -> V1_0__init.sql, V1_10__c.sql"), gutters("db/migration/V1_2__b.sql"))
    assertEquals(listOf("Flyway version 1.0: previous —, next V1_2__b.sql -> V1_2__b.sql"), gutters("db/migration/V1_0__init.sql"))
    assertEquals(listOf("Flyway version 2: previous V1_10__c.sql, next — -> V1_10__c.sql"), gutters("db/migration/sub/V2__d.sql"))
    assertEmpty(gutters("db/migration/R__views.sql"))
    assertEmpty(gutters("other/V1_5__elsewhere.sql"))
  }

  fun testLiquibaseIncludes() {
    myFixture.addFileToProject("db/changelog/parts/001-users.xml", "<databaseChangeLog/>")
    myFixture.addFileToProject("db/changelog/all/a.xml", "<databaseChangeLog/>")
    myFixture.addFileToProject("db/changelog/master.xml", """
      <databaseChangeLog>
        <include file="parts/001-users.xml" relativeToChangelogFile="true"/>
        <include file="parts/missing.xml" relativeToChangelogFile="true"/>
        <includeAll path="all/" relativeToChangelogFile="true"/>
        <include file="${'$'}{base}/x.xml"/>
      </databaseChangeLog>
    """.trimIndent())
    assertEquals(listOf("Includes all/ -> all", "Includes parts/001-users.xml -> 001-users.xml"), gutters("db/changelog/master.xml"))

    myFixture.addFileToProject("db/changelog/master.yaml", """
      databaseChangeLog:
        - include:
            file: db/changelog/parts/001-users.xml
        - includeAll:
            path: nowhere/
    """.trimIndent())
    assertEquals(listOf("Includes db/changelog/parts/001-users.xml -> 001-users.xml"), gutters("db/changelog/master.yaml"))
  }
}
