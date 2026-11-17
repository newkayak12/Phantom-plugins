// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiUtil

private fun configText(file: PsiFile): String =
  (PsiDocumentManager.getInstance(file.project).getDocument(file)?.charsSequence ?: file.viewProvider.contents).toString()

private fun hasClass(project: Project, scope: GlobalSearchScope, fqn: String) = JavaPsiFacade.getInstance(project).findClass(fqn, scope) != null

/**
 * A `bootstrap.yml` / `bootstrap.properties` that nothing reads: Spring Boot itself only reads `application.*`, and
 * Spring Cloud 2020.0 and later reads bootstrap files only with `spring-cloud-starter-bootstrap` (or the legacy
 * switch) on the classpath.
 */
class BootBootstrapConfigInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
    override fun visitFile(file: PsiFile) {
      if (!BOOTSTRAP.matches(file.name)) return
      val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return
      if (!SpringBootGate.isBootModule(module)) return
      val scope = module.getModuleWithDependenciesAndLibrariesScope(false)
      val project = file.project
      val key = when {
        !hasClass(project, scope, CLOUD_CONTEXT) -> "inspection.bootstrap.no.cloud"
        // before 2020.0 bootstrap files were always read
        !hasClass(project, scope, BOOTSTRAP_SWITCH) -> return
        hasClass(project, scope, BOOTSTRAP_MARKER) -> return
        ProjectConfigKeys.keys(project).keys.any { key -> LEGACY_SWITCHES.any { ConfigNames.normalize(it) == ConfigNames.normalize(key) } } -> return
        else -> "inspection.bootstrap.no.starter"
      }
      val text = configText(file)
      val firstLine = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return
      val range = TextRange(firstLine, lineEndOf(text, firstLine))
      holder.registerProblem(file, PhantomSpringBootBundle.message(key), ProblemHighlightType.WARNING, range)
    }
  }

  private companion object {
    val BOOTSTRAP = Regex("""bootstrap([-.][\w.-]*)?\.(ya?ml|properties)""")
    const val CLOUD_CONTEXT = "org.springframework.cloud.bootstrap.BootstrapConfiguration"
    /** Added in Spring Cloud 2020.0 together with the switch that turned bootstrap off by default. */
    const val BOOTSTRAP_SWITCH = "org.springframework.cloud.util.PropertyUtils"
    const val BOOTSTRAP_MARKER = "org.springframework.cloud.bootstrap.marker.Marker"
    val LEGACY_SWITCHES = setOf("spring.cloud.bootstrap.enabled", "spring.config.use-legacy-processing")
  }
}

/** Function beans (`Function`, `Consumer`, `Supplier`) Spring Cloud Function and Stream bind by bean name. */
internal object FunctionBeans {
  private val FUNCTION_TYPES = listOf("java.util.function.Function", "java.util.function.Consumer", "java.util.function.Supplier",
                                      "java.util.function.BiFunction", "java.util.function.BiConsumer")
  private const val BEAN = "org.springframework.context.annotation.Bean"
  private const val COMPONENT = "org.springframework.stereotype.Component"

  /** Bean names, or null when no function bean is found at all (they may come from where this does not look). */
  fun names(project: Project): Set<String>? = CachedValuesManager.getManager(project).getCachedValue(project) {
    CachedValueProvider.Result.create(compute(project), PsiModificationTracker.MODIFICATION_COUNT)
  }

  private fun compute(project: Project): Set<String>? {
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val scope = GlobalSearchScope.projectScope(project)
    val result = LinkedHashSet<String>()
    val bean = facade.findClass(BEAN, all)
    if (bean != null) {
      for (method in AnnotatedElementsSearch.searchPsiMethods(bean, scope).findAll()) {
        val type = method.returnType?.let { PsiUtil.resolveClassInClassTypeOnly(it) } ?: continue
        if (!isFunction(type)) continue
        val annotation = method.getAnnotation(BEAN) ?: continue
        val names = listOf("name", "value").flatMap { strings(annotation, it) }
        if (names.isEmpty()) result.add(method.name) else result.addAll(names)
      }
    }
    for (fqn in FUNCTION_TYPES) {
      val base = facade.findClass(fqn, all) ?: continue
      for (psiClass in ClassInheritorsSearch.search(base, scope, true)) {
        if (psiClass.isInterface || !MetaAnnotationUtil.isMetaAnnotated(psiClass, listOf(COMPONENT))) continue
        val name = psiClass.name ?: continue
        result.add(name.replaceFirstChar { it.lowercaseChar() })
      }
    }
    return result.takeIf { it.isNotEmpty() }
  }

