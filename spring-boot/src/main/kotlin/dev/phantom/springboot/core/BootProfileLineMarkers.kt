// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.uast.UClass
import org.jetbrains.uast.getUParentForIdentifier
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon

/** Keys of a configuration file by where their text starts, read without a parser. */
internal object ConfigKeyOffsets {
  class Key(val name: String, val start: Int, val value: String)

  fun of(file: PsiFile): Map<Int, String> = CachedValuesManager.getCachedValue(file) {
    val result = read(file.name, file.viewProvider.contents.toString()).associate { it.start to it.name }
    CachedValueProvider.Result.create(result, file)
  }

  /** Normalized key names with the offset of the key's first character on its line. */
  fun read(fileName: String, text: String): List<Key> =
    if (fileName.endsWith(".properties")) ConfigText.properties(text).map { Key(ConfigNames.normalize(it.key), it.offset - (it.key.lastIndexOf('.') + 1), it.value) }
    else ConfigText.yaml(text).map { Key(ConfigNames.normalize(it.key), it.offset, it.value) }
}

/** `application.yml` and its profile files (`application-dev.yml`) in one folder. */
internal object ProfileFiles {
  private val NAME = Regex("""(application|bootstrap)(?:-([\w.-]+))?\.(ya?ml|properties)""")

  class Name(val stem: String, val profile: String?)

  fun nameOf(fileName: String): Name? = NAME.matchEntire(fileName)?.let { Name(it.groupValues[1], it.groupValues[2].ifEmpty { null }) }

  /** The base file(s) for a profile file, or the profile files for a base file, beside [file]. */
  fun counterparts(file: VirtualFile): List<VirtualFile> {
    val name = nameOf(file.name) ?: return emptyList()
    return file.parent?.children.orEmpty().filter { other ->
      if (other == file || other.isDirectory) return@filter false
      val n = nameOf(other.name) ?: return@filter false
      n.stem == name.stem && (if (name.profile == null) n.profile != null else n.profile == null)
    }.sortedBy { it.name }
  }

  @TestOnly
  val readCount: AtomicInteger = AtomicInteger()

  fun keys(file: VirtualFile): Map<String, ConfigKeyOffsets.Key> {
    readCount.incrementAndGet()
    val text = FileDocumentManager.getInstance().getCachedDocument(file)?.charsSequence?.toString()
               ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return emptyMap()
    return ConfigKeyOffsets.read(file.name, text).associateBy { it.name }
  }
}

/**
 * Between `application.yml` and `application-<profile>.yml` in the same folder: on a profile file's key, the base value
 * it overrides; on a base key, the profile files that override it.
 */
class BootProfileOverrideLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBootBundle.message("gutter.profile.name")

  override fun getIcon(): Icon = AllIcons.Gutter.OverridingMethod

  private class Override(val file: VirtualFile, val key: ConfigKeyOffsets.Key)

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null || element.textLength == 0) return null
    val file = element.containingFile ?: return null
    val name = ProfileFiles.nameOf(file.name) ?: return null
    val key = ConfigKeyOffsets.of(file)[element.textRange.startOffset] ?: return null
    val virtualFile = file.originalFile.virtualFile ?: return null
    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return null
    if (!SpringBootGate.isBootModule(module)) return null
    val others = counterpartKeys(file, virtualFile).mapNotNull { (other, keys) -> keys[key]?.let { Override(other, it) } }
    if (others.isEmpty()) return null
    val project = file.project
    val targets: NotNullLazyValue<Collection<PsiElement>> = NotNullLazyValue.lazy {
      val manager = PsiManager.getInstance(project)
      others.mapNotNull { o -> manager.findFile(o.file)?.findElementAt(o.key.start) }
    }
    val profile = name.profile
    return if (profile != null) {
      val base = others.first()
      NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridingMethod)
        .setTargets(targets)
        .setTooltipText(PhantomSpringBootBundle.message("gutter.profile.overrides", base.file.name, base.key.value.ifEmpty { "…" }))
        .createLineMarkerInfo(element)
    }
    else {
      NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridenMethod)
        .setTargets(targets)
        .setTooltipText(PhantomSpringBootBundle.message("gutter.profile.overridden", others.joinToString { it.file.name }))
        .setPopupTitle(PhantomSpringBootBundle.message("gutter.profile.popup"))
        .createLineMarkerInfo(element)
    }
  }

  private fun counterpartKeys(file: PsiFile, virtualFile: VirtualFile): List<Pair<VirtualFile, Map<String, ConfigKeyOffsets.Key>>> =
    CachedValuesManager.getCachedValue(file) {
      val counterparts = ProfileFiles.counterparts(virtualFile)
      // Only configuration text matters, also a counterpart changed on disk: a Java keystroke must not re-read the files.
      CachedValueProvider.Result.create(counterparts.map { it to ProfileFiles.keys(it) }, *BootCacheDeps.config(file.project, counterparts))
    }
}

/** On a `@SpringBootApplication` class: the module's configuration files, including those it imports. */
class BootApplicationConfigLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBootBundle.message("gutter.application.config.name")

  override fun getIcon(): Icon = AllIcons.FileTypes.Config

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null) return null
    val owner = element.parent as? PsiNameIdentifierOwner ?: return null
    if (owner.nameIdentifier != element) return null
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
    if (!SpringBootGate.isBootModule(module)) return null
    val uClass = getUParentForIdentifier(element) as? UClass ?: return null
    if (uClass.javaPsi.getAnnotation(SPRING_BOOT_APPLICATION) == null) return null
    val project = element.project
    val files = (BootRoots.baseConfigFiles(project) + BootConfigImports.importedFiles(project)).distinct()
      .filter { ModuleUtilCore.findModuleForFile(it, project) == module }
    if (files.isEmpty()) return null
    return NavigationGutterIconBuilder.create(AllIcons.FileTypes.Config)
      .setTargets(NotNullLazyValue.lazy { files.mapNotNull { PsiManager.getInstance(project).findFile(it) } })
      .setTooltipText(PhantomSpringBootBundle.message("gutter.application.config.tooltip", files.take(5).joinToString { it.name } + if (files.size > 5) ", …" else ""))
      .setPopupTitle(PhantomSpringBootBundle.message("gutter.application.config.popup"))
      .createLineMarkerInfo(element)
  }

  companion object {
    const val SPRING_BOOT_APPLICATION: String = "org.springframework.boot.autoconfigure.SpringBootApplication"
  }
}
