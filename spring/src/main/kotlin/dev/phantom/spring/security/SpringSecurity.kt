// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.security

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.icons.AllIcons
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.uast.UastHintedVisitorAdapter
import com.intellij.util.ProcessingContext
import dev.phantom.spring.core.SpringMvcEndpoints
import dev.phantom.spring.core.SpringProjectGate
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UNamedExpression
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

internal object SecurityGate {
  private const val MARKER = "org.springframework.security.core.Authentication"

  fun isSecurityProject(project: Project): Boolean {
    if (!SpringProjectGate.isSpringProject(project)) return false
    if (JavaLibraryUtil.hasLibraryClass(project, MARKER)) return true
    return ApplicationManager.getApplication().isUnitTestMode &&
           JavaPsiFacade.getInstance(project).findClass(MARKER, GlobalSearchScope.allScope(project)) != null
  }
}

/** A string literal and where its value starts in the file, for working on the text before the caret. */
internal class SecurityString(val host: UInjectionHost, val psi: PsiLanguageInjectionHost) {
  val valueStart: Int get() = psi.textRange.startOffset + ElementManipulators.getValueTextRange(psi).startOffset
  val value: String get() = ElementManipulators.getValueTextRange(psi).substring(psi.text)

  companion object {
    fun at(element: PsiElement): SecurityString? {
      val host = element.getUastParentOfType(UInjectionHost::class.java) ?: return null
      val psi = host.psiLanguageInjectionHost.takeIf { it.isValidHost } ?: return null
      return SecurityString(host, psi)
    }
  }
}

/** Method security annotations whose value is a SpEL expression evaluated against the method's arguments. */
internal object MethodSecurity {
  val EXPRESSION_ANNOTATIONS = setOf(
    "org.springframework.security.access.prepost.PreAuthorize",
    "org.springframework.security.access.prepost.PostAuthorize",
    "org.springframework.security.access.prepost.PreFilter",
    "org.springframework.security.access.prepost.PostFilter",
  )
  private val NAME_ANNOTATIONS = listOf("org.springframework.security.core.parameters.P", "org.springframework.data.repository.query.Param")

  /** The functions and variables the default method security expression root offers. */
  val FUNCTIONS = listOf(
    "hasRole('')", "hasAnyRole('')", "hasAuthority('')", "hasAnyAuthority('')", "hasPermission(, '')",
    "isAuthenticated()", "isFullyAuthenticated()", "isAnonymous()", "isRememberMe()",
  )
  val VARIABLES = listOf("permitAll", "denyAll", "principal", "authentication", "returnObject", "filterObject")
  val SPEL_BUILTINS = setOf("this", "root")
  val REFERENCE = Regex("""#([A-Za-z_]\w*)""")

  /** The annotation the string is the value of, when it is a method security expression. */
  fun annotationOf(string: SecurityString): UAnnotation? {
    val parent = string.host.uastParent
    val annotation = (parent as? UNamedExpression)?.uastParent as? UAnnotation ?: parent as? UAnnotation ?: return null
    return annotation.takeIf { it.qualifiedName in EXPRESSION_ANNOTATIONS }
  }

  /** Names `#name` can refer to: parameter names, and the names given with `@P` or `@Param`. */
  fun argumentNames(method: UMethod): Set<String> = method.uastParameters.flatMapTo(HashSet()) { parameter ->
    val psi = parameter.javaPsi as? com.intellij.psi.PsiParameter
    val given = NAME_ANNOTATIONS.mapNotNull { fqn -> psi?.getAnnotation(fqn)?.let { AnnotationUtil.getStringAttributeValue(it, "value") } }
    given + parameter.name
  }
}

/** Calls in the HTTP security configuration whose string arguments are request paths. */
internal object RequestMatchers {
  private val METHODS = setOf("requestMatchers", "antMatchers", "mvcMatchers", "regexMatchers", "securityMatcher", "securityMatchers", "authorize")

  fun isPathArgument(string: SecurityString): Boolean {
    val call = string.host.uastParent as? UCallExpression ?: return false
    if (call.methodName !in METHODS || string.host !in call.valueArguments) return false
    val owner = call.resolve()?.containingClass?.qualifiedName ?: return false
    return owner.startsWith("org.springframework.security.")
  }

