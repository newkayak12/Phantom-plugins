// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.docker.compose.*
import org.jetbrains.yaml.psi.YAMLKeyValue

class ComposeChecksTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(ComposeUnknownKeysInspection::class.java, ComposeValuesInspection::class.java, ComposeRelationsInspection::class.java,
                                ComposeMissingImageInspection::class.java, ComposeUnquotedPortsInspection::class.java,
                                ComposeDuplicateContainerNameInspection::class.java)
  }

  private fun problems(): List<String> = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
    .mapNotNull(HighlightInfo::getDescription).sorted()

  private fun resource(name: String) = javaClass.getResource("/compose/$name")!!.readText()

  fun testUserComposeFilesHaveNoProblems() {
    for (name in listOf("user-compose.yaml", "user-compose-advanced.yaml")) {
      myFixture.configureByText(if (name.contains("advanced")) "compose-advanced.yaml" else "compose.yaml", resource(name))
      assertEmpty(name, problems())
    }
  }

  fun testUnknownKeysAndValues() {
    myFixture.configureByText("compose.yaml", """
      x-common: &common
        restart: unless-stopped
      services:
        db:
          <<: *common
          image: mysql
          enviroment:
            A: b
          ports: "3306:3306"
          restart: sometimes
          healthcheck:
            test: ["CMD", "true"]
            intervall: 5s
        app:
          build:
            context: .
            dockerfil: Dockerfile
          environment: FOO
          pull_policy: allways
          network_mode: hostt
          depends_on:
            db:
              condition: service_ready
      volumez: {}
    """.trimIndent())
    assertEquals(listOf(
      "'allways' is not a pull policy; use one of always, never, missing, if_not_present, build, daily, weekly or every_<duration>",
      "'dockerfil' is not a Compose key in build",
      "'enviroment' is not a Compose key in a service",
      "'environment' must be a list or a mapping",
      "'hostt' is not a network mode; use none, host, bridge, service:<name> or container:<name>",
      "'intervall' is not a Compose key in healthcheck",
      "'ports' must be a list",
      "'service_ready' is not a depends_on condition; use one of service_started, service_healthy, service_completed_successfully",
      "'sometimes' is not a restart policy; use no, always, on-failure[:retries] or unless-stopped",
      "'volumez' is not a Compose key in the top level",
    ), problems())
  }

  fun testRelationsMissingImagePortsAndContainerNames() {
    myFixture.configureByText("compose.yaml", """
      services:
        api:
          image: app
          container_name: same
          depends_on: [db, cache]
          links: ["db:database"]
          networks: [back, front]
          volumes:
            - data:/var/lib/data
            - ./conf:/etc/conf
            - logs:/logs
          ports:
            - 22:22
            - 8080:80
            - "2222:22"
        db:
          image: mysql
          container_name: same
          volumes_from: [api, "container:other"]
        worker:
          command: run
      networks:
        back:
      volumes:
        data:
    """.trimIndent())
    assertEquals(listOf(
      "'22:22' unquoted is a base-60 number in YAML 1.1 and can turn into another port; quote it",
      "Another service also uses container_name 'same'; only one of them can be created",
      "Another service also uses container_name 'same'; only one of them can be created",
      "Network 'front' is not declared under the top-level networks",
      "No service 'cache' in this or the neighbouring compose files",
      "Service 'worker' has neither image nor build",
      "Volume 'logs' is not declared under the top-level volumes",
    ), problems())
    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("- 22:22") + 3)
    myFixture.launchAction(myFixture.findSingleIntention("Quote the port mapping"))
    assertTrue(myFixture.file.text.contains("- \"22:22\""))
  }

  /** `C:\data:/data` is a Windows bind mount: neither `C` nor any other volume name. */
  fun testWindowsBindMountIsNotANamedVolume() {
    myFixture.configureByText("compose.yaml", """
      services:
        app:
          image: alpine
          volumes:
            - 'C:\data:/data'
            - D:/logs:/logs:ro
            - cache:/cache
    """.trimIndent())
    assertEquals(listOf("Volume 'cache' is not declared under the top-level volumes"), problems())
  }

  /** The definitions of a compose file and its neighbours are read once until one of the files changes. */
  fun testDefinitionsAreCached() {
    myFixture.addFileToProject("compose.override.yaml", "services:\n  extra:\n    image: alpine\n")
    val file = myFixture.configureByText("compose.yaml", "services:\n  app:\n    image: alpine\n") as org.jetbrains.yaml.psi.YAMLFile
    val first = ComposeReferences.definitions(file, ComposeReferences.Kind.SERVICE)
    assertEquals(setOf("app", "extra"), first.keys)
    assertSame(first, ComposeReferences.definitions(file, ComposeReferences.Kind.SERVICE))
  }

  fun testOverrideFileSeesServicesOfItsBase() {
    myFixture.addFileToProject("app/compose.yaml", "services:\n  db:\n    image: mysql\n  api:\n    image: app\n")
    val file = myFixture.addFileToProject("app/compose.override.yaml", "services:\n  api:\n    ports: [\"8080:80\"]\n    depends_on: [db]\n")
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    assertEmpty(problems())
  }

  fun testNavigationAndCompletion() {
    myFixture.configureByText("compose.yaml", """
      services:
        api:
          image: app
          depends_on: [d<caret>]
        db:
          image: mysql
        cache:
          image: redis
    """.trimIndent())
    // the only service starting with "d" is inserted at once; the service itself is never offered
    assertNull(myFixture.completeBasic())
    assertTrue(myFixture.file.text, myFixture.file.text.contains("depends_on: [db]"))
    val reference = myFixture.file.findReferenceAt(myFixture.file.text.indexOf("[db]") + 1)!!
    assertEquals("db", (reference.resolve() as YAMLKeyValue).keyText)

    myFixture.configureByText("compose.yaml", """
      services:
        api:
          image: app
          depends_on:
            d<caret>b:
              condition: service_started
        db:
          image: mysql
    """.trimIndent())
    val offset = myFixture.caretOffset
    val targets = ComposeGotoDeclarationHandler().getGotoDeclarationTargets(myFixture.file.findElementAt(offset), offset, myFixture.editor)!!
    val target = targets.single() as YAMLKeyValue
    assertEquals("db", target.keyText)
    assertEquals("services", (target.parent.parent as YAMLKeyValue).keyText)
  }
}
