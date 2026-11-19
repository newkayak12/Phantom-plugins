// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.terminal.tests.block

import com.intellij.terminal.completion.spec.ShellCommandSpec
import com.intellij.terminal.completion.spec.ShellName
import com.intellij.terminal.completion.spec.ShellRuntimeContext
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.terminal.completion.spec.ShellCommand
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.plugins.terminal.block.completion.ShellCommandSpecsManagerImpl
import org.jetbrains.plugins.terminal.block.completion.spec.ShellCommandSpecsProvider
import org.jetbrains.plugins.terminal.block.completion.spec.impl.ShellRuntimeContextImpl
import org.jetbrains.plugins.terminal.block.completion.spec.json.ShellDefaultCommandSpecsProvider
import org.jetbrains.plugins.terminal.testFramework.completion.impl.DummyShellCommandExecutor
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.toPath

/** The command specs bundled in completionSpecs, written by terminal/tools/fig-specs from the Fig specs */
@RunWith(JUnit4::class)
internal class BundledCommandSpecsTest : BasePlatformTestCase() {
  private val manager: ShellCommandSpecsManagerImpl
    get() = ShellCommandSpecsManagerImpl.getInstance()

  private val context: ShellRuntimeContext = ShellRuntimeContextImpl(
    currentDirectory = "",
    envVariables = emptyMap(),
    commandTokens = listOf(""),
    definedShellName = ShellName("dummy"),
    generatorCommandsRunner = DummyShellCommandExecutor,
  )

  override fun setUp() {
    super.setUp()
    ExtensionTestUtil.maskExtensions(ShellCommandSpecsProvider.EP_NAME, listOf(ShellDefaultCommandSpecsProvider()), testRootDisposable)
  }

  @Test
  fun `every bundled spec file parses`() {
    val json = Json { ignoreUnknownKeys = true }
    val root = javaClass.classLoader.getResource("completionSpecs/all_commands.json")!!.toURI()
    val files = withSpecsFolder(root) { folder ->
      Files.walk(folder).use { paths -> paths.filter { it.extension == "json" }.toList() }.map { it to it.readText() }
    }
    val commands = json.decodeFromString<List<ShellCommand>>(files.single { it.first.fileName.toString() == "all_commands.json" }.second)
    assertTrue("Too few commands: ${commands.size}", commands.size > 500)
    for ((file, text) in files) {
      if (file.fileName.toString() != "all_commands.json") {
        val command = json.decodeFromString<ShellCommand>(text)
        assertTrue("No names in $file", command.names.isNotEmpty())
      }
    }
  }

  @Test
  fun `git che suggests checkout and cherry-pick`() = runBlocking {
    assertSubcommandsStartingWith("git", "che", "checkout", "cherry-pick")
    val checkout = subcommand("git", "checkout")
    assertTrue("git checkout has no options", checkout.allOptionsGenerator.generate(context).any { it.name == "--force" })
  }

  @Test
  fun `docker ru suggests run`() = runBlocking {
    assertSubcommandsStartingWith("docker", "ru", "run")
  }

  @Test
  fun `npm i suggests install`() = runBlocking {
    assertSubcommandsStartingWith("npm", "i", "install", "i", "init")
  }

  @Test
  fun `aws s3 is loaded from its own file`() = runBlocking {
    val s3 = manager.getFullCommandSpec(subcommand("aws", "s3"))
    val names = s3.subcommandsGenerator.generate(context).map { it.name }
    assertTrue("aws s3 subcommands: $names", names.containsAll(listOf("cp", "ls", "sync")))
  }

  private suspend fun subcommand(command: String, name: String): ShellCommandSpec {
    val spec = manager.getCommandSpec(command) ?: error("No spec for $command")
    return spec.subcommandsGenerator.generate(context).first { it.name == name }
  }

  private suspend fun assertSubcommandsStartingWith(command: String, prefix: String, vararg expected: String) {
    val spec = manager.getCommandSpec(command)
    assertNotNull("No spec for $command", spec)
    val names = spec!!.subcommandsGenerator.generate(context).map { it.name }.filter { it.startsWith(prefix) }
    assertTrue("$command $prefix: $names", names.containsAll(expected.toList()))
  }

  private fun <T> withSpecsFolder(allCommands: java.net.URI, action: (Path) -> T): T {
    if (allCommands.scheme == "jar") {
      FileSystems.newFileSystem(allCommands, emptyMap<String, Any>()).use { return action(it.getPath("/completionSpecs")) }
    }
    return action(allCommands.toPath().parent)
  }
}