  // Every handler path, plus a double-star pattern for each path prefix: "/api/v1/menu/{id}" also gives "/api/" + "**" and so on
  fun suggestions(project: Project): List<Pair<String, String>> {
    val result = LinkedHashMap<String, String>()
    for (endpoint in SpringMvcEndpoints.all(project)) {
      val path = SpringMvcEndpoints.readablePath(endpoint.path)
      result.putIfAbsent(path, "${endpoint.methods.joinToString("|").ifEmpty { "ANY" }} ${endpoint.controllerName}.${endpoint.handlerName}")
      val segments = path.trim('/').split('/').takeWhile { !it.startsWith("{") }
      for (i in 1..segments.size) result.putIfAbsent("/" + segments.take(i).joinToString("/") + "/**", "")
    }
    return result.entries.map { it.key to it.value }.sortedBy { it.first }
  }
}

/**
 * Completion in Spring Security strings: request paths of the project's handlers in `requestMatchers("…")` (and the
 * Kotlin DSL's `authorize("…")`), and functions, variables and `#arguments` in `@PreAuthorize` and friends.
 */
class SpringSecurityCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
      override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val position = parameters.position
        if (!SecurityGate.isSecurityProject(position.project)) return
        val string = SecurityString.at(position) ?: return
        val before = parameters.offset - string.valueStart
        if (before < 0) return
        val typed = position.containingFile.text.substring(string.valueStart, parameters.offset)

        if (RequestMatchers.isPathArgument(string)) {
          val paths = result.withPrefixMatcher(typed)
          for ((path, handler) in RequestMatchers.suggestions(position.project)) {
            paths.addElement(LookupElementBuilder.create(path).withIcon(AllIcons.Webreferences.Server).withTypeText(handler, true))
          }
          result.stopHere()
          return
        }
        if (MethodSecurity.annotationOf(string) == null) return
        val word = Regex("""(#?)([A-Za-z_]\w*)?$""").find(typed) ?: return
        val words = result.withPrefixMatcher(word.groupValues[2])
        if (word.groupValues[1] == "#") {
          val method = string.host.getParentOfType<UMethod>() ?: return
          MethodSecurity.argumentNames(method).sorted().forEach { words.addElement(LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.Parameter)) }
        }
        else {
          MethodSecurity.FUNCTIONS.forEach { words.addElement(LookupElementBuilder.create(it).withPresentableText(it.substringBefore('(') + "()").withIcon(AllIcons.Nodes.Method)) }
          MethodSecurity.VARIABLES.forEach { words.addElement(LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.Variable)) }
        }
        result.stopHere()
      }
    })
  }
}

/**
 * `#name` in a method security expression that is not one of the method's arguments. Spring evaluates it to null,
 * so `#userId == principal.id` silently denies (or, negated, grants) access.
 */
class MethodSecurityExpressionInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SecurityGate.isSecurityProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        val names by lazy { MethodSecurity.argumentNames(node) }
        for (annotation in node.uAnnotations) {
          if (annotation.qualifiedName !in MethodSecurity.EXPRESSION_ANNOTATIONS) continue
          val host = annotation.findDeclaredAttributeValue("value") as? UInjectionHost ?: continue
          val psi = host.psiLanguageInjectionHost
          val valueRange = ElementManipulators.getValueTextRange(psi)
          val value = valueRange.substring(psi.text)
          for (match in MethodSecurity.REFERENCE.findAll(value)) {
            val name = match.groupValues[1]
            if (name in names || name in MethodSecurity.SPEL_BUILTINS) continue
            val range = TextRange(valueRange.startOffset + match.range.first, valueRange.startOffset + match.range.last + 1)
            holder.registerProblem(psi, range, PhantomSecurityMessages.unknownArgument(name))
          }
        }
        return true
      }
    }, arrayOf(UMethod::class.java), true)
  }
}

internal object PhantomSecurityMessages {
  fun unknownArgument(name: String): String = dev.phantom.spring.core.PhantomSpringBundle.message("security.unknown.argument", name)
}

/** Which kinds of method security annotations the project's `@Enable…MethodSecurity` configuration switches on. */
internal object MethodSecuritySwitches {
  private const val ENABLE = "org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity"
  private const val ENABLE_GLOBAL = "org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity"
  private const val ENABLE_REACTIVE = "org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity"

