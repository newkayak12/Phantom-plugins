// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.migrations

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MigrationsTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.migrations,org.jetbrains.plugins.yaml")
    }
  }

  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(FlywayMigrationInspection::class.java, LiquibaseChangelogInspection::class.java)
  }

  private fun problemsIn(path: String): List<String> {
    val file = myFixture.findFileInTempDir(path) ?: error("no $path")
    myFixture.configureFromExistingVirtualFile(file)
    return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.inspectionToolId?.startsWith("Phantom") == true }.mapNotNull { it.description }
  }

  /** The names of a real project's migrations: `V1_0__init.sql` … `V1_21__…`, versions with `_` as separator. */
  fun testRealProjectNamesAreClean() {
    val names = listOf("V1_0__init.sql", "V1_1__create_user_context.sql", "V1_9__insert_tag_into_category.sql", "V1_10__create_company_context.sql",
                       "V1_16__outbox.sql", "V1_21__timetable_occupancy_hold.sql", "R__views.sql", "U1_16__undo_outbox.sql")
    for (name in names) myFixture.addFileToProject("db/migration/$name", "SELECT 1;")
    for (name in names) assertEmpty(name, problemsIn("db/migration/$name"))
  }

  fun testBadNamesAndDuplicates() {
    myFixture.addFileToProject("db/migration/V1_16__outbox.sql", "")
    myFixture.addFileToProject("db/migration/sub/V1.16__again.sql", "")
    myFixture.addFileToProject("db/migration/V2.0__a.sql", "")
    myFixture.addFileToProject("db/migration/V2__b.sql", "")
    myFixture.addFileToProject("db/migration/V1_init.sql", "")
    myFixture.addFileToProject("db/migration/v3__lower.sql", "")
    myFixture.addFileToProject("db/migration/V4__.sql", "")
    myFixture.addFileToProject("db/migration/seed-data.sql", "")
    myFixture.addFileToProject("sql/V1_init.sql", "")

    assertEquals(listOf("Version 1.16 is also used by V1.16__again.sql: Flyway refuses to migrate with two migrations of one version"),
                 problemsIn("db/migration/V1_16__outbox.sql"))
    assertEquals(listOf("Version 2 is also used by V2.0__a.sql: Flyway refuses to migrate with two migrations of one version"),
                 problemsIn("db/migration/V2__b.sql"))
    assertEquals(listOf("Flyway skips 'V1_init.sql': versioned migrations are named V<version>__<description>.sql (two underscores)"),
                 problemsIn("db/migration/V1_init.sql"))
    assertEquals(listOf("Flyway skips 'v3__lower.sql': the prefix is case-sensitive, use 'V3__lower.sql'"), problemsIn("db/migration/v3__lower.sql"))
    assertEquals(listOf("'V4__.sql' has no description after '__'"), problemsIn("db/migration/V4__.sql"))
    assertEmpty(problemsIn("db/migration/seed-data.sql"))
    assertEmpty(problemsIn("sql/V1_init.sql"))
  }

  /** `spring.flyway.locations` from an imported YAML file, the way a multi-module Spring Boot project splits its config. */
  fun testConfiguredLocations() {
    myFixture.addFileToProject("persistence/flyway/index.yaml", """
      spring:
        flyway:
          table: flyway_version_control
          locations: classpath:db/custom, classpath:db/{vendor}/extra
    """.trimIndent())
    myFixture.addFileToProject("db/custom/V1_init.sql", "")
    myFixture.addFileToProject("db/mysql/extra/V2_init.sql", "")
    assertEquals(1, problemsIn("db/custom/V1_init.sql").size)
    assertEquals(1, problemsIn("db/mysql/extra/V2_init.sql").size)
  }

  fun testLiquibaseXml() {
    myFixture.addFileToProject("db/changelog/parts/001-users.xml", "<databaseChangeLog/>")
    myFixture.addFileToProject("db/changelog/all/002.xml", "<databaseChangeLog/>")
    myFixture.addFileToProject("db/changelog/db.changelog-master.xml", """
      <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
        <changeSet id="1" author="kim"><sql>select 1</sql></changeSet>
        <changeSet id="1" author="lee"><sql>select 1</sql></changeSet>
        <changeSet id="1" author="kim"><sql>select 2</sql></changeSet>
        <include file="parts/001-users.xml" relativeToChangelogFile="true"/>
        <include file="db/changelog/parts/001-users.xml"/>
        <include file="classpath:/db/changelog/parts/001-users.xml"/>
        <include file="parts/missing.xml" relativeToChangelogFile="true"/>
        <includeAll path="all/" relativeToChangelogFile="true"/>
        <includeAll path="db/changelog/nothing/"/>
      </databaseChangeLog>
    """.trimIndent())
    assertEquals(listOf(
      "Changeset '1' by 'kim' is defined twice in this changelog: Liquibase fails on the second one",
      "Included changelog 'parts/missing.xml' does not exist",
      "includeAll path 'db/changelog/nothing/' does not exist",
    ), problemsIn("db/changelog/db.changelog-master.xml").sorted())
  }

  fun testLiquibaseYamlAndSql() {
    myFixture.addFileToProject("db/changelog/changes/001.yaml", "databaseChangeLog: []")
    myFixture.addFileToProject("db/changelog/db.changelog-master.yaml", """
      databaseChangeLog:
        - changeSet:
            id: create-users
            author: kim
            changes:
              - sql: select 1
        - changeSet:
            id: create-users
            author: kim
        - include:
            file: changes/001.yaml
            relativeToChangelogFile: true
        - include:
            file: changes/002.yaml
            relativeToChangelogFile: true
    """.trimIndent())
    assertEquals(listOf(
      "Changeset 'create-users' by 'kim' is defined twice in this changelog: Liquibase fails on the second one",
      "Included changelog 'changes/002.yaml' does not exist",
    ), problemsIn("db/changelog/db.changelog-master.yaml").sorted())

    myFixture.addFileToProject("db/changelog/changes/003.sql", """
      --liquibase formatted sql

      --changeset kim:1
      create table a (id int);
      --changeset kim:2
      create table b (id int);
      --changeset kim:1
      create table c (id int);
    """.trimIndent())
    assertEquals(listOf("Changeset '1' by 'kim' is defined twice in this changelog: Liquibase fails on the second one"),
                 problemsIn("db/changelog/changes/003.sql"))
    // an ordinary YAML file is not a changelog
    myFixture.addFileToProject("application.yaml", "spring:\n  application:\n    name: app\n")
    assertEmpty(problemsIn("application.yaml"))
  }

  /** Gradle projects keep migrations in a resource root (`src/main/resources`), which is not a Java source root. */
  fun testMigrationsInAResourceRoot() {
    val resources = myFixture.tempDirFixture.findOrCreateDir("module/src/main/resources")
    com.intellij.testFramework.PsiTestUtil.addSourceRoot(module, resources, org.jetbrains.jps.model.java.JavaResourceRootType.RESOURCE)
    try {
      myFixture.addFileToProject("module/src/main/resources/db/migration/V1_0__init.sql", "")
      myFixture.addFileToProject("module/src/main/resources/db/migration/V1_init.sql", "")
      assertEmpty(problemsIn("module/src/main/resources/db/migration/V1_0__init.sql"))
      assertEquals(listOf("Flyway skips 'V1_init.sql': versioned migrations are named V<version>__<description>.sql (two underscores)"),
                   problemsIn("module/src/main/resources/db/migration/V1_init.sql"))
    }
    finally {
      com.intellij.testFramework.PsiTestUtil.removeSourceRoot(module, resources)
    }
  }

  fun testLiquibaseUnresolvedProperty() {
    myFixture.enableInspections(LiquibaseUnresolvedPropertyInspection::class.java)
    myFixture.addFileToProject("db/changelog/master.xml", """
      <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
        <property name="schema" value="app"/>
        <include file="parts/tables.xml" relativeToChangelogFile="true"/>
      </databaseChangeLog>
    """.trimIndent())
    myFixture.addFileToProject("liquibase.properties", "parameter.tablePrefix=t_\n")
    myFixture.addFileToProject("db/changelog/parts/tables.xml", """
      <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
        <property name="idType" value="bigint"/>
        <changeSet id="1" author="kim">
          <!-- ${'$'}{inAComment} is not read -->
          <sql>create table ${'$'}{schema}.${'$'}{tablePrefix}users (id ${'$'}{idType}, owner ${'$'}{user.name}, home ${'$'}{HOME})</sql>
          <sql>insert into ${'$'}{schmea}.log values (1)</sql>
        </changeSet>
      </databaseChangeLog>
    """.trimIndent())
    assertEquals(listOf("Changelog property 'schmea' is not defined; Liquibase leaves the placeholder in the SQL as written"),
                 problemsIn("db/changelog/parts/tables.xml"))
  }

  /** `spring.liquibase.parameters` keys are known names; a YAML that only mentions both words judges nothing away. */
  fun testLiquibaseParametersFromSpringYaml() {
    myFixture.enableInspections(LiquibaseUnresolvedPropertyInspection::class.java)
    myFixture.addFileToProject("application.yaml", """
      spring:
        liquibase:
          change-log: classpath:db/changelog/master.xml
          parameters:
            schema: app
      springdoc:
        parameters: x
    """.trimIndent())
    myFixture.addFileToProject("db/changelog/master.xml", """
      <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
        <changeSet id="1" author="kim"><sql>create table ${'$'}{schema}.${'$'}{prefix}users (id int)</sql></changeSet>
      </databaseChangeLog>
    """.trimIndent())
    assertEquals(listOf("Changelog property 'prefix' is not defined; Liquibase leaves the placeholder in the SQL as written"),
                 problemsIn("db/changelog/master.xml"))
  }

  /** Migration names come from the VFS: a rename updates the list, typing in a migration does not rescan. */
  fun testMigrationListFollowsFileNamesOnly() {
    val v1 = myFixture.addFileToProject("db/migration/V1__a.sql", "select 1;")
    val first = FlywayMigrations.of(module)
    assertEquals(listOf("V1__a.sql"), first.map { it.first.name })
    edit(v1) { it.insertString(0, "-- note\n") }
    assertSame(first, FlywayMigrations.of(module))
    WriteAction.runAndWait<Throwable> { v1.virtualFile.rename(this, "V2__a.sql") }
    assertEquals(listOf("V2__a.sql"), FlywayMigrations.of(module).map { it.first.name })
  }

  /** Locations follow YAML edits and `.properties` files changed on disk; the migration list follows the locations. */
  fun testLocationsFollowConfig() {
    val yaml = myFixture.addFileToProject("application.yaml", "spring:\n  flyway:\n    locations: classpath:db/a\n")
    val properties = myFixture.addFileToProject("flyway.properties", "x=1\n")
    myFixture.addFileToProject("db/b/V1__b.sql", "")
    assertContainsElements(FlywayLocations.of(module), "db/a")
    assertEmpty(FlywayMigrations.of(module))
    edit(yaml) { it.replaceString(it.text.indexOf("db/a"), it.text.indexOf("db/a") + 4, "db/b") }
    assertContainsElements(FlywayLocations.of(module), "db/b")
    assertEquals(listOf("V1__b.sql"), FlywayMigrations.of(module).map { it.first.name })
    WriteAction.runAndWait<Throwable> { VfsUtil.saveText(properties.virtualFile, "spring.flyway.locations=classpath:db/c\n") }
    assertContainsElements(FlywayLocations.of(module), "db/c")
  }

  /** Changelog files are read from disk: a change on disk and a new changelog both count. */
  fun testLiquibasePropertiesFollowFiles() {
    val properties = myFixture.addFileToProject("liquibase.properties", "parameter.first=1\n")
    assertContainsElements(LiquibaseProperties.of(module).names, "first")
    WriteAction.runAndWait<Throwable> { VfsUtil.saveText(properties.virtualFile, "parameter.second=1\n") }
    assertContainsElements(LiquibaseProperties.of(module).names, "second")
    myFixture.addFileToProject("db/changelog/more.xml", "<databaseChangeLog><property name=\"third\" value=\"1\"/></databaseChangeLog>")
    assertContainsElements(LiquibaseProperties.of(module).names, "third")
  }

  private fun edit(file: PsiFile, change: (Document) -> Unit) {
    val documents = PsiDocumentManager.getInstance(project)
    WriteCommandAction.runWriteCommandAction(project) { change(documents.getDocument(file)!!) }
    documents.commitAllDocuments()
  }
}
