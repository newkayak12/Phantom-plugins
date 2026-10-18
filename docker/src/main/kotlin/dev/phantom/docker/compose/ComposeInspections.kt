// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker.compose

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue

/** Runs [check] on compose files only, with a problem list to fill. */
abstract class ComposeInspection : LocalInspectionTool() {
  override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
    if (file !is YAMLFile) return null
    val model = ComposeModel.of(file) ?: return null
    val root = file.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: return null
    val problems = Problems(manager, isOnTheFly)
    check(file, root, model, problems)
    return problems.list.toTypedArray()
  }

  abstract fun check(file: YAMLFile, root: YAMLMapping, model: ComposeModel, problems: Problems)

  class Problems(private val manager: InspectionManager, private val isOnTheFly: Boolean) {
    val list = ArrayList<ProblemDescriptor>()

    fun add(element: PsiElement, message: String, type: ProblemHighlightType = ProblemHighlightType.GENERIC_ERROR_OR_WARNING, vararg fixes: LocalQuickFix) {
      list.add(manager.createProblemDescriptor(element, message, isOnTheFly, fixes, type))
    }
  }

  protected fun services(root: YAMLMapping): List<YAMLKeyValue> = Yaml.keys(root.getKeyValueByKey("services")?.value)
}

/** Keys the Compose Specification does not define, at the top level, in services, `build`, `healthcheck` and `depends_on`. */
class ComposeUnknownKeysInspection : ComposeInspection() {
  override fun check(file: YAMLFile, root: YAMLMapping, model: ComposeModel, problems: Problems) {
    fun level(mapping: YAMLValue?, known: Set<String>, where: String) {
      for (kv in Yaml.keys(mapping)) {
        if (!ComposeSchema.allowed(kv.keyText, known)) {
          problems.add(kv.key ?: kv, PhantomDockerBundle.message("compose.unknown.key", kv.keyText, where))
        }
      }
    }
    level(root, ComposeSchema.TOP, "the top level")
    for (service in services(root)) {
      val body = service.value as? YAMLMapping ?: continue
      level(body, ComposeSchema.SERVICE, "a service")
      level(body.getKeyValueByKey("healthcheck")?.value, ComposeSchema.HEALTHCHECK, "healthcheck")
      level(body.getKeyValueByKey("build")?.value, ComposeSchema.BUILD, "build")
      for (dep in Yaml.keys(body.getKeyValueByKey("depends_on")?.value)) level(dep.value, ComposeSchema.DEPENDS_ON_ENTRY, "depends_on")
    }
  }
}

/** Values of the wrong shape or outside the allowed set: ports, environment, depends_on, condition, restart. */
class ComposeValuesInspection : ComposeInspection() {
  override fun check(file: YAMLFile, root: YAMLMapping, model: ComposeModel, problems: Problems) {
    for (service in services(root)) {
      val body = service.value as? YAMLMapping ?: continue
      body.getKeyValueByKey("ports")?.let { kv ->
        val v = kv.value
        if (v != null && v !is YAMLSequence) problems.add(v, PhantomDockerBundle.message("compose.value.list", "ports"))
      }
      body.getKeyValueByKey("environment")?.let { kv ->
        val v = kv.value
        if (v is YAMLScalar && v.textValue.isNotEmpty()) problems.add(v, PhantomDockerBundle.message("compose.value.list.or.map", "environment"))
      }
      body.getKeyValueByKey("depends_on")?.let { kv ->
        val v = kv.value
        if (v is YAMLScalar && v.textValue.isNotEmpty()) problems.add(v, PhantomDockerBundle.message("compose.value.list.or.map", "depends_on"))
        for (dep in Yaml.keys(v)) {
          val condition = (dep.value as? YAMLMapping)?.getKeyValueByKey("condition")?.value as? YAMLScalar ?: continue
          if (condition.textValue !in ComposeSchema.CONDITIONS) {
            problems.add(condition, PhantomDockerBundle.message("compose.value.condition", condition.textValue, ComposeSchema.CONDITIONS.joinToString(", ")))
          }
        }
      }
      (body.getKeyValueByKey("restart")?.value as? YAMLScalar)?.let { restart ->
        if (!restart.textValue.contains('$') && !ComposeSchema.validRestart(restart.textValue)) {
          problems.add(restart, PhantomDockerBundle.message("compose.value.restart", restart.textValue))
        }
      }
    }
  }
}

