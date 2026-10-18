// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInspection.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.*
import org.jetbrains.annotations.NonNls
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/** Where in an annotation string a part of its value sits, for highlighting just that part. */
private fun rangeOf(psi: PsiElement, part: String): TextRange? {
  val at = psi.text.indexOf("{$part").takeIf { it >= 0 }?.plus(1) ?: psi.text.indexOf(part).takeIf { it >= 0 } ?: return null
  return TextRange(at, at + part.length)
}

@NonNls
private object CacheNames {
  const val CACHEABLE = "org.springframework.cache.annotation.Cacheable"
  const val CACHE_PUT = "org.springframework.cache.annotation.CachePut"
  const val CACHE_EVICT = "org.springframework.cache.annotation.CacheEvict"
  const val CACHING = "org.springframework.cache.annotation.Caching"
  const val CACHE_CONFIG = "org.springframework.cache.annotation.CacheConfig"
  const val CACHE_RESOLVER = "org.springframework.cache.interceptor.CacheResolver"
  const val CACHING_CONFIGURER = "org.springframework.cache.annotation.CachingConfigurer"
  val OPERATIONS = setOf(CACHEABLE, CACHE_PUT, CACHE_EVICT)
  val ALL = OPERATIONS + CACHING + CACHE_CONFIG
}

/**
 * `@Value("${key}")` whose key no configuration file of the project defines and that has no default: the context fails
 * to start unless something outside the files (an environment variable, a command line argument) supplies it.
 */
class SpringValuePlaceholderInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project) || SpringBeans.isInTestSources(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    val keys by lazy { SpringConfigKeys.get(holder.project) }
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        if (node.qualifiedName != SpringNames.VALUE) return true
        val value = node.findDeclaredAttributeValue("value") ?: return true
        val text = value.evaluateString() ?: return true
        val psi = value.sourcePsi ?: return true
        val placeholders = placeholders(text).filter { !it.hasDefault && isCheckable(it.key) }
        if (placeholders.isEmpty()) return true
        // No configuration files at all: the values come from elsewhere, nothing to compare with
        if (keys.files.isEmpty() || keys.external) return true
        for (placeholder in placeholders) {
          if (keys.contains(placeholder.key)) continue
          holder.registerProblem(psi, rangeOf(psi, placeholder.key), PhantomSpringBundle.message("inspection.value.placeholder.message", placeholder.key))
        }
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }

  class Placeholder(val key: String, val hasDefault: Boolean)

  companion object {
    /** Keys Spring or the JVM supplies without any configuration file. */
    @NonNls private val BUILT_IN_PREFIXES = listOf("java.", "os.", "user.", "file.", "line.", "path.", "sun.", "jdk.", "random.", "local.", "awt.")

    fun isCheckable(key: String): Boolean =
      key.isNotBlank() && !key.contains("\${") && !key.contains("#{") &&
      // environment variable style (JWT_SECRET, HOME) is read from the environment
      !key.matches(Regex("[A-Z0-9_]+")) && BUILT_IN_PREFIXES.none { key.startsWith(it) }

    /** The `${…}` placeholders of a value, outermost only; `:` at the top level of one starts its default. */
    fun placeholders(text: String): List<Placeholder> {
      val result = ArrayList<Placeholder>()
      var i = text.indexOf("\${")
      while (i >= 0) {
        var depth = 1
        var j = i + 2
        var colon = -1
        while (j < text.length && depth > 0) {
          when {
            text.startsWith("\${", j) -> { depth++; j++ }
            text[j] == '{' -> depth++
            text[j] == '}' -> depth--
            text[j] == ':' && depth == 1 && colon < 0 -> colon = j
          }
          j++
        }
        if (depth != 0) break
        val body = text.substring(i + 2, j - 1)
        result += if (colon >= 0) Placeholder(text.substring(i + 2, colon), true) else Placeholder(body, false)
        i = text.indexOf("\${", j)
      }
      return result
    }
  }
}

