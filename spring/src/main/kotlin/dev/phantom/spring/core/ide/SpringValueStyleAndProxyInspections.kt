// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.*
import org.jetbrains.annotations.NonNls
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Mistakes in how a `@Value` string is written rather than what it names: a `${` that never closes, `$ {key}` with a
 * space, an empty `${}`, a `#{...}` written inside `${...}` (placeholders resolve first, so the SpEL becomes part of
 * the key), and a bare `{key}` that probably lost its `$`.
 */
class SpringValueStyleInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        if (node.qualifiedName != SpringNames.VALUE) return true
        val value = node.findDeclaredAttributeValue("value") ?: return true
        val text = value.evaluateString() ?: return true
        val problem = problem(text) ?: return true
        holder.registerProblem(value.sourcePsi ?: return true, problem)
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }

  companion object {
    private val SPACED = Regex("""\$\s+\{""")
    private val BARE = Regex("""\{[A-Za-z][\w.\-]*}""")

    fun problem(text: String): String? {
      if (SPACED.containsMatchIn(text)) return PhantomSpringBundle.message("inspection.value.style.spaced")
      if (BARE.matches(text.trim())) return PhantomSpringBundle.message("inspection.value.style.bare", text.trim())
      var i = text.indexOf("\${")
      while (i >= 0) {
        val end = closing(text, i + 2) ?: return PhantomSpringBundle.message("inspection.value.style.unclosed")
        val body = text.substring(i + 2, end)
        if (body.isBlank()) return PhantomSpringBundle.message("inspection.value.style.empty")
        if (body.trimStart().startsWith("#{")) return PhantomSpringBundle.message("inspection.value.style.spel.inside")
        i = text.indexOf("\${", end + 1)
      }
      return null
    }

    /** The `}` that closes a `${` whose body starts at [from], counting nested braces. */
    private fun closing(text: String, from: Int): Int? {
      var depth = 1
      for (j in from until text.length) {
        when (text[j]) {
          '{' -> depth++
          '}' -> if (--depth == 0) return j
        }
      }
      return null
    }
  }
}

/**
 * Injecting a bean by its class when the bean is a JDK dynamic proxy: Spring then exposes only the bean's interfaces,
 * and the context fails with "bean is of type $Proxy". That happens only when interface proxying is chosen
 * explicitly — Spring Boot proxies classes (CGLIB) unless `spring.aop.proxy-target-class=false`, and without Boot one
 * of the `@Enable*` annotations must say `proxyTargetClass = false` with none saying `true`.
 */
class SpringJdkProxyInjectionInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val project = holder.project
    if (!SpringProjectGate.isSpringProject(project) || SpringBeans.isInTestSources(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    if (!JdkProxyMode.isJdkProxying(project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitParameter(node: UParameter): Boolean { check(node); return true }
      override fun visitField(node: UField): Boolean { check(node); return true }

      private fun check(node: UDeclaration) {
        val point = SpringBeans.injectionPointOf(node) ?: return
        val type = (point.type as? PsiClassType)?.resolve() ?: return
        if (type.isInterface || type.isAnnotationType || !SpringBeans.isProjectSourceClass(type) || !SpringBeans.isBeanClass(type)) return
        val interfaces = JdkProxyMode.proxiedInterfaces(type)
        if (interfaces.isEmpty() || !JdkProxyMode.isProxied(type)) return
        val anchor = (node.uastAnchor ?: node).sourcePsi ?: return
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.jdk.proxy.message", type.name ?: return, interfaces.joinToString { it.name ?: "?" }))
      }
    }, arrayOf(UParameter::class.java, UField::class.java), true)
  }
}

@NonNls
object JdkProxyMode {
  private val ENABLERS = listOf(
    "org.springframework.transaction.annotation.EnableTransactionManagement",
    "org.springframework.scheduling.annotation.EnableAsync",
    "org.springframework.cache.annotation.EnableCaching",
    "org.springframework.context.annotation.EnableAspectJAutoProxy",
    "org.springframework.retry.annotation.EnableRetry",
  )
  private const val BOOT_AUTOCONFIGURE = "org.springframework.boot.autoconfigure.SpringBootApplication"
  private val PROPERTY_FALSE = Regex("""proxy[-_]?target[-_]?class\s*[:=]\s*["']?false\b""", RegexOption.IGNORE_CASE)

  /** Proxied when a method or the class carries one of the proxy annotations (transactions, async, caching, retry). */
  fun isProxied(psiClass: PsiClass): Boolean =
    psiClass.methods.any { SpringProxies.methodAdvice(it) != null } ||
    psiClass.annotations.any { a -> a.qualifiedName in SpringProxies.ADVICE }

  /** The interfaces a JDK proxy of [psiClass] would implement; Spring ignores marker and callback interfaces. */
  fun proxiedInterfaces(psiClass: PsiClass): List<PsiClass> =
    psiClass.interfaces.filter { i ->
      val fqn = i.qualifiedName ?: return@filter false
      i.methods.isNotEmpty() && !fqn.startsWith("org.springframework.beans.factory.") && !fqn.startsWith("org.springframework.context.") &&
      fqn != "java.io.Serializable" && fqn != "java.lang.AutoCloseable" && !fqn.startsWith("groovy.")
    }

  private enum class CodeMode { CLASS, INTERFACE, BOOT }

  /** The `@Enable*` search runs on changes of declarations and libraries, the property is read once per set of config keys. */
  fun isJdkProxying(project: Project): Boolean = when (codeMode(project)) {
    CodeMode.CLASS -> false
    CodeMode.INTERFACE -> true
    CodeMode.BOOT -> SpringConfigKeys.get(project).derived("proxyTargetClassFalse", ::propertySaysFalse)
  }

  private fun codeMode(project: Project): CodeMode = CachedValuesManager.getManager(project).getCachedValue(project) {
    // annotations and their attributes are declarations: typing in a method body keeps the result
    CachedValueProvider.Result.create(computeCodeMode(project), *SpringStructureTracker.deps(project))
  }

  /** How often the `@Enable*` search ran, for tests that check it survives body edits. */
  @org.jetbrains.annotations.TestOnly
  val codeModeComputes: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()

  private fun computeCodeMode(project: Project): CodeMode {
    codeModeComputes.incrementAndGet()
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val scope = GlobalSearchScope.projectScope(project)
    var anyFalse = false
    var anyTrue = false
    for (fqn in ENABLERS) {
      val annotation = facade.findClass(fqn, all) ?: continue
      com.intellij.psi.search.searches.AnnotatedElementsSearch.searchPsiClasses(annotation, scope).findAll().forEach { owner ->
        val value = AnnotationUtil.findAnnotation(owner, fqn)?.findDeclaredAttributeValue("proxyTargetClass")?.text?.trim()
        when (value) {
          "true" -> anyTrue = true
          "false" -> anyFalse = true
        }
      }
    }
    if (anyTrue) return CodeMode.CLASS
    val boot = JavaLibraryUtil.hasLibraryClass(project, BOOT_AUTOCONFIGURE) ||
               (ApplicationManager.getApplication().isUnitTestMode && facade.findClass(BOOT_AUTOCONFIGURE, all) != null)
    // Spring Boot forces class proxies for every auto-proxy creator unless the property turns it off
    return if (boot) CodeMode.BOOT else if (anyFalse) CodeMode.INTERFACE else CodeMode.CLASS
  }

  private fun propertySaysFalse(keys: SpringConfigKeys.Keys): Boolean = keys.files.any { file ->
    val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@any false
    text.lineSequence().any { line -> !line.trimStart().startsWith("#") && PROPERTY_FALSE.containsMatchIn(line) }
  }
}
