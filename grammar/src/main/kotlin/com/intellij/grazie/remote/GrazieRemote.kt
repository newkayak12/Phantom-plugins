// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.grazie.remote

import com.intellij.grazie.GrazieConfig
import com.intellij.grazie.GrazieDynamic
import com.intellij.grazie.jlanguage.Lang
import com.intellij.grazie.remote.GrazieRemote.downloadAsync
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.io.DigestUtil
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path
import java.security.DigestInputStream
import kotlin.io.path.exists
import kotlin.io.path.inputStream

object GrazieRemote {

  fun isJLangAvailableLocally(lang: Lang): Boolean {
    if (lang.isEnglish()) return true
    return GrazieDynamic.dynamicFolder.resolve(lang.ltRemote!!.file).exists()
  }

  fun isAvailableLocally(lang: Lang): Boolean = isJLangAvailableLocally(lang)

  fun allAvailableLocally(languages: Collection<Lang>): Boolean = languages.all { isAvailableLocally(it) }

  /**
   * Downloads language to local storage synchronously. Use with extra care as
   * some languages are GPL-licensed and require explicit user agreement before downloading
   * 
   * Consider using [downloadAsync] instead as it:
   * 1. Has built-in GPL license agreement check
   * 2. Downloads language in the background without blocking
   * 
   * @param lang Language to download
   * @return true if download was successful, false otherwise
   */
  fun downloadWithoutLicenseCheck(lang: Lang): Boolean = LanguageDownloader.download(listOf(lang))

  /** Downloads [languages] asynchronously to local storage */
  fun downloadAsync(languages: Collection<Lang>, project: Project): Unit = LanguageDownloader.downloadAsync(languages, project)

  /** Downloads all missing languages to local storage*/
  fun downloadMissing(project: Project): Unit = downloadAsync(GrazieConfig.get().missedLanguages, project)

  /**
   * Languages that need the user's agreement to a license before they are downloaded: none since the GPL-licensed
   * Hunspell dictionaries are no longer downloaded (LanguageTool bundles are LGPL)
   */
  @RequiresEdt
  fun getLanguagesBasedOnUserAgreement(languages: Collection<Lang>, @Suppress("UNUSED_PARAMETER") project: Project): Collection<Lang> =
    languages

  @ApiStatus.ScheduledForRemoval
  @Deprecated("Use isValidBundleForLanguage(descriptor, file) instead")
  fun isValidBundleForLanguage(language: Lang, file: Path): Boolean {
    val remote = language.ltRemote ?: return false
    val actualChecksum = checksum(file)
    return remote.checksum == actualChecksum
  }

  fun isValidBundleForLanguage(descriptor: RemoteLangDescriptor?, file: Path): Boolean {
    if (descriptor == null) return false
    val actualChecksum = checksum(file)
    return descriptor.checksum == actualChecksum
  }

  @ApiStatus.Internal
  fun checksum(path: Path): String {
    val digest = path.inputStream().use {
      val digest = DigestUtil.md5()
      DigestInputStream(it, digest).use { stream ->
        val buffer = ByteArray(1024 * 8)
        var bytesRead = 0
        while (bytesRead != -1) {
          bytesRead = stream.read(buffer)
        }
      }
      return@use digest
    }
    return DigestUtil.digestToHash(digest)
  }
}
