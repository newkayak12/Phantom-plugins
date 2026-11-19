// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.sync

import com.intellij.settingsSync.core.FileState
import com.intellij.settingsSync.core.ServerState
import com.intellij.settingsSync.core.SettingsSnapshot
import com.intellij.settingsSync.core.SettingsSyncLocalSettings
import com.intellij.settingsSync.core.SettingsSyncPushResult
import com.intellij.settingsSync.core.UpdateResult
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.io.path.createDirectories
import dev.phantom.sync.core.GitRepoCommunicator
import dev.phantom.sync.core.GitRepoSyncAccounts
import dev.phantom.sync.core.GitSettingsRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Two machines (two working copies) sharing one bare repository, as two IDEs share a GitHub repository. */
class GitRepoCommunicatorTest : BasePlatformTestCase() {
  private lateinit var root: Path
  private lateinit var remote: String

  override fun setUp() {
    super.setUp()
    root = Files.createTempDirectory("phantom-sync")
    val bare = root.resolve("settings.git").createDirectories()
    GitSettingsRepository.run(listOf(GitSettingsRepository.gitExecutable(), "init", "-q", "--bare", bare.toString()), workDir = null)
    remote = bare.toString()
    SettingsSyncLocalSettings.getInstance().knownAndAppliedServerId = null
  }

  override fun tearDown() {
    try {
      root.toFile().deleteRecursively()
    }
    finally {
      super.tearDown()
    }
  }

  private fun machine(name: String) = GitRepoCommunicator(GitSettingsRepository(remote, root.resolve(name)))

  private fun snapshot(editorXml: String) = SettingsSnapshot(
    SettingsSnapshot.MetaInfo(Instant.now(), SettingsSnapshot.AppInfo(UUID.randomUUID(), null, "Phantom", "user", "host", "/config")),
    setOf(FileState.Modified("options/editor.xml", editorXml.toByteArray())), null, emptyMap(), emptySet())

  fun testEmptyRepositoryHasNoSettings() {
    assertEquals(ServerState.FileNotExists, machine("a").checkServerState())
    assertEquals(UpdateResult.NoFileOnServer, machine("a").receiveUpdates())
  }

  fun testPushThenReceiveOnAnotherMachine() {
    val pushed = machine("a").push(snapshot("<a/>"), force = false, expectedServerVersionId = null)
    assertInstanceOf(pushed, SettingsSyncPushResult.Success::class.java)

    val b = machine("b")
    assertEquals(ServerState.UpdateNeeded, b.checkServerState())
    val update = b.receiveUpdates() as UpdateResult.Success
    assertEquals((pushed as SettingsSyncPushResult.Success).serverVersionId, update.serverVersionId)
    val file = update.settingsSnapshot.fileStates.single() as FileState.Modified
    assertEquals("options/editor.xml", file.file)
    assertEquals("<a/>", String(file.content))

    SettingsSyncLocalSettings.getInstance().knownAndAppliedServerId = update.serverVersionId
    assertEquals(ServerState.UpToDate, b.checkServerState())
  }

  fun testStalePushIsRejectedAndForceOverwrites() {
    val a = machine("a")
    val b = machine("b")
    val first = a.push(snapshot("<one/>"), force = false, expectedServerVersionId = null) as SettingsSyncPushResult.Success
    val second = a.push(snapshot("<two/>"), force = false, expectedServerVersionId = first.serverVersionId)
    assertInstanceOf(second, SettingsSyncPushResult.Success::class.java)

    // b never received anything, or only the first version: both must not overwrite a's newer settings
    assertEquals(SettingsSyncPushResult.Rejected, b.push(snapshot("<b/>"), force = false, expectedServerVersionId = null))
    assertEquals(SettingsSyncPushResult.Rejected, b.push(snapshot("<b/>"), force = false, expectedServerVersionId = first.serverVersionId))

    assertInstanceOf(b.push(snapshot("<b/>"), force = true, expectedServerVersionId = null), SettingsSyncPushResult.Success::class.java)
    val content = ((a.receiveUpdates() as UpdateResult.Success).settingsSnapshot.fileStates.single() as FileState.Modified).content
    assertEquals("<b/>", String(content))
  }

  fun testCreateAndDeleteFiles() {
    val a = machine("a")
    a.createFile("cross-ide-sync-enabled", "")
    assertTrue(machine("b").isFileExists("cross-ide-sync-enabled"))
    a.deleteFile("cross-ide-sync-enabled")
    assertFalse(machine("b").isFileExists("cross-ide-sync-enabled"))
  }

  fun testUnreachableRepositoryIsAnError() {
    val missing = GitRepoCommunicator(GitSettingsRepository(root.resolve("missing.git").toString(), root.resolve("c")))
    assertInstanceOf(missing.checkServerState(), ServerState.Error::class.java)
  }

  fun testRepositoryNames() {
    assertEquals("me/ide-settings", GitRepoSyncAccounts.repositoryName("git@github.com:me/ide-settings.git"))
    assertEquals("me/ide-settings", GitRepoSyncAccounts.repositoryName("https://github.com/me/ide-settings.git"))
    assertEquals("me/ide-settings", GitRepoSyncAccounts.repositoryName("https://github.com/me/ide-settings/"))
    assertEquals("ssh/settings", GitRepoSyncAccounts.repositoryName("/tmp/ssh/settings.git"))
  }
}
