package org.jetbrains.idea.packagesearch.api

import com.intellij.openapi.components.service
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

// Phantom: the common module loads (with Maven), and the Package Search client never reaches package-search.services.jetbrains.com
@TestApplication
class PackageSearchDisabledTest {
  @Test
  fun `requests fail before they leave`() {
    val error = runCatching { runBlocking { service<PackageSearchApiClientService>().searchByString("junit") } }.exceptionOrNull()
    assertTrue(generateSequence(error) { it.cause }.any { it is IOException && "disabled in Phantom" in it.message.orEmpty() },
               "expected the Phantom IOException, got $error")
  }
}
