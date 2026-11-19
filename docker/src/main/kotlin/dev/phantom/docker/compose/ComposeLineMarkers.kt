// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker.compose

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import dev.phantom.docker.ComposeFiles
import dev.phantom.docker.dockerfile.Dockerfile
import org.jetbrains.yaml.psi.*
import javax.swing.Icon

/**
 * In a compose file: `build` of a service to the Dockerfile it builds (context and `dockerfile` resolved against the
 * compose file's directory), and `depends_on` to the services it waits for.
 */
class ComposeLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomDockerBundle.message("compose.gutter.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null) return null
    val kv = element.parent as? YAMLKeyValue ?: return null
    if (kv.key != element || kv.keyText !in setOf("build", "depends_on")) return null
    val file = element.containingFile as? YAMLFile ?: return null
    if (!ComposeFiles.isComposeFile(file.name)) return null
    val service = serviceMapping(kv) ?: return null
    return when (kv.keyText) {
      "build" -> {
        val dockerfile = dockerfileOf(kv, file) ?: return null
        val target = PsiManager.getInstance(file.project).findFile(dockerfile) ?: return null
        NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
          .setTargets(target)
          .setTooltipText(PhantomDockerBundle.message("compose.gutter.build", dockerfile.presentableUrl))
          .createLineMarkerInfo(element)
      }
      else -> {
        val names = ComposeReferences.serviceTargets(service).filter { PsiTreeUtil.isAncestor(kv, it.element, false) }.map { it.name }.distinct()
        val definitions = ComposeReferences.definitions(file, ComposeReferences.Kind.SERVICE)
        val targets = names.mapNotNull { definitions[it] }
        if (targets.isEmpty()) return null
        NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
          .setTargets(targets)
          .setTooltipText(PhantomDockerBundle.message("compose.gutter.depends", targets.joinToString { it.keyText }))
          .setPopupTitle(PhantomDockerBundle.message("compose.gutter.depends.popup"))
          .createLineMarkerInfo(element)
      }
    }
  }

  /** The mapping of the service [kv] is a direct key of, under the top-level `services:`. */
  private fun serviceMapping(kv: YAMLKeyValue): YAMLMapping? {
    val mapping = kv.parentMapping ?: return null
    val service = mapping.parent as? YAMLKeyValue ?: return null
    val services = service.parentMapping?.parent as? YAMLKeyValue ?: return null
    if (services.keyText != "services" || services.parentMapping?.parent !is YAMLDocument) return null
    return mapping
  }

  /** `build: ./dir` or `build: {context, dockerfile}`; remote contexts and variables are not followed. */
  private fun dockerfileOf(build: YAMLKeyValue, file: YAMLFile): VirtualFile? {
    val dir = file.originalFile.virtualFile?.parent ?: return null
    val (context, dockerfile) = when (val value = build.value) {
      is YAMLScalar -> value.textValue to null
      is YAMLMapping -> ((value.getKeyValueByKey("context")?.value as? YAMLScalar)?.textValue ?: ".") to
        (value.getKeyValueByKey("dockerfile")?.value as? YAMLScalar)?.textValue
      else -> return null
    }
    if (context.contains("://") || context.startsWith("git@") || context.contains('$') || dockerfile?.contains('$') == true) return null
    val contextDir = (if (context == "." || context.isEmpty()) dir else dir.findFileByRelativePath(context.removePrefix("./"))) ?: return null
    if (!contextDir.isDirectory) return null
    val found = contextDir.findFileByRelativePath((dockerfile ?: "Dockerfile").removePrefix("./")) ?: return null
    return found.takeIf { !it.isDirectory && (dockerfile != null || Dockerfile.isDockerfile(it.name)) }
  }
}
