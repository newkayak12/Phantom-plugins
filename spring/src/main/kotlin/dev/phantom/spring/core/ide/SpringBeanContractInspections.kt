// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInspection.*
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiUtil
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.*
import org.jetbrains.annotations.NonNls
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

@NonNls
internal object SpringContractNames {
  const val PROFILE: String = "org.springframework.context.annotation.Profile"
  const val ACTIVE_PROFILES: String = "org.springframework.test.context.ActiveProfiles"
  const val RESOURCE: String = "org.springframework.core.io.Resource"
  const val INFERRED: String = "(inferred)"

  /** Stereotypes whose `value` names the bean. */
  val NAMED_STEREOTYPES: List<String> = listOf(
    SpringNames.COMPONENT,
    "org.springframework.stereotype.Service",
    "org.springframework.stereotype.Repository",
    "org.springframework.stereotype.Controller",
    "org.springframework.web.bind.annotation.RestController",
    SpringProxies.CONFIGURATION,
  )

  /** Resource prefixes Spring's resource loaders understand. */
  val RESOURCE_PREFIXES: Set<String> = setOf("classpath", "classpath*", "file", "http", "https", "jar", "war", "ftp", "zip", "vfs", "vfsfile", "vfszip", "wsjar")
}

/**
 * `@Bean(initMethod = "x")` / `@Bean(destroyMethod = "y")` naming no method of the bean type: the context fails to
 * start (init) or to close (destroy). An init method takes no parameters; a destroy method none or one `boolean`.
 * Only checked when the declared type is a concrete class, since an interface or abstract type says little about
 * what the factory returns.
 */
class SpringBeanLifecycleMethodInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        val bean = node.findAnnotation(SpringNames.BEAN) ?: return true
        val type = PsiUtil.resolveClassInClassTypeOnly(node.returnType) ?: return true
        if (type.isInterface || type.hasModifierProperty(PsiModifier.ABSTRACT) || type is PsiTypeParameter) return true
        check(bean, "initMethod", type, destroy = false)
        check(bean, "destroyMethod", type, destroy = true)
        return true
      }

      private fun check(bean: UAnnotation, attribute: String, type: PsiClass, destroy: Boolean) {
        val value = bean.findDeclaredAttributeValue(attribute) ?: return
        val name = value.evaluateString()?.trim() ?: return
        if (name.isEmpty() || name == SpringContractNames.INFERRED || name.contains("\${") || name.contains("#{")) return
        val methods = type.findMethodsByName(name, true)
        val fits = methods.any { m ->
          val parameters = m.parameterList.parameters
          parameters.isEmpty() || destroy && parameters.size == 1 && parameters[0].type == PsiTypes.booleanType()
        }
        if (fits) return
        val key = if (methods.isEmpty()) "inspection.bean.lifecycle.missing" else "inspection.bean.lifecycle.parameters"
        holder.registerProblem(anchorOf(value) ?: return, PhantomSpringBundle.message(key, name, type.name ?: "", attribute))
      }
    }, arrayOf(UMethod::class.java), true)
  }
}

/**
 * `@Lookup` methods are overridden by Spring with CGLIB: they must not be private or static (or final in Java), and
 * must return the bean to look up, so not void.
 */
class SpringLookupMethodInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        val lookup = node.findAnnotation(SpringDefinitionNames.LOOKUP) ?: return true
        val method = node.javaPsi
        val anchor = lookup.sourcePsi ?: return true
        val java = node.sourcePsi?.language == JavaLanguage.INSTANCE
        val reason = when {
          method.hasModifierProperty(PsiModifier.PRIVATE) -> "private"
          method.hasModifierProperty(PsiModifier.STATIC) -> "static"
          // Kotlin functions in components are opened by the Spring compiler plugin
          java && method.hasModifierProperty(PsiModifier.FINAL) -> "final"
          else -> null
        }
        if (reason != null) holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.lookup.modifier", reason))
        val returnType = node.returnType
        if (returnType == null || returnType == PsiTypes.voidType() || returnType.equalsToText("kotlin.Unit")) {
          holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.lookup.void"))
        }
        return true
      }
    }, arrayOf(UMethod::class.java), true)
  }
}

/**
 * `@Value` into a `Resource` (or `Resource[]`) with a prefix no resource loader knows (`clas:`, `filee:`), or a
 * `classpath:` location that is not on the module's classpath. Plain paths are left alone: the application context
 * decides what they mean.
 */
class SpringResourceValueInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  private val prefix = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*\*?):""")

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        if (node.qualifiedName != SpringNames.VALUE) return true
        val target = node.uastParent as? UVariable ?: return true
        val type = target.type.let { if (it is PsiArrayType) it.componentType else it }
        if ((type as? PsiClassType)?.resolve()?.qualifiedName != SpringContractNames.RESOURCE) return true
        val value = node.findDeclaredAttributeValue("value") ?: return true
        val text = value.evaluateString()?.trim() ?: return true
        if (text.isEmpty() || text.contains("\${") || text.contains("#{")) return true
        val anchor = anchorOf(value) ?: return true
        val scheme = prefix.find(text)?.groupValues?.get(1)
        // a single letter is a Windows drive (C:)
        if (scheme == null || scheme.length == 1) return true
        if (scheme.lowercase() !in SpringContractNames.RESOURCE_PREFIXES) {
          holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.resource.value.prefix", "$scheme:"))
          return true
        }
        if (scheme == "classpath") {
          val owner = target.getContainingUClass()?.javaPsi ?: return true
          if (!resourceExists(owner, text, false)) holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.resource.value.missing", text))
        }
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }
}

/** Spring's profile expressions: names joined by `!`, `&`, `|` and parentheses, with `&` and `|` not mixed without parentheses. */
object SpringProfileExpressions {
  /** Why the expression cannot parse, or null. */
  fun problem(expression: String): String? {
    if (expression.isBlank()) return "empty profile"
    val tokens = Regex("""[()!&|]|[^\s()!&|]+""").findAll(expression).map { it.value }.toList()
    var i = 0
    fun peek(): String? = tokens.getOrNull(i)
    fun isName(t: String?) = t != null && t !in setOf("(", ")", "!", "&", "|")
    lateinit var expr: () -> String?
    fun term(): String? {
      val t = peek() ?: return "missing a profile name at the end"
      return when {
        t == "!" -> { i++; term() }
        t == "(" -> { i++; expr() ?: if (peek() == ")") { i++; null } else "missing ')'" }
        isName(t) -> { i++; null }
        else -> "unexpected '$t'"
      }
    }
    expr = {
      var problem = term()
      var op: String? = null
      while (problem == null && (peek() == "&" || peek() == "|")) {
        val next = peek()!!
        if (op != null && op != next) { problem = "mixing '&' and '|' needs parentheses"; break }
        op = next
        i++
        problem = term()
      }
      problem
    }
    val problem = expr()
    if (problem != null) return problem
    return if (i < tokens.size) "unexpected '${tokens[i]}'" else null
  }

  fun names(expression: String): List<String> =
    Regex("""[^\s()!&|]+""").findAll(expression).map { it.value }.toList()
}

/** `@Profile` expressions Spring cannot parse fail when the context starts. */
class SpringProfileExpressionInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        if (node.qualifiedName != SpringContractNames.PROFILE) return true
        for (item in attributeItems(node, "value")) {
          val text = item.evaluateString() ?: continue
          val problem = SpringProfileExpressions.problem(text) ?: continue
          holder.registerProblem(anchorOf(item) ?: continue, PhantomSpringBundle.message("inspection.profile.expression.message", problem))
        }
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }
}

/**
 * A profile in `@Profile` that nothing in the project defines or activates: no `application-<name>` file, no
 * `spring.profiles.*` or `on-profile` setting, no `@ActiveProfiles`. Often a typo; it can also come from an
 * environment variable, hence a weak warning. Skipped when the project has no configuration files.
 */
class SpringUnknownProfileInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    val known by lazy { KnownProfiles.of(holder.project) }
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        if (node.qualifiedName != SpringContractNames.PROFILE) return true
        val profiles = known ?: return true
        for (item in attributeItems(node, "value")) {
          val text = item.evaluateString() ?: continue
          if (SpringProfileExpressions.problem(text) != null) continue
          val unknown = SpringProfileExpressions.names(text).filter { it !in profiles && !it.contains("\${") }
          if (unknown.isEmpty()) continue
          holder.registerProblem(anchorOf(item) ?: continue, PhantomSpringBundle.message("inspection.profile.unknown.message", unknown.joinToString(", ")))
        }
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }
}

/** Profile names the project mentions, or null when it has no configuration files to judge by. */
private object KnownProfiles {
  private val WORD = Regex("""[A-Za-z0-9_.\-]+""")
  private val PROFILE_FILE = Regex("""^(?:application|bootstrap)-(.+)\.(?:properties|ya?ml)$""")

  // the config files are read once per set of config keys, the @ActiveProfiles classes searched on code changes only
  fun of(project: Project): Set<String>? {
    val keys = SpringConfigKeys.get(project)
    if (keys.files.isEmpty()) return null
    return keys.derived("profiles", ::fromConfig) + fromActiveProfiles(project)
  }

  private fun fromConfig(keys: SpringConfigKeys.Keys): Set<String> {
    val known = HashSet<String>()
    known += "default"
    for (file in keys.files) {
      PROFILE_FILE.find(file.name)?.let { known += it.groupValues[1] }
      val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: continue
      profileValues(text, known)
    }
    return known
  }

  /** Values of profile settings, generously: the line naming a profile key and everything nested under it. */
  private fun profileValues(text: String, known: MutableSet<String>) {
    var blockIndent = -1
    for (line in text.lines()) {
      val trimmed = line.trimStart()
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
      val indent = line.length - trimmed.length
      if (isNested(blockIndent, indent, trimmed)) {
        val value = if (trimmed.startsWith("-")) trimmed.removePrefix("-") else trimmed.substringAfter(':', "")
        WORD.findAll(value).forEach { known += it.value }
        continue
      }
      blockIndent = -1
      if (!line.contains("profile", ignoreCase = true)) continue
      val value = line.substringAfter('=', line.substringAfter(':', ""))
      WORD.findAll(value).forEach { known += it.value }
      blockIndent = indent
    }
  }

  /** A line under the profile key's line at [blockIndent] (none when negative): deeper, or a list item at the same depth. */
  private fun isNested(blockIndent: Int, indent: Int, trimmed: String): Boolean =
    blockIndent >= 0 && (indent > blockIndent || indent == blockIndent && trimmed.startsWith("-"))

  private fun fromActiveProfiles(project: Project): Set<String> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val known = HashSet<String>()
    val facade = JavaPsiFacade.getInstance(project)
    facade.findClass(SpringContractNames.ACTIVE_PROFILES, GlobalSearchScope.allScope(project))?.let { annotation ->
      AnnotatedElementsSearch.searchPsiClasses(annotation, GlobalSearchScope.projectScope(project)).forEach { psiClass ->
        val active = psiClass.getAnnotation(SpringContractNames.ACTIVE_PROFILES) ?: return@forEach
        for (attribute in listOf("value", "profiles")) {
          com.intellij.codeInsight.AnnotationUtil.arrayAttributeValues(active.findDeclaredAttributeValue(attribute)).forEach { v ->
            (facade.constantEvaluationHelper.computeConstantExpression(v) as? String)?.let { WORD.findAll(it).forEach { m -> known += m.value } }
          }
        }
      }
    }
    CachedValueProvider.Result.create<Set<String>>(known, SpringCacheDeps.jvm(project), ProjectRootManager.getInstance(project))
  }
}

/** Bean names are lowerCamelCase by convention (`userService`); `UserService` reads like a type and breaks `@Qualifier` guesses. */
class SpringBeanNameConventionInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        val fqn = node.qualifiedName ?: return true
        val items = when {
          fqn in SpringContractNames.NAMED_STEREOTYPES -> attributeItems(node, "value")
          fqn == SpringNames.BEAN -> attributeItems(node, "name") + attributeItems(node, "value")
          else -> return true
        }
        for (item in items) {
          val name = item.evaluateString() ?: continue
          if (!isTypeLike(name)) continue
          holder.registerProblem(anchorOf(item) ?: continue, PhantomSpringBundle.message("inspection.bean.name.convention.message", name, SpringBeans.decapitalize(name)))
        }
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }

  /** Starts with an upper-case letter followed by a lower-case one (`URLFetcher` stays as is, as `Introspector` would). */
  private fun isTypeLike(name: String): Boolean =
    name.length >= 2 && name[0].isUpperCase() && name[1].isLowerCase() && name.none { it == '.' || it == '-' || it == '#' || it == '$' }
}

/**
 * `argNames` names a binding the pointcut never uses: the advice parameter at that position gets nothing bound.
 * Names that match `returning` or `throwing` are bound by those attributes.
 */
class AopArgNamesBindingInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!AopModel.isAopProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        val annotation = AopModel.annotationOf(node) ?: return true
        val value = annotation.findDeclaredAttributeValue("argNames") ?: return true
        val names = value.evaluateString()?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: return true
        val expression = AopModel.expressionOf(annotation)?.evaluateString() ?: return true
        if (expression.isBlank()) return true
        val bound = setOfNotNull(
          annotation.findDeclaredAttributeValue("returning")?.evaluateString(),
          annotation.findDeclaredAttributeValue("throwing")?.evaluateString(),
        )
        val unused = names.filter { it !in bound && !Regex("""(?<![\w$.])${Regex.escape(it)}(?![\w$])""").containsMatchIn(expression) }
        if (unused.isEmpty()) return true
        holder.registerProblem(value.sourcePsi ?: return true, PhantomSpringBundle.message("aop.arg.names.unbound", unused.joinToString(", ")))
        return true
      }
    }, arrayOf(UMethod::class.java), true)
  }
}