  enum class Kind(val attribute: String) { PRE_POST("prePostEnabled"), SECURED("securedEnabled"), JSR250("jsr250Enabled") }

  val ANNOTATIONS: Map<String, Kind> = mapOf(
    "org.springframework.security.access.prepost.PreAuthorize" to Kind.PRE_POST,
    "org.springframework.security.access.prepost.PostAuthorize" to Kind.PRE_POST,
    "org.springframework.security.access.prepost.PreFilter" to Kind.PRE_POST,
    "org.springframework.security.access.prepost.PostFilter" to Kind.PRE_POST,
    "org.springframework.security.access.annotation.Secured" to Kind.SECURED,
    "jakarta.annotation.security.RolesAllowed" to Kind.JSR250,
    "javax.annotation.security.RolesAllowed" to Kind.JSR250,
  )

  /**
   * The enabled kinds, or null when nothing can be said: the enabling annotations are not on the classpath (an
   * unusual setup this check does not know). Searched in sources and libraries, cached until the next PSI change.
   */
  fun state(project: Project): Found? = com.intellij.psi.util.CachedValuesManager.getManager(project).getCachedValue(project) {
    com.intellij.psi.util.CachedValueProvider.Result.create(find(project), com.intellij.psi.util.PsiModificationTracker.getInstance(project))
  }?.takeIf { it.known }

  /** [used]: some configuration carries an enabling annotation; [kinds]: what it switches on. */
  class Found(val known: Boolean, val used: Boolean, val kinds: Set<Kind>)

  private fun find(project: Project): Found {
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val classes = listOf(ENABLE, ENABLE_GLOBAL, ENABLE_REACTIVE).mapNotNull { fqn -> facade.findClass(fqn, all)?.let { fqn to it } }
    if (classes.isEmpty()) return Found(false, false, emptySet())
    val kinds = HashSet<Kind>()
    var used = false
    for ((fqn, annotationClass) in classes) {
      com.intellij.psi.search.searches.AnnotatedElementsSearch.searchPsiClasses(annotationClass, all).forEach { configuration ->
        val annotation = configuration.getAnnotation(fqn) ?: return@forEach
        used = true
        if (fqn == ENABLE_REACTIVE) { kinds += Kind.entries; return@forEach }
        for (kind in Kind.entries) {
          // @EnableMethodSecurity turns pre/post on by default; @EnableGlobalMethodSecurity turns nothing on by default
          val default = fqn == ENABLE && kind == Kind.PRE_POST
          if (flag(annotation, kind.attribute) ?: default) kinds += kind
        }
      }
    }
    return Found(true, used, kinds)
  }

  private fun flag(annotation: com.intellij.psi.PsiAnnotation, name: String): Boolean? {
    val value = annotation.findDeclaredAttributeValue(name) ?: return null
    return (JavaPsiFacade.getInstance(annotation.project).constantEvaluationHelper.computeConstantExpression(value) as? Boolean)
           ?: value.text.trim().toBooleanStrictOrNull()
  }
}

/**
 * `@PreAuthorize`, `@Secured` or `@RolesAllowed` that Spring ignores because no configuration enables that kind of
 * method security: the method runs for everyone. Quiet when the enabling annotations cannot be found at all.
 */
class MethodSecurityDisabledInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SecurityGate.isSecurityProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean { check(node.uAnnotations); return true }
      override fun visitClass(node: org.jetbrains.uast.UClass): Boolean { check(node.uAnnotations); return true }

      private fun check(annotations: List<UAnnotation>) {
        for (annotation in annotations) {
          val kind = MethodSecuritySwitches.ANNOTATIONS[annotation.qualifiedName] ?: continue
          val state = MethodSecuritySwitches.state(holder.project) ?: return
          if (kind in state.kinds) continue
          val anchor = annotation.sourcePsi ?: continue
          val name = "@" + annotation.qualifiedName!!.substringAfterLast('.')
          val message = if (!state.used) dev.phantom.spring.core.PhantomSpringBundle.message("security.disabled.none", name)
                        else dev.phantom.spring.core.PhantomSpringBundle.message("security.disabled.attribute", name, "${kind.attribute} = true")
          holder.registerProblem(anchor, message)
        }
      }
    }, arrayOf(UMethod::class.java, org.jetbrains.uast.UClass::class.java), true)
  }
}