/**
 * Names that point nowhere: `depends_on`, `links`, `extends`, `volumes_from` to services, a service's `networks` to
 * the top-level networks and named volumes to the top-level volumes. Names from the other compose files in the same
 * directory count, and nothing is reported when the file uses `include`.
 */
class ComposeRelationsInspection : ComposeInspection() {
  override fun check(file: YAMLFile, root: YAMLMapping, model: ComposeModel, problems: Problems) {
    val scope = ComposeModel.withSiblings(file) ?: return
    if (scope.hasInclude) return
    for (service in services(root)) {
      val body = service.value as? YAMLMapping ?: continue
      for (target in ComposeReferences.serviceTargets(body)) {
        if (target.name !in scope.services) problems.add(target.element, PhantomDockerBundle.message("compose.relation.service", target.name))
      }
      for (target in ComposeReferences.networkTargets(body)) {
        if (target.name !in scope.networks && target.name != "default") problems.add(target.element, PhantomDockerBundle.message("compose.relation.network", target.name))
      }
      for (target in ComposeReferences.volumeTargets(body)) {
        if (target.name !in scope.volumes) problems.add(target.element, PhantomDockerBundle.message("compose.relation.volume", target.name))
      }
    }
  }
}

/** A service with neither `image` nor `build` (nor `extends`), unless another compose file beside it gives one. */
class ComposeMissingImageInspection : ComposeInspection() {
  override fun check(file: YAMLFile, root: YAMLMapping, model: ComposeModel, problems: Problems) {
    val scope = ComposeModel.withSiblings(file) ?: return
    if (scope.hasInclude) return
    for (service in services(root)) {
      if (ComposeModel.definesImage(service) || service.keyText in scope.siblingImages) continue
      problems.add(service.key ?: service, PhantomDockerBundle.message("compose.missing.image", service.keyText))
    }
  }
}

/**
 * Unquoted short-syntax ports such as `22:22`: YAML 1.1 parsers read digits separated by colons, each part after the
 * first below 60, as a base-60 number, so the port mapping turns into 1342.
 */
class ComposeUnquotedPortsInspection : ComposeInspection() {
  override fun check(file: YAMLFile, root: YAMLMapping, model: ComposeModel, problems: Problems) {
    for (service in services(root)) {
      val ports = (service.value as? YAMLMapping)?.getKeyValueByKey("ports")?.value as? YAMLSequence ?: continue
      for (item in ports.items) {
        val scalar = item.value as? YAMLScalar ?: continue
        if (scalar is YAMLQuotedText || !SEXAGESIMAL.matches(scalar.textValue)) continue
        problems.add(scalar, PhantomDockerBundle.message("compose.ports.unquoted", scalar.textValue), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, QuoteFix())
      }
    }
  }

  private class QuoteFix : LocalQuickFix {
    override fun getFamilyName(): String = PhantomDockerBundle.message("compose.ports.quote")
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val scalar = descriptor.psiElement as? YAMLScalar ?: return
      val quoted = YAMLElementGenerator.getInstance(project).createYamlKeyValue("x", "\"${scalar.textValue}\"").value ?: return
      scalar.replace(quoted)
    }
  }

  companion object {
    private val SEXAGESIMAL = Regex("""[1-9][0-9_]*(:[0-5]?[0-9])+""")
  }
}

/** Two services with the same `container_name`: the second container cannot be created. */
class ComposeDuplicateContainerNameInspection : ComposeInspection() {
  override fun check(file: YAMLFile, root: YAMLMapping, model: ComposeModel, problems: Problems) {
    val byName = services(root).mapNotNull { s ->
      ((s.value as? YAMLMapping)?.getKeyValueByKey("container_name")?.value as? YAMLScalar)?.takeIf { !it.textValue.contains('$') }
    }.groupBy { it.textValue }
    for ((name, scalars) in byName) {
      if (scalars.size > 1) scalars.forEach { problems.add(it, PhantomDockerBundle.message("compose.duplicate.container", name)) }
    }
  }
}
