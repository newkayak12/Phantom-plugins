// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.util.JDOMUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element

class DockerComposeTest : BasePlatformTestCase() {
  private val compose = """
    services:
      mysql:
        image: 'mysql:latest'
        ports:
          - "3306:3306"
      redis:
        image: redis
    volumes:
      mysql-data:
  """.trimIndent()

  private fun leafAt(text: String): PsiElement = myFixture.file.findElementAt(myFixture.file.text.indexOf(text))!!

  fun testServiceAtCaretAndRunIcons() {
    myFixture.configureByText("compose.yaml", compose)
    assertEquals("", ComposeFiles.target(leafAt("services")))
    assertEquals("mysql", ComposeFiles.target(leafAt("mysql:")))
    assertEquals("mysql", ComposeFiles.target(leafAt("3306")))
    assertEquals("redis", ComposeFiles.target(leafAt("redis:")))
    assertEquals("", ComposeFiles.target(leafAt("mysql-data")))

    val leaves = PsiTreeUtil.collectElements(myFixture.file) { it.firstChild == null }
    val marked = leaves.filter { ComposeRunLineMarkerContributor().getInfo(it) != null }.map { it.text }
    assertEquals(listOf("services", "mysql", "redis"), marked)
  }

  fun testNotAComposeFile() {
    myFixture.configureByText("application.yaml", compose)
    assertNull(ComposeFiles.target(leafAt("mysql")))
    assertTrue(ComposeFiles.isComposeFile("docker-compose.override.yml"))
    assertTrue(ComposeFiles.isComposeFile("compose-advanced.yaml"))
    assertFalse(ComposeFiles.isComposeFile("composer.json"))
  }

  fun testProducerAndCommandLine() {
    myFixture.configureByText("compose.yaml", compose)
    val context = ConfigurationContext(leafAt("redis:"))
    val configuration = context.configuration?.configuration as ComposeRunConfiguration
    assertEquals(listOf(myFixture.file.virtualFile.path), configuration.composeFiles)
    assertEquals(listOf("redis"), configuration.services)
    assertEquals("compose.yaml: redis", configuration.name)
    configuration.build = true
    configuration.extraArgs = "--wait"
    assertEquals(listOf("compose", "-f", myFixture.file.virtualFile.path, "up", "--build", "--wait", "redis"), configuration.commandArgs())

    val element = Element("configuration")
    configuration.writeExternal(element)
    val copy = ComposeRunConfiguration(project, ComposeConfigurationType.getInstance().factory, "copy")
    copy.readExternal(JDOMUtil.load(JDOMUtil.write(element)))
    assertEquals(configuration.commandArgs(), copy.commandArgs())
  }

  fun testParsesDockerPs() {
    val rows = DockerCli.parseContainers(
      "abc123\tproto-mysql-1\tmysql:latest\tUp 2 hours (healthy)\trunning\t0.0.0.0:3306->3306/tcp\tproto\tmysql\n" +
      "def456\tlonely\tnginx\tExited (0) 3 days ago\texited\t\t\t\n")
    assertEquals(listOf("proto-mysql-1", "lonely"), rows.map { it.name })
    assertTrue(rows[0].running)
    assertEquals("proto", rows[0].composeProject)
    assertFalse(rows[1].running)
    assertEquals("", rows[1].composeProject)
  }

  /** Against the machine's docker, when there is one. */
  fun testRealDockerPs() {
    if (DockerCli.executable() == null) return
    val version = DockerCli.run("version", "--format", "{{.Server.Version}}")
    if (version.exitCode != 0) return // no daemon running
    DockerCli.containers().forEach { assertTrue(it.id.isNotEmpty() && it.state.isNotEmpty()) }
  }

  /**
   * Runs the configuration the gutter icon makes, against the machine's docker, in a compose project of its own
   * (never the user's), and checks that only the chosen service starts and the Docker window would list it.
   */
  fun testRealComposeRunsOnlyTheChosenService() {
    if (DockerCli.executable() == null || DockerCli.run("version", "--format", "{{.Server.Version}}").exitCode != 0) return
    val project = "phantom-compose-test"
    val dir = Files.createTempDirectory("phantom-compose")
    val composeFile = dir.resolve("compose.yaml")
    Files.writeString(composeFile, "name: $project\nservices:\n  first:\n    image: redis:latest\n  second:\n    image: redis:latest\n")
    val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(composeFile)!!
    val psi = PsiManager.getInstance(getProject()).findFile(vFile)!!
    val configuration = ConfigurationContext(psi.findElementAt(psi.text.indexOf("second:"))!!).configuration!!
    val output = StringBuffer()
    val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
    val handler = environment.state!!.execute(environment.executor, environment.runner)!!.processHandler
    handler.addProcessListener(object : ProcessAdapter() {
      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) { output.append(event.text) }
    })
    handler.startNotify()
    try {
      val started = { DockerCli.containers().filter { it.composeProject == project && it.running } }
      PlatformTestUtil.waitWithEventsDispatching({ "service did not start:\n$output" }, { started().isNotEmpty() || handler.isProcessTerminated }, 90)
      assertEquals(output.toString(), listOf("second"), started().map { it.composeService })
      println("compose started: " + started().map { "${it.name} ${it.status}" })
    }
    finally {
      handler.destroyProcess()
      handler.waitFor(20_000)
      DockerCli.run("compose", "-p", project, "down", "--remove-orphans", timeoutMs = 60_000)
      dir.toFile().deleteRecursively()
    }
  }
}
