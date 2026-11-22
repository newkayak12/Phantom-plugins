// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.grazie

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
object GraziePlugin {
  const val id = "dev.phantom.grammar" // phantom: own id; tanvd.grazi and dev.phantom.grazie are only aliases, which getPlugin() does not resolve

  object LanguageTool {
    const val version = "6.8.27"
    // phantom: the language bundles (LGPL 2.1, the same files and checksums as JetBrains' resources.jetbrains.com/grazie/model/language-tool)
    // are downloaded from this repository's GitHub release, made by tools/languagetool-release.sh, not from JetBrains' servers
    const val url = "https://github.com/newkayak12/Phantom-plugins/releases/download/languagetool-$version"
  }

  private val descriptor: IdeaPluginDescriptor
    get() = PluginManagerCore.getPlugin(PluginId.getId(id))!!

  val group: String
    get() = GrazieBundle.message("grazie.group.name")

  val settingsPageName: String
    get() = GrazieBundle.message("grazie.settings.page.name")

  val isBundled: Boolean
    get() = descriptor.isBundled

  val classLoader: ClassLoader
    get() = descriptor.classLoader
}