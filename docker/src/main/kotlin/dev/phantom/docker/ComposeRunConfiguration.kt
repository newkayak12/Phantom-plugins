// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker

import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.dashboard.RunDashboardDefaultTypesProvider
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.FormBuilder
import dev.phantom.docker.compose.PhantomDockerBundle
import org.jdom.Element
import java.io.File
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent

object DockerIcons {
  @JvmField val Docker: Icon = IconLoader.getIcon("/icons/phantom/docker.svg", DockerIcons::class.java.classLoader)
}

class ComposeConfigurationType : ConfigurationTypeBase(ID, PhantomDockerBundle.message("compose.run.type.name"),
                                                       PhantomDockerBundle.message("compose.run.type.description"),
                                                       NotNullLazyValue.createValue { DockerIcons.Docker }) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = "Docker Compose"
      override fun createTemplateConfiguration(project: Project): RunConfiguration = ComposeRunConfiguration(project, this, "")
      override fun getOptionsClass(): Class<out RunConfigurationOptions> = LocatableRunConfigurationOptions::class.java
    })
  }

  val factory: ConfigurationFactory get() = configurationFactories.single()

  companion object {
    const val ID: String = "PhantomDockerCompose"

    fun getInstance(): ComposeConfigurationType = ConfigurationTypeUtil.findConfigurationType(ComposeConfigurationType::class.java)
  }
}

/**
 * `docker compose -f <files> up [--build] [--remove-orphans] [services]`, attached, so the console shows every
 * service's log. Stop sends Ctrl+C, which makes compose stop the containers it started.
 */
class ComposeRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
  LocatableConfigurationBase<LocatableRunConfigurationOptions>(project, factory, name) {
  var composeFiles: List<String> = emptyList()
  var services: List<String> = emptyList()
  var build: Boolean = false
  var removeOrphans: Boolean = false
  var extraArgs: String = ""

  fun commandArgs(): List<String> = buildList {
    add("compose")
    for (file in composeFiles) { add("-f"); add(file) }
    add("up")
    if (build) add("--build")
    if (removeOrphans) add("--remove-orphans")
    addAll(ParametersListUtil.parse(extraArgs))
    addAll(services)
  }

  override fun suggestedName(): String {
    val file = composeFiles.firstOrNull()?.let { File(it).name } ?: "compose"
    return if (services.isEmpty()) file else "$file: ${services.joinToString(", ")}"
  }

  override fun checkConfiguration() {
    if (composeFiles.isEmpty()) throw RuntimeConfigurationError(PhantomDockerBundle.message("compose.run.no.file"))
    composeFiles.firstOrNull { !File(it).isFile }?.let { throw RuntimeConfigurationError(PhantomDockerBundle.message("compose.run.file.missing", it)) }
    if (DockerCli.executable() == null) throw RuntimeConfigurationWarning(DockerNotFoundException().message!!)
  }

  override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = ComposeSettingsEditor(project)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = object : CommandLineState(environment) {
    override fun startProcess(): ProcessHandler {
      val workDir = composeFiles.firstOrNull()?.let { Path.of(it).parent }
      val handler = object : KillableColoredProcessHandler(DockerCli.command(*commandArgs().toTypedArray(), workDir = workDir)) {
        override fun shouldKillProcessSoftly(): Boolean = true
      }
      ProcessTerminatedListener.attach(handler)
      return handler
    }
  }

  override fun readExternal(element: Element) {
    super.readExternal(element)
    composeFiles = element.getChildren("compose-file").mapNotNull { it.getAttributeValue("path") }
    services = element.getChildren("service").mapNotNull { it.getAttributeValue("name") }
    build = JDOMExternalizerUtil.readField(element, "BUILD") == "true"
    removeOrphans = JDOMExternalizerUtil.readField(element, "REMOVE_ORPHANS") == "true"
    extraArgs = JDOMExternalizerUtil.readField(element, "EXTRA_ARGS").orEmpty()
  }

  override fun writeExternal(element: Element) {
    super.writeExternal(element)
    for (f in composeFiles) element.addContent(Element("compose-file").setAttribute("path", f))
    for (s in services) element.addContent(Element("service").setAttribute("name", s))
    if (build) JDOMExternalizerUtil.writeField(element, "BUILD", "true")
    if (removeOrphans) JDOMExternalizerUtil.writeField(element, "REMOVE_ORPHANS", "true")
    if (extraArgs.isNotBlank()) JDOMExternalizerUtil.writeField(element, "EXTRA_ARGS", extraArgs)
  }
}

class ComposeSettingsEditor(project: Project) : SettingsEditor<ComposeRunConfiguration>() {
  private val file = TextFieldWithBrowseButton().apply {
    addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle(PhantomDockerBundle.message("compose.run.file.chooser")))
  }
  private val services = JBTextField().apply { emptyText.text = PhantomDockerBundle.message("compose.run.all.services") }
  private val build = JBCheckBox(PhantomDockerBundle.message("compose.run.build"))
  private val removeOrphans = JBCheckBox(PhantomDockerBundle.message("compose.run.remove.orphans"))
  private val extraArgs = RawCommandLineEditor()

  override fun resetEditorFrom(c: ComposeRunConfiguration) {
    file.text = c.composeFiles.joinToString(File.pathSeparator)
    services.text = c.services.joinToString(", ")
    build.isSelected = c.build
    removeOrphans.isSelected = c.removeOrphans
    extraArgs.text = c.extraArgs
  }

  override fun applyEditorTo(c: ComposeRunConfiguration) {
    c.composeFiles = file.text.split(File.pathSeparator).map { it.trim() }.filter { it.isNotEmpty() }
    c.services = services.text.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    c.build = build.isSelected
    c.removeOrphans = removeOrphans.isSelected
    c.extraArgs = extraArgs.text
  }

  override fun createEditor(): JComponent = FormBuilder.createFormBuilder()
    .addLabeledComponent(PhantomDockerBundle.message("compose.run.files"), file)
    .addTooltip(PhantomDockerBundle.message("compose.run.files.tooltip", File.pathSeparator))
    .addLabeledComponent(PhantomDockerBundle.message("compose.run.services"), services)
    .addComponent(build)
    .addComponent(removeOrphans)
    .addLabeledComponent(PhantomDockerBundle.message("compose.run.arguments"), extraArgs)
    .panel
}

class ComposeDashboardTypes : RunDashboardDefaultTypesProvider {
  override fun getDefaultTypeIds(project: Project): Collection<String> = listOf(ComposeConfigurationType.ID)
}
