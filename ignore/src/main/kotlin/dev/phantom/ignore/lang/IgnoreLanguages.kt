// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ignore.lang

import com.intellij.openapi.vcs.changes.ignore.lang.IgnoreFileType
import com.intellij.openapi.vcs.changes.ignore.lang.IgnoreLanguage

// Dialects of the platform's ignore language (the one .gitignore uses): its highlighting, path completion and references,
// and the duplicate entry check carry over. Each is registered in plugin.xml like git4idea registers GitIgnore.

object DockerIgnoreLanguage : IgnoreLanguage("DockerIgnore", "dockerignore") {
  override fun getFileType(): IgnoreFileType = DockerIgnoreFileType
}
object DockerIgnoreFileType : IgnoreFileType(DockerIgnoreLanguage)

object NpmIgnoreLanguage : IgnoreLanguage("NpmIgnore", "npmignore") {
  override fun getFileType(): IgnoreFileType = NpmIgnoreFileType
}
object NpmIgnoreFileType : IgnoreFileType(NpmIgnoreLanguage)

object HelmIgnoreLanguage : IgnoreLanguage("HelmIgnore", "helmignore") {
  override fun getFileType(): IgnoreFileType = HelmIgnoreFileType
}
object HelmIgnoreFileType : IgnoreFileType(HelmIgnoreLanguage)

object PrettierIgnoreLanguage : IgnoreLanguage("PrettierIgnore", "prettierignore") {
  override fun getFileType(): IgnoreFileType = PrettierIgnoreFileType
}
object PrettierIgnoreFileType : IgnoreFileType(PrettierIgnoreLanguage)

object EslintIgnoreLanguage : IgnoreLanguage("EslintIgnore", "eslintignore") {
  override fun getFileType(): IgnoreFileType = EslintIgnoreFileType
}
object EslintIgnoreFileType : IgnoreFileType(EslintIgnoreLanguage)

object StylelintIgnoreLanguage : IgnoreLanguage("StylelintIgnore", "stylelintignore") {
  override fun getFileType(): IgnoreFileType = StylelintIgnoreFileType
}
object StylelintIgnoreFileType : IgnoreFileType(StylelintIgnoreLanguage)

object GcloudIgnoreLanguage : IgnoreLanguage("GcloudIgnore", "gcloudignore") {
  override fun getFileType(): IgnoreFileType = GcloudIgnoreFileType
}
object GcloudIgnoreFileType : IgnoreFileType(GcloudIgnoreLanguage)

object VscodeIgnoreLanguage : IgnoreLanguage("VscodeIgnore", "vscodeignore") {
  override fun getFileType(): IgnoreFileType = VscodeIgnoreFileType
}
object VscodeIgnoreFileType : IgnoreFileType(VscodeIgnoreLanguage)
