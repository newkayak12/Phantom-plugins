// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.run

import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.execution.JavaExecutionUtil
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.*
import com.intellij.execution.dashboard.RunDashboardDefaultTypesProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.util.PsiMethodUtil
import com.intellij.openapi.util.IconLoader
import org.jdom.Element

/**
 * The Spring Boot run configuration type. Its id, factory name and the `SPRING_BOOT_MAIN_CLASS` / `ACTIVE_PROFILES`
 * options are the ones IntelliJ IDEA Ultimate writes to `.idea/workspace.xml`, so a project's existing Spring Boot run
 * configurations open in Phantom as they are, and the ones Phantom writes open in IDEA.
 */
class SpringBootConfigurationType : ConfigurationTypeBase(
  ID, "Spring Boot", "Run a Spring Boot application", NotNullLazyValue.createValue { IconLoader.getIcon("/icons/phantom/springBoot.svg", SpringBootConfigurationType::class.java.classLoader) },
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = FACTORY_NAME
      override fun getName(): String = FACTORY_NAME
      override fun createTemplateConfiguration(project: Project): RunConfiguration = SpringBootRunConfiguration("", project, this)
      override fun getOptionsClass(): Class<out BaseState> = com.intellij.execution.application.JvmMainMethodRunConfigurationOptions::class.java
    })
  }

  val factory: ConfigurationFactory get() = configurationFactories.single()

  companion object {
    const val ID: String = "SpringBootApplicationConfigurationType"
    const val FACTORY_NAME: String = "Spring Boot"

    fun getInstance(): SpringBootConfigurationType = ConfigurationTypeUtil.findConfigurationType(SpringBootConfigurationType::class.java)
  }
}

/**
 * A Java application run configuration whose main class is the `@SpringBootApplication` class, plus active profiles.
 * For a Kotlin application, whose `main` is a top-level function next to the class, it runs that file's class.
 */
class SpringBootRunConfiguration(name: String, project: Project, factory: ConfigurationFactory) : ApplicationConfiguration(name, project, factory) {
  /** Comma-separated, passed as `-Dspring.profiles.active`. */
  var activeProfiles: String = ""

  override fun createAdditionalTabComponents(manager: AdditionalTabComponentManager, startedProcess: com.intellij.execution.process.ProcessHandler?) {
    super.createAdditionalTabComponents(manager, startedProcess)
    if (startedProcess != null && hasActuator()) manager.addAdditionalTabComponent(ActuatorTab(startedProcess), "phantom.actuator")
  }

  /** Whether the module's runtime classpath has Spring Boot Actuator; true when that cannot be told yet (indexing). */
  fun hasActuator(): Boolean {
    val module = configurationModule.module ?: return true
    if (com.intellij.openapi.project.DumbService.isDumb(project)) return true
    return ReadAction.compute<Boolean, RuntimeException> {
      com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(ACTUATOR_ENDPOINT, module.getModuleRuntimeScope(false)) != null
    }
  }

  override fun getRunClass(): String? = ReadAction.compute<String?, RuntimeException> {
    val main = mainClass ?: return@compute mainClassName
    SpringBootApplications.launcherOf(main)?.let(JavaExecutionUtil::getRuntimeQualifiedName) ?: mainClassName
  }

  override fun checkClass(): com.intellij.execution.configurations.JavaRunConfigurationModule {
    val module = configurationModule
    val psiClass = module.checkModuleAndClassName(mainClassName, "No Spring Boot application class specified")
    if (psiClass == null || SpringBootApplications.launcherOf(psiClass) == null) {
      throw RuntimeConfigurationWarning("No main method for $mainClassName, in the class or in its Kotlin file")
    }
    return module
  }

  override fun readExternal(element: Element) {
    super.readExternal(element)
    option(element, MAIN_CLASS_OPTION)?.let { mainClassName = it }
    activeProfiles = option(element, PROFILES_OPTION).orEmpty()
  }

  override fun writeExternal(element: Element) {
    super.writeExternal(element)
    // IDEA keeps the class under its own option name; ApplicationConfiguration's MAIN_CLASS_NAME would only duplicate it
    element.getChildren("option").filter { it.getAttributeValue("name") == "MAIN_CLASS_NAME" }.forEach { element.removeContent(it) }
    if (activeProfiles.isNotBlank()) element.addContent(0, optionElement(PROFILES_OPTION, activeProfiles))
    mainClassName?.let { element.addContent(optionElement(MAIN_CLASS_OPTION, it)) }
  }

  override fun suggestedName(): String? = mainClass?.name ?: mainClassName?.substringAfterLast('.')

  private fun option(element: Element, name: String): String? =
    element.getChildren("option").firstOrNull { it.getAttributeValue("name") == name }?.getAttributeValue("value")

  private fun optionElement(name: String, value: String): Element = Element("option").setAttribute("name", name).setAttribute("value", value)

  companion object {
    const val MAIN_CLASS_OPTION: String = "SPRING_BOOT_MAIN_CLASS"
    const val PROFILES_OPTION: String = "ACTIVE_PROFILES"
    const val ACTUATOR_ENDPOINT: String = "org.springframework.boot.actuate.endpoint.annotation.Endpoint"
  }
}

object SpringBootApplications {
  const val ANNOTATION: String = "org.springframework.boot.autoconfigure.SpringBootApplication"

  fun isApplicationClass(psiClass: PsiClass): Boolean =
    !psiClass.isAnnotationType && !psiClass.isInterface && MetaAnnotationUtil.isMetaAnnotated(psiClass, listOf(ANNOTATION))

  /**
   * The class to launch for [application]: itself when it has a `main` (Java, or a Kotlin companion with `@JvmStatic`),
   * otherwise the class of a Kotlin file whose top-level `fun main` sits next to it.
   */
  fun launcherOf(application: PsiClass): PsiClass? {
    if (PsiMethodUtil.hasMainMethod(application)) return application
    val owner = application.navigationElement.containingFile as? PsiClassOwner ?: application.containingFile as? PsiClassOwner
    val candidates = (owner?.classes.orEmpty().toList() + (application.containingFile as? PsiClassOwner)?.classes.orEmpty()).distinct()
    return candidates.firstOrNull { it != application && PsiMethodUtil.hasMainMethod(it) }
  }
}

/** Spring Boot applications show in the Services tool window, where several can be started, stopped and watched at once. */
class SpringBootDashboardTypes : RunDashboardDefaultTypesProvider {
  override fun getDefaultTypeIds(project: Project): Collection<String> = listOf(SpringBootConfigurationType.ID)
}