/** `@EnableWebSecurity(debug = true)` in production code: Spring Security logs every request with its headers and session. */
class SecurityDebugInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SecurityGate.isSecurityProject(holder.project) || dev.phantom.spring.core.SpringBeans.isInTestSources(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        if (node.qualifiedName != ENABLE_WEB_SECURITY) return true
        val debug = node.findDeclaredAttributeValue("debug") ?: return true
        if (debug.evaluate() != true) return true
        holder.registerProblem(debug.sourcePsi?.parent?.takeIf { it.textLength < 40 } ?: debug.sourcePsi ?: return true,
                               dev.phantom.spring.core.PhantomSpringBundle.message("security.debug.message"))
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }

  companion object {
    const val ENABLE_WEB_SECURITY: String = "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity"
  }
}

/**
 * A call from a bean into its own secured method (`@PreAuthorize`, `@Secured`, `@RolesAllowed`…): the call does not
 * go through the proxy, so the check does not run for it. A caller with the very same annotation is not reported.
 */
class SecuritySelfInvocationInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SecurityGate.isSecurityProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitCallExpression(node: UCallExpression): Boolean {
        if (node.kind != org.jetbrains.uast.UastCallKind.METHOD_CALL) return true
        val receiver = node.receiver
        if (receiver != null && receiver !is org.jetbrains.uast.UThisExpression) return true
        val callee = node.resolve() ?: return true
        val secured = callee.annotations.firstOrNull { it.qualifiedName in SECURED } ?: return true
        val caller = node.getParentOfType<UMethod>(true) ?: return true
        val callerClass = caller.javaPsi.containingClass ?: return true
        if (node.getParentOfType<org.jetbrains.uast.UClass>(true)?.javaPsi != callerClass) return true
        val calleeClass = callee.containingClass ?: return true
        if (!com.intellij.psi.util.InheritanceUtil.isInheritorOrSelf(callerClass, calleeClass, true)) return true
        if (!dev.phantom.spring.core.SpringBeans.isBeanClass(callerClass)) return true
        if (caller.javaPsi.annotations.any { it.qualifiedName == secured.qualifiedName && it.text == secured.text }) return true
        val anchor = node.methodIdentifier?.sourcePsi ?: node.sourcePsi ?: return true
        val name = "@" + secured.qualifiedName!!.substringAfterLast('.')
        holder.registerProblem(anchor, dev.phantom.spring.core.PhantomSpringBundle.message("security.self.invocation.message", name, callee.name))
        return true
      }
    }, arrayOf(UCallExpression::class.java), true)
  }

  companion object {
    val SECURED: Set<String> = MethodSecurity.EXPRESSION_ANNOTATIONS + setOf(
      "org.springframework.security.access.annotation.Secured",
      "jakarta.annotation.security.RolesAllowed",
      "javax.annotation.security.RolesAllowed",
    )
  }
}

/**
 * `@name` bean references in method security expressions that are a near miss of a project bean name (other case,
 * one letter off). Names close to no project bean are not reported: library beans are not known here.
 */
class SecurityBeanReferenceInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SecurityGate.isSecurityProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        if (node.qualifiedName !in MethodSecurity.EXPRESSION_ANNOTATIONS) return true
        val host = node.findDeclaredAttributeValue("value") as? UInjectionHost ?: return true
        val psi = host.psiLanguageInjectionHost
        val valueRange = ElementManipulators.getValueTextRange(psi)
        // blank out quoted text so an e-mail address in a literal is not read as a bean reference
        val value = valueRange.substring(psi.text).replace(Regex("'[^']*'")) { " ".repeat(it.value.length) }
        for (match in BEAN_REFERENCE.findAll(value)) {
          val name = match.groupValues[1]
          val meant = dev.phantom.spring.core.ide.SpringBeanNameTypos.nearMiss(holder.project, name) ?: continue
          val start = valueRange.startOffset + match.groups[1]!!.range.first
          holder.registerProblem(psi, TextRange(start, start + name.length),
                                 dev.phantom.spring.core.PhantomSpringBundle.message("inspection.bean.name.typo.message", name, meant))
        }
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }

  companion object {
    private val BEAN_REFERENCE = Regex("""(?<![\w.])@([A-Za-z_]\w*)""")
  }
}