/** Basic syntax of SpEL in Spring annotations: brackets and quotes that do not close, empty `#{}`. */
class SpringElSyntaxInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        val fqn = node.qualifiedName ?: return true
        if (fqn == SpringNames.VALUE) {
          val value = node.findDeclaredAttributeValue("value") ?: return true
          val text = value.evaluateString() ?: return true
          templateError(text)?.let { report(value, it) }
          return true
        }
        val attributes = WHOLE_EXPRESSION_ATTRIBUTES[fqn] ?: return true
        for (attribute in attributes) {
          val value = node.findDeclaredAttributeValue(attribute) ?: continue
          val text = value.evaluateString() ?: continue
          if (text.isEmpty()) continue
          expressionError(text)?.let { report(value, it) }
        }
        return true
      }

      private fun report(value: UExpression, message: String) {
        holder.registerProblem(value.sourcePsi ?: return, PhantomSpringBundle.message("inspection.spel.message", message))
      }
    }, arrayOf(UAnnotation::class.java), true)
  }

  companion object {
    @NonNls private val WHOLE_EXPRESSION_ATTRIBUTES: Map<String, List<String>> = buildMap {
      for (cache in CacheNames.OPERATIONS) put(cache, listOf("key", "condition", "unless"))
      put("org.springframework.context.event.EventListener", listOf("condition"))
      put("org.springframework.transaction.event.TransactionalEventListener", listOf("condition"))
      for (security in listOf("PreAuthorize", "PostAuthorize", "PreFilter", "PostFilter")) put("org.springframework.security.access.prepost.$security", listOf("value"))
    }

    /** The first problem in the `#{…}` parts of a `@Value` string, or null. */
    fun templateError(text: String): String? {
      var i = text.indexOf("#{")
      while (i >= 0) {
        val end = closingBrace(text, i + 2) ?: return PhantomSpringBundle.message("inspection.spel.unclosed", "#{")
        val body = text.substring(i + 2, end)
        if (body.isBlank()) return PhantomSpringBundle.message("inspection.spel.empty")
        expressionError(body)?.let { return it }
        i = text.indexOf("#{", end + 1)
      }
      return null
    }

    /** The index of the `}` closing a `#{` whose body starts at [from], skipping quoted text and nested braces. */
    private fun closingBrace(text: String, from: Int): Int? {
      var depth = 1
      var quote: Char? = null
      var i = from
      while (i < text.length) {
        val c = text[i]
        if (quote != null) {
          if (c == quote) { if (i + 1 < text.length && text[i + 1] == quote) i++ else quote = null }
        }
        else when (c) {
          '\'', '"' -> quote = c
          '{' -> depth++
          '}' -> if (--depth == 0) return i
        }
        i++
      }
      return null
    }

    /** The first bracket or quote problem in a SpEL expression, or null. Quotes are escaped by doubling them. */
    fun expressionError(text: String): String? {
      if (text.isBlank()) return PhantomSpringBundle.message("inspection.spel.empty")
      val open = ArrayDeque<Char>()
      var quote: Char? = null
      var i = 0
      while (i < text.length) {
        val c = text[i]
        if (quote != null) {
          if (c == quote) { if (i + 1 < text.length && text[i + 1] == quote) i++ else quote = null }
        }
        else when (c) {
          '\'', '"' -> quote = c
          '(', '[', '{' -> open.addLast(c)
          ')', ']', '}' -> {
            val expected = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
            if (open.lastOrNull() != expected) return PhantomSpringBundle.message("inspection.spel.unexpected", c.toString())
            open.removeLast()
          }
        }
        i++
      }
      if (quote != null) return PhantomSpringBundle.message("inspection.spel.unclosed.string")
      open.lastOrNull()?.let { return PhantomSpringBundle.message("inspection.spel.unclosed", it.toString()) }
      return null
    }
  }
}

/** `@Cacheable` and friends that name no cache, on a class without `@CacheConfig(cacheNames)`: the first call fails. */
class SpringCacheNamesInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        val fqn = node.qualifiedName ?: return true
        if (fqn !in CacheNames.OPERATIONS) return true
        val annotation = node.javaPsi ?: return true
        if (namesCaches(annotation)) return true
        val owner = node.uastParent as? UDeclaration ?: return true
        val psiClass = (owner as? UClass)?.javaPsi ?: (owner as? UMethod)?.javaPsi?.containingClass ?: return true
        val config = psiClass.getAnnotation(CacheNames.CACHE_CONFIG)
        if (config != null && namesCaches(config)) return true
        if (hasCustomResolver(holder.project)) return true
        val anchor = node.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.cache.names.message", "@" + fqn.substringAfterLast('.')))
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }

  private fun namesCaches(annotation: PsiAnnotation): Boolean =
    listOf("value", "cacheNames").any { AnnotationUtil.arrayAttributeValues(annotation.findDeclaredAttributeValue(it)).isNotEmpty() } ||
    !AnnotationUtil.getStringAttributeValue(annotation, "cacheResolver").isNullOrEmpty()

  /** A project CacheResolver can work out the caches itself. */
  private fun hasCustomResolver(project: Project): Boolean = CachedValuesManager.getManager(project).getCachedValue(project) {
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val scope = GlobalSearchScope.projectScope(project)
    val resolver = facade.findClass(CacheNames.CACHE_RESOLVER, all)
    val configurer = facade.findClass(CacheNames.CACHING_CONFIGURER, all)
    val custom = (resolver != null && ClassInheritorsSearch.search(resolver, scope, true).findFirst() != null) ||
                 (configurer != null && ClassInheritorsSearch.search(configurer, scope, true).anyMatch { it.findMethodsByName("cacheResolver", false).isNotEmpty() })
    CachedValueProvider.Result.create(custom, PsiModificationTracker.MODIFICATION_COUNT)
  }
}

