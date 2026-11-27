// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import javax.swing.Icon

/**
 * On `spring.profiles.active` / `include` / `default`, `spring.profiles.group.<name>` and
 * `spring.config.activate.on-profile`: the `application-<profile>` files of the profiles the value names.
 */
class BootProfileValueLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBootBundle.message("gutter.profile.value.name")

  override fun getIcon(): Icon = AllIcons.FileTypes.Config

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {

    if (GhostMode.enabled) return null
    if (element.firstChild != null || element.textLength == 0) return null
    val file = element.containingFile ?: return null
    if (!BootConfigFiles.isConfigFile(file)) return null
    val key = keys(file)[element.textRange.startOffset] ?: return null
    if (!isProfileKey(key.name)) return null
    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return null
    if (!SpringBootGate.isBootModule(module)) return null
    val text = file.viewProvider.contents
    val profiles = profileNames(if (key.value.isNotEmpty()) key.value else listItems(text, key.start))
    if (profiles.isEmpty()) return null
    val virtualFile = file.originalFile.virtualFile ?: return null
    val stem = ProfileFiles.nameOf(virtualFile.name)?.stem ?: "application"
    val folders = (listOfNotNull(virtualFile.parent) + BootRoots.baseConfigFiles(file.project).mapNotNull { it.parent }).distinct()
    val targets = profiles.flatMap { profile -> profileFiles(folders, stem, profile) }.distinct().sortedBy { it.name }
    if (targets.isEmpty()) return null
    val project = file.project
    return NavigationGutterIconBuilder.create(AllIcons.FileTypes.Config)
      .setTargets(NotNullLazyValue.lazy { targets.mapNotNull { PsiManager.getInstance(project).findFile(it) } })
      .setTooltipText(PhantomSpringBootBundle.message("gutter.profile.value.tooltip", targets.joinToString { it.name }))
      .setPopupTitle(PhantomSpringBootBundle.message("gutter.profile.value.popup"))
      .createLineMarkerInfo(element)
  }

  private fun keys(file: PsiFile): Map<Int, ConfigKeyOffsets.Key> = CachedValuesManager.getCachedValue(file) {
    val text = file.viewProvider.contents.toString()
    val keys = ConfigKeyOffsets.read(file.name, text) + if (file.name.endsWith(".properties")) emptyList() else parentKeys(text)
    CachedValueProvider.Result.create(keys.associateBy { it.start }, file)
  }

  /** YAML keys with no scalar value (`active:` followed by `- dev` items), which the flat key reader leaves out. */
  private fun parentKeys(text: String): List<ConfigKeyOffsets.Key> {
    val out = ArrayList<ConfigKeyOffsets.Key>()
    val stack = ArrayList<Pair<Int, String>>()
    var offset = 0
    for (line in text.split('\n')) {
      val start = offset
      offset += line.length + 1
      if (line.startsWith("---")) { stack.clear(); continue }
      val body = line.trim()
      if (body.isEmpty() || body.startsWith("#") || body.startsWith("-")) continue
      val indent = line.length - line.trimStart().length
      val m = Regex("^([\\w.\\-\\[\\]\"']+):(\\s*(#.*)?)?$").find(body)
      while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.size - 1)
      if (m == null) { Regex("^([\\w.\\-\"']+):").find(body)?.let { stack.add(indent to "\u0000") }; continue }
      val key = m.groupValues[1].trim('"', '\'')
      val path = (stack.map { it.second } + key).joinToString(".")
      if ("\u0000" !in path) out.add(ConfigKeyOffsets.Key(ConfigNames.normalize(path), start + indent, ""))
      stack.add(indent to key)
    }
    return out
  }

  companion object {
    private val KEYS = setOf("spring.profiles.active", "spring.profiles.include", "spring.profiles.default", "spring.config.activate.on-profile", "spring.profiles")

    fun isProfileKey(normalized: String): Boolean = normalized in KEYS || normalized.startsWith("spring.profiles.group.")

    /** Profile names in a value: a comma list, or a profile expression (`prod & !cloud`). */
    fun profileNames(value: String): List<String> =
      Regex("[A-Za-z0-9_][\\w.-]*").findAll(value).map { it.value }.filter { it != "default" || value.trim() == "default" }.distinct().toList()

    /** `- a` items under the key that starts at [keyStart], joined with commas. */
    fun listItems(text: CharSequence, keyStart: Int): String {
      val lineStart = text.lastIndexOf('\n', keyStart - 1) + 1
      val keyIndent = keyStart - lineStart
      val items = ArrayList<String>()
      var pos = text.indexOf('\n', keyStart).takeIf { it >= 0 } ?: return ""
      while (pos < text.length) {
        val next = text.indexOf('\n', pos + 1).let { if (it < 0) text.length else it }
        val line = text.subSequence(pos + 1, next).toString()
        val body = line.trim()
        if (body.isEmpty() || body.startsWith("#")) { pos = next; continue }
        val indent = line.length - line.trimStart().length
        if (indent < keyIndent || (indent == keyIndent && !body.startsWith("- ")) || !body.startsWith("-")) break
        items.add(body.removePrefix("-").substringBefore(" #").trim().trim('"', '\''))
        pos = next
      }
      return items.joinToString(",")
    }

    private fun profileFiles(folders: List<VirtualFile>, stem: String, profile: String): List<VirtualFile> =
      folders.flatMap { folder ->
        folder.children.filter { !it.isDirectory && ProfileFiles.nameOf(it.name)?.let { n -> n.stem == stem && n.profile == profile } == true }
      }.sortedBy { it.name }
  }
}
