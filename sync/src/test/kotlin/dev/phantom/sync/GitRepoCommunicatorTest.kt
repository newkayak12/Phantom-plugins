// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.sync

import com.intellij.settingsSync.core.FileState
import com.intellij.settingsSync.core.InvalidVersionIdException
import com.intellij.settingsSync.core.ServerState
import com.intellij.settingsSync.core.SettingsSnapshot
import com.intellij.settingsSync.core.SettingsSyncLocalSettings
import com.intellij.settingsSync.core.SettingsSyncPushResult
import com.intellij.settingsSync.core.UpdateResult
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.io.path.createDirectories
import dev.phantom.sync.core.GitRepoAuthService
import dev.phantom.sync.core.GitRepoCommunicator
import dev.phantom.sync.core.GitRepoSyncAccounts
import dev.phantom.sync.core.GitSettingsRepository
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

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

  fun testPushBetweenVersionCheckAndWriteIsNotOverwritten() {
    val a = GitSettingsRepository(remote, root.resolve("a"))
    val b = GitSettingsRepository(remote, root.resolve("b"))
    val first = a.write("settings.zip", null, "<one/>".toByteArray())

    // b pushes after a checked that the remote is at `first`, before a commits
    a.afterVersionCheck = {
      a.afterVersionCheck = {}
      b.write("settings.zip", first, "<b/>".toByteArray())
    }
    assertInstanceOf(runCatching { a.write("settings.zip", first, "<a/>".toByteArray()) }.exceptionOrNull(), InvalidVersionIdException::class.java)
    val (content, version) = a.read("settings.zip")
    assertEquals("<b/>", String(content!!))
    assertFalse(version == first)

    // the retry sees b's version: still rejected with the old one, accepted once a has b's version
    assertInstanceOf(runCatching { a.write("settings.zip", first, "<a/>".toByteArray()) }.exceptionOrNull(), InvalidVersionIdException::class.java)
    val third = a.write("settings.zip", version, "<a/>".toByteArray())
    assertEquals("<a/>" to third, b.read("settings.zip").let { String(it.first!!) to it.second })
  }

  fun testUnchangedRemoteIsNotFetchedAgain() {
    val a = GitSettingsRepository(remote, root.resolve("a"))
    val b = GitSettingsRepository(remote, root.resolve("b"))
    val first = a.write("settings.zip", null, "<one/>".toByteArray())
    var fetches = 0
    b.beforeFetch = { fetches++ }

    // a check followed by a read, as the sync engine does: one fetch, the read only asks the remote for its branch head
    assertEquals(first, b.latestVersion("settings.zip"))
    assertEquals("<one/>" to first, b.read("settings.zip").let { String(it.first!!) to it.second })
    assertEquals(1, fetches)

    // another machine pushes: the next check fetches its commit
    val second = a.write("settings.zip", first, "<two/>".toByteArray())
    assertEquals("<two/>" to second, b.read("settings.zip").let { String(it.first!!) to it.second })
    assertEquals(2, fetches)
  }

  fun testCredentialsAreMasked() {
    assertEquals("https://user:***@github.com/me/s.git", GitSettingsRepository.maskCredentials("https://user:ghp_secret@github.com/me/s.git"))
    assertEquals("https://***@github.com/me/s.git", GitSettingsRepository.maskCredentials("https://ghp_secret@github.com/me/s.git"))
    assertEquals("fatal: unable to access 'https://x-access-token:***@host/s.git/': 403",
                 GitSettingsRepository.maskCredentials("fatal: unable to access 'https://x-access-token:ghs_secret@host/s.git/': 403"))
    assertEquals("ssh://git@github.com/me/s.git", GitSettingsRepository.maskCredentials("ssh://git@github.com/me/s.git"))
    assertEquals("git@github.com:me/s.git", GitSettingsRepository.maskCredentials("git@github.com:me/s.git"))
  }

  fun testCredentialsStayOutOfLogAndErrors() {
    val token = "ghp_FAKE0123456789token"
    val url = "https://x-access-token:$token@127.0.0.1:9/me/settings.git"
    val logged = mutableListOf<String>()
    val handler = object : Handler() {
      override fun publish(record: LogRecord) {
        logged += "${record.message} ${record.thrown?.message}"
      }
      override fun flush() {}
      override fun close() {}
    }
    val log = Logger.getLogger("#" + GitSettingsRepository::class.java.name)
    val level = log.level
    log.level = Level.ALL
    log.addHandler(handler)
    try {
      // a git that quotes the URL with its credentials in stderr, as older git versions do
      val fake = runCatching {
        GitSettingsRepository.run(listOf("sh", "-c", "echo \"fatal: unable to access '$0/': failed\" >&2; exit 128", url), workDir = null)
      }.exceptionOrNull()
      assertInstanceOf(fake, IOException::class.java)
      // the real connection check against an unreachable host, and the message the user sees
      val repository = GitSettingsRepository(url, root.resolve("c"))
      val access = runCatching { repository.checkAccess() }.exceptionOrNull()
      assertInstanceOf(access, IOException::class.java)
      val shown = (GitRepoCommunicator(repository).checkServerState() as ServerState.Error).message

      assertTrue(logged.isNotEmpty())
      for (text in logged + listOf(fake!!.message!!, access!!.message!!, shown)) {
        assertFalse(text, token in text)
      }
      assertTrue(fake.message!!, "x-access-token:***@127.0.0.1" in fake.message!!)
    }
    finally {
      log.removeHandler(handler)
      log.level = level
    }
  }

  fun testHangingCommandIsKilledAtTheTimeout() {
    val marker = "phantom-sync-timeout-" + UUID.randomUUID()
    // a child that keeps stdout open and never ends, under a parent shell, like ssh under git
    val (error, elapsed) = measureTimedValue {
      runCatching {
        GitSettingsRepository.run(listOf("sh", "-c", "sh -c 'sleep 60; :' $marker; :"), workDir = null, timeout = 1.seconds)
      }.exceptionOrNull()
    }
    assertInstanceOf(error, IOException::class.java)
    assertTrue(error!!.message!!, "timed out" in error.message!!)
    assertTrue("took $elapsed", elapsed < 10.seconds)
    Thread.sleep(500)
    val left = ProcessHandle.allProcesses().filter { it.info().commandLine().orElse("").contains(marker) }.toList()
    assertTrue("still running: $left", left.isEmpty())
  }

  fun testAccountNameHidesCredentials() {
    val url = "https://x-access-token:ghp_FAKE0123456789token@github.com/me/ide-settings.git"
    GitRepoSyncAccounts.getInstance().add(url)
    try {
      val user = GitRepoAuthService().getUserData(url)!!
      assertEquals(url, user.id)
      assertEquals("https://x-access-token:***@github.com/me/ide-settings.git", user.printableName)
    }
    finally {
      GitRepoSyncAccounts.getInstance().remove(url)
    }
  }

  fun testChildHoldingTheOutputDoesNotBlockAfterExit() {
    val marker = "phantom-sync-output-" + UUID.randomUUID()
    try {
      // the shell exits at once, but its backgrounded child inherited stdout and keeps it open
      val (result, elapsed) = measureTimedValue {
        GitSettingsRepository.run(listOf("sh", "-c", "sh -c 'sleep 60; :' $marker & echo done"), workDir = null)
      }
      assertEquals("done", result.text())
      assertTrue("took $elapsed", elapsed < 10.seconds)
    }
    finally {
      ProcessHandle.allProcesses().filter { it.info().commandLine().orElse("").contains(marker) }.forEach { shell ->
        shell.descendants().forEach { it.destroyForcibly() }
        shell.destroyForcibly()
      }
    }
  }

  fun testInstancesOnTheSameWorkDirShareOneLock() {
    val dir = root.resolve("a")
    val first = GitSettingsRepository(remote, dir)
    val second = GitSettingsRepository(remote, dir)
    var finishedDuringWrite = true
    first.afterVersionCheck = {
      // another instance on the same working copy must wait until this write is done
      val other = kotlin.concurrent.thread { second.latestVersion("settings.zip") }
      other.join(500)
      finishedDuringWrite = !other.isAlive
      first.afterVersionCheck = {}
    }
    val version = first.write("settings.zip", null, "<one/>".toByteArray())
    assertFalse(finishedDuringWrite)
    assertEquals(version, second.latestVersion("settings.zip"))
  }

  fun testWorkingCopyKeepsOnlyTheLatestRemoteCommit() {
    val a = GitSettingsRepository(remote, root.resolve("a"))
    val b = GitSettingsRepository(remote, root.resolve("b"))
    var version: String? = null
    repeat(3) { version = a.write("settings.zip", version, "<v$it/>".toByteArray()) }
    assertEquals("<v2/>" to version, b.read("settings.zip").let { String(it.first!!) to it.second })
    val count = GitSettingsRepository.run(listOf(GitSettingsRepository.gitExecutable(), "rev-list", "--count", "refs/remotes/origin/main"),
                                          root.resolve("b")).text()
    assertEquals("1", count)
  }

  fun testUrlsWithCredentialsAreDetected() {
    assertTrue(GitSettingsRepository.hasCredentials("https://user:ghp_secret@github.com/me/s.git"))
    assertTrue(GitSettingsRepository.hasCredentials("https://ghp_secret@github.com/me/s.git"))
    assertFalse(GitSettingsRepository.hasCredentials("https://github.com/me/s.git"))
    assertFalse(GitSettingsRepository.hasCredentials("git@github.com:me/s.git"))
    assertFalse(GitSettingsRepository.hasCredentials("ssh://git@github.com/me/s.git"))
  }
}