/**
 * Caching annotations on an interface or an interface method. Spring finds them only through interface-based (JDK)
 * proxies; with class-based proxies, the Spring Boot default, the cache is skipped. Spring Data repositories are
 * interface proxies and are left alone.
 */
class SpringCacheOnInterfaceInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        val fqn = node.qualifiedName ?: return true
        if (fqn !in CacheNames.ALL) return true
        val psiClass = when (val owner = node.uastParent) {
          is UClass -> owner.javaPsi
          is UMethod -> owner.javaPsi.containingClass
          else -> null
        } ?: return true
        if (!psiClass.isInterface || psiClass.isAnnotationType) return true
        if (InheritanceUtil.isInheritor(psiClass, SpringNames.DATA_REPOSITORY)) return true
        holder.registerProblem(node.sourcePsi ?: return true, PhantomSpringBundle.message("inspection.cache.interface.message", "@" + fqn.substringAfterLast('.')))
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }
}

/**
 * A bean name in `@Qualifier` or `@DependsOn` that is a near miss of a project bean name (other case, one letter off).
 * Names that match no project bean at all are not reported: library and auto-configured beans are not known here.
 */
object SpringBeanNameTypos {
  fun knownNames(project: Project): Set<String> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val names = HashSet<String>()
    for ((name, beans) in SpringBeanNames.byName(project)) {
      names += name
      beans.forEach { bean -> SpringBeans.qualifier(bean)?.let(names::add) }
    }
    CachedValueProvider.Result.create<Set<String>>(names, PsiModificationTracker.MODIFICATION_COUNT)
  }

  /** The project bean [name] probably means, or null when it is a known name or not close to one. */
  fun nearMiss(project: Project, name: String): String? {
    val names = knownNames(project)
    if (name.isBlank() || name in names) return null
    return names.firstOrNull { it.equals(name, ignoreCase = true) } ?: if (name.length < 6) null else names.firstOrNull { distanceIsOne(it, name) }
  }

  private fun distanceIsOne(a: String, b: String): Boolean {
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    while (i < a.length && i < b.length && a[i] == b[i]) i++
    return when {
      a.length == b.length -> a.substring(i + 1) == b.substring(i + 1) || (i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2))
      a.length > b.length -> a.substring(i + 1) == b.substring(i)
      else -> a.substring(i) == b.substring(i + 1)
    }
  }
}

class SpringQualifierUnresolvedInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        if (node.qualifiedName != SpringNames.QUALIFIER) return true
        val variable = (node.uastParent as? UVariable)?.javaPsi as? PsiVariable ?: return true
        // a project type is checked by the autowiring inspection, with the candidates
        val type = SpringBeans.beanTypeOf(variable.type)
        if (type != null && SpringBeans.isProjectSourceClass(type)) return true
        val value = node.findDeclaredAttributeValue("value") ?: return true
        val name = value.evaluateString() ?: return true
        val meant = SpringBeanNameTypos.nearMiss(holder.project, name) ?: return true
        holder.registerProblem(value.sourcePsi ?: return true, PhantomSpringBundle.message("inspection.bean.name.typo.message", name, meant))
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }
}

class SpringDependsOnUnresolvedInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        if (node.qualifiedName != SpringBeanNameReferenceContributor.DEPENDS_ON) return true
        val value = node.findDeclaredAttributeValue("value") ?: return true
        val items = (value as? UCallExpression)?.valueArguments ?: listOf(value)
        for (item in items) {
          val name = item.evaluateString() ?: continue
          val meant = SpringBeanNameTypos.nearMiss(holder.project, name) ?: continue
          holder.registerProblem(item.sourcePsi ?: continue, PhantomSpringBundle.message("inspection.bean.name.typo.message", name, meant))
        }
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }
}