  private fun strings(annotation: PsiAnnotation, attribute: String): List<String> {
    val value = annotation.findDeclaredAttributeValue(attribute) ?: return emptyList()
    val values = (value as? PsiArrayInitializerMemberValue)?.initializers?.toList() ?: listOf(value)
    val helper = JavaPsiFacade.getInstance(annotation.project).constantEvaluationHelper
    return values.mapNotNull { (it as? PsiExpression)?.let { e -> helper.computeConstantExpression(e) as? String } }.filter { it.isNotBlank() }
  }

  private fun isFunction(psiClass: PsiClass): Boolean {
    val fqn = psiClass.qualifiedName ?: return false
    return fqn in FUNCTION_TYPES || fqn.startsWith("kotlin.jvm.functions.Function") || FUNCTION_TYPES.any { com.intellij.psi.util.InheritanceUtil.isInheritor(psiClass, it) }
  }
}

/**
 * Spring Cloud Stream bindings named after functions that do not exist: `spring.cloud.stream.bindings.<fn>-in-<n>`,
 * `spring.cloud.stream.function.bindings.<fn>-in-<n>` and the names in `spring.cloud.function.definition`. Output
 * bindings are not checked, since `StreamBridge` creates them under any name.
 */
class BootCloudStreamBindingInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
    override fun visitFile(file: PsiFile) {
      if (!BootConfigFiles.isConfigFile(file)) return
      val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return
      if (!SpringBootGate.isBootModule(module)) return
      if (!hasClass(file.project, module.getModuleWithDependenciesAndLibrariesScope(false), STREAM)) return
      val functions = FunctionBeans.names(file.project) ?: return
      val text = configText(file)
      val entries = if (file.name.endsWith(".properties")) ConfigText.properties(text) else ConfigText.yaml(text)
      val reported = HashSet<Int>()
      for (entry in entries) {
        val key = entry.key
        val binding = BINDING_PREFIXES.firstNotNullOfOrNull { prefix -> key.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.substringBefore('.') }
        if (binding != null) {
          val function = INPUT.matchEntire(binding)?.groupValues?.get(1) ?: continue
          if (composed(function, functions)) continue
          val at = text.lastIndexOf(binding, entry.offset + binding.length).takeIf { it >= 0 } ?: continue
          if (!reported.add(at)) continue
          holder.registerProblem(file, PhantomSpringBootBundle.message("inspection.stream.unknown.function", function, binding),
                                 ProblemHighlightType.GENERIC_ERROR_OR_WARNING, TextRange(at, at + binding.length))
          continue
        }
        if (ConfigNames.normalize(key) != DEFINITION) continue
        val valueStart = text.indexOf(entry.value, entry.offset).takeIf { it >= 0 && entry.value.isNotEmpty() } ?: continue
        var offset = 0
        for (name in entry.value.split(';', '|', ',')) {
          val start = entry.value.indexOf(name, offset)
          offset = start + name.length
          val trimmed = name.trim()
          if (trimmed.isEmpty() || trimmed in functions || trimmed.contains("\${")) continue
          val at = valueStart + start + name.indexOf(trimmed)
          holder.registerProblem(file, PhantomSpringBootBundle.message("inspection.stream.unknown.definition", trimmed),
                                 ProblemHighlightType.GENERIC_ERROR_OR_WARNING, TextRange(at, at + trimmed.length))
        }
      }
    }
  }

  /** A known function, or a composition (`a|b` binds as `ab-in-0`) of known functions. */
  private fun composed(name: String, functions: Set<String>): Boolean {
    if (name in functions) return true
    return functions.any { f -> f.isNotEmpty() && name.length > f.length && name.startsWith(f) && composed(name.substring(f.length), functions) }
  }

  private companion object {
    const val STREAM = "org.springframework.cloud.stream.function.StreamBridge"
    const val DEFINITION = "spring.cloud.function.definition"
    val BINDING_PREFIXES = listOf("spring.cloud.stream.bindings.", "spring.cloud.stream.function.bindings.")
    val INPUT = Regex("""(.+)-in-\d+""")
  }
}
