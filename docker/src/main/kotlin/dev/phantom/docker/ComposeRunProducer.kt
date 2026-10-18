// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

object ComposeFiles {
  private val NAME = Regex("""(docker-)?compose([.-][\w.-]+)?\.ya?ml""")

  fun isComposeFile(name: String): Boolean = NAME.matches(name)

  /** The top-level `services:` entry, or null when [element] is not inside one. */
  private fun servicesEntry(kv: YAMLKeyValue): Boolean =
    kv.keyText == "services" && (kv.parent as? YAMLMapping)?.parent is YAMLDocument

  /** What Run at [element] starts: `""` for the whole file, a service name, or null for neither. */
  fun target(element: PsiElement): String? {
    val file = element.containingFile ?: return null
    if (!isComposeFile(file.name)) return null
    var kv = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false)
    while (kv != null) {
      if (servicesEntry(kv)) return ""
      val parent = PsiTreeUtil.getParentOfType(kv, YAMLKeyValue::class.java, true)
      if (parent != null && servicesEntry(parent)) return kv.keyText
      kv = parent
    }
    return ""
  }

  fun path(file: PsiFile): String? = file.virtualFile?.path ?: file.originalFile.virtualFile?.path
}

class ComposeRunProducer : LazyRunConfigurationProducer<ComposeRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = ComposeConfigurationType.getInstance().factory

  override fun setupConfigurationFromContext(configuration: ComposeRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
    val element = context.psiLocation ?: return false
    val service = ComposeFiles.target(element) ?: return false
    val path = ComposeFiles.path(element.containingFile) ?: return false
    configuration.composeFiles = listOf(path)
    configuration.services = listOfNotNull(service.takeIf { it.isNotEmpty() })
    configuration.setGeneratedName()
    return true
  }

  override fun isConfigurationFromContext(configuration: ComposeRunConfiguration, context: ConfigurationContext): Boolean {
    val element = context.psiLocation ?: return false
    val service = ComposeFiles.target(element) ?: return false
    return configuration.composeFiles == listOfNotNull(ComposeFiles.path(element.containingFile)) &&
           configuration.services == listOfNotNull(service.takeIf { it.isNotEmpty() })
  }
}

/** Run icons on `services:` (the whole file) and on each service. */
class ComposeRunLineMarkerContributor : RunLineMarkerContributor() {
  override fun getInfo(element: PsiElement): Info? {
    if (element.firstChild != null) return null
    val kv = element.parent as? YAMLKeyValue ?: return null
    if (kv.key != element || !ComposeFiles.isComposeFile(element.containingFile.name)) return null
    val target = ComposeFiles.target(element) ?: return null
    val isServices = kv.keyText == "services" && target.isEmpty()
    if (!isServices && target != kv.keyText) return null
    val tooltip = if (isServices) "docker compose up" else "docker compose up $target"
    return Info(com.intellij.icons.AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { tooltip }
  }
}
