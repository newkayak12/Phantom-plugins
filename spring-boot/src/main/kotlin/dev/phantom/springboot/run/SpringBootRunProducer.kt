// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.ui.SettingsEditorFragment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.ui.LabeledComponent
import java.awt.BorderLayout

/**
 * Run, Debug and Profile on a `@SpringBootApplication` class or its Kotlin `main` create a Spring Boot configuration,
 * taking the place of the plain Application or Kotlin one the other producers offer for the same `main`.
 */
class SpringBootRunProducer : LazyRunConfigurationProducer<SpringBootRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = SpringBootConfigurationType.getInstance().factory

  override fun setupConfigurationFromContext(configuration: SpringBootRunConfiguration, context: ConfigurationContext,
                                             sourceElement: Ref<PsiElement>): Boolean {
    val application = applicationAt(context.psiLocation) ?: return false
    configuration.setMainClass(application)
    configuration.setGeneratedName()
    return true
  }

  override fun isConfigurationFromContext(configuration: SpringBootRunConfiguration, context: ConfigurationContext): Boolean {
    val application = applicationAt(context.psiLocation) ?: return false
    return configuration.mainClassName == application.qualifiedName
  }

  override fun isPreferredConfiguration(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean =
    other.configuration !is SpringBootRunConfiguration

  override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean =
    other.configuration !is SpringBootRunConfiguration

  companion object {
    /** The Spring Boot application class at [element]: the class around it, or one declared in the same file. */
    fun applicationAt(element: PsiElement?): PsiClass? {
      if (element == null) return null
      val enclosing = generateSequence(PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)) {
        PsiTreeUtil.getParentOfType(it, PsiClass::class.java, true)
      }
      enclosing.firstOrNull { SpringBootApplications.isApplicationClass(it) }?.let { return it }
      // a Kotlin top-level `fun main` has no class around it in PSI: look at the classes of its file
      val file = element.containingFile as? PsiClassOwner ?: return null
      val inFile = file.classes.filter { SpringBootApplications.isApplicationClass(it) }
      return inFile.singleOrNull()?.takeIf { cls -> SpringBootApplications.launcherOf(cls) != null }
    }
  }
}

/** Active profiles: a field in the Spring Boot configuration editor, and `-Dspring.profiles.active` at launch. */
class SpringBootProfilesExtension : RunConfigurationExtension() {
  override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = configuration is SpringBootRunConfiguration

  override fun isEnabledFor(applicableConfiguration: RunConfigurationBase<*>, runnerSettings: RunnerSettings?): Boolean = true

  override fun <T : RunConfigurationBase<*>> updateJavaParameters(configuration: T, params: JavaParameters, runnerSettings: RunnerSettings?) {
    val profiles = (configuration as? SpringBootRunConfiguration)?.activeProfiles?.trim().orEmpty()
    if (profiles.isEmpty() || params.vmParametersList.hasProperty("spring.profiles.active")) return
    params.vmParametersList.addProperty("spring.profiles.active", profiles.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","))
  }

  override fun <P : RunConfigurationBase<*>> createFragments(configuration: P): List<SettingsEditor<P>> {
    if (configuration !is SpringBootRunConfiguration) return emptyList()
    val field = JBTextField().apply { emptyText.text = "dev,local" }
    val component = LabeledComponent.create(field, "Active profiles:", BorderLayout.WEST)
    val fragment = SettingsEditorFragment<P, LabeledComponent<JBTextField>>(
      "spring.boot.profiles", "Active profiles", "Spring Boot", component,
      { c, comp -> comp.component.text = (c as SpringBootRunConfiguration).activeProfiles },
      { c, comp -> (c as SpringBootRunConfiguration).activeProfiles = if (comp.isVisible) comp.component.text.trim() else "" },
      { c -> (c as SpringBootRunConfiguration).activeProfiles.isNotBlank() },
    )
    fragment.isRemovable = true
    fragment.setHint("Comma-separated, passed as -Dspring.profiles.active")
    return listOf(fragment)
  }
}
