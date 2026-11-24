// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.SpringBeans
import dev.phantom.spring.core.SpringProjectGate
import dev.phantom.spring.core.SpringProxies
import dev.phantom.spring.core.SpringStructureTracker
import org.jetbrains.annotations.NonNls
import org.jetbrains.uast.*
import javax.swing.Icon

/** The text of the attributes an annotation declares, `propagation = REQUIRES_NEW, readOnly = true`, as written. */
private fun declaredAttributes(annotation: UAnnotation): String =
  annotation.attributeValues.mapNotNull { attribute ->
    val text = attribute.expression.sourcePsi?.text ?: return@mapNotNull null
    if (attribute.name == null || attribute.name == "value") text else "${attribute.name} = $text"
  }.joinToString(", ")

/** Whether [element] is the name of a declaration; plain PSI, cheap enough to run on every leaf before anything else. */
private fun isNameIdentifier(element: PsiElement): Boolean =
  element.firstChild == null && (element.parent as? PsiNameIdentifierOwner)?.nameIdentifier == element

/**
 * `@Transactional` and `@Async` where they are declared, on a method or a class, with their settings as the tooltip:
 * the proxy behaviour of the method is visible without reading the annotation.
 */
class SpringTransactionalLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBundle.message("proxy.line.marker.name")

  override fun getIcon(): Icon = AllIcons.Actions.Commit

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // the project gate before the UAST conversion: outside Spring projects no leaf pays for it
    if (!isNameIdentifier(element) || !SpringProjectGate.isSpringProject(element.project)) return null
    val owner = getUParentForIdentifier(element) as? UAnnotated ?: return null
    if (owner !is UMethod && owner !is UClass) return null
    val transactional = SpringProxies.TRANSACTION_ANNOTATIONS.firstNotNullOfOrNull { owner.findAnnotation(it) }
    val async = owner.findAnnotation(SpringProxies.ASYNC)
    if (transactional == null && async == null) return null
    val lines = listOfNotNull(
      transactional?.let { PhantomSpringBundle.message("proxy.gutter.transactional", declaredAttributes(it).ifEmpty { PhantomSpringBundle.message("proxy.gutter.defaults") }) },
      async?.let { PhantomSpringBundle.message("proxy.gutter.async", declaredAttributes(it).ifEmpty { PhantomSpringBundle.message("proxy.gutter.default.executor") }) },
    )
    val tooltip = lines.joinToString("<br>", "<html>", "</html>")
    val icon = if (transactional != null) AllIcons.Actions.Commit else AllIcons.Actions.Lightning
    return LineMarkerInfo(element, element.textRange, icon, { tooltip }, null, GutterIconRenderer.Alignment.LEFT, { lines.joinToString(", ") })
  }
}

/** On `@ComponentScan` and `@SpringBootApplication` classes: the packages they scan, to open in the Project view. */
class SpringComponentScanLineMarkerProvider : LineMarkerProviderDescriptor() {
  @NonNls private val componentScan = "org.springframework.context.annotation.ComponentScan"
  @NonNls private val bootApplication = "org.springframework.boot.autoconfigure.SpringBootApplication"

  override fun getName(): String = PhantomSpringBundle.message("scan.line.marker.name")

  override fun getIcon(): Icon = AllIcons.Nodes.Package

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (!isNameIdentifier(element) || !SpringProjectGate.isSpringProject(element.project)) return null
    val uClass = getUParentForIdentifier(element) as? UClass ?: return null
    val scan = uClass.findAnnotation(componentScan)
    val boot = uClass.findAnnotation(bootApplication)
    if (scan == null && boot == null) return null
    val psiClass = uClass.javaPsi
    val packages = LinkedHashSet<String>()
    for ((annotation, names, classes) in listOfNotNull(scan?.let { Triple(it, listOf("value", "basePackages"), "basePackageClasses") },
                                                       boot?.let { Triple(it, listOf("scanBasePackages"), "scanBasePackageClasses") })) {
      names.flatMap { attributeItems(annotation, it) }.mapNotNullTo(packages) { it.evaluateString()?.trim()?.takeIf(String::isNotEmpty) }
      attributeItems(annotation, classes).mapNotNullTo(packages) { item ->
        ((item as? UClassLiteralExpression)?.type as? PsiClassType)?.resolve()?.qualifiedName?.substringBeforeLast('.', "")
      }
    }
    // no package named: the package of the annotated class
    if (packages.isEmpty()) packages += psiClass.qualifiedName?.substringBeforeLast('.', "") ?: return null
    // placeholders (${...}) and patterns are resolved by Spring at startup; show what is written
    val label = packages.joinToString(", ") { it.ifEmpty { PhantomSpringBundle.message("scan.gutter.default.package") } }
    val project = element.project
    return NavigationGutterIconBuilder.create(AllIcons.Nodes.Package)
      .setTargets(NotNullLazyValue.lazy {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        packages.filter { !it.contains('$') && !it.contains('*') }
          .flatMap { facade.findPackage(it)?.getDirectories(scope)?.toList().orEmpty() }
      })
      .setTooltipText(PhantomSpringBundle.message("scan.gutter.tooltip", label))
      .setPopupTitle(PhantomSpringBundle.message("scan.gutter.tooltip", label))
      .setEmptyPopupText(PhantomSpringBundle.message("scan.gutter.empty", label))
      .createLineMarkerInfo(element)
  }
}

/**
 * Which advice of the project's aspects applies to a method. Only pointcuts that can be decided from the code are
 * matched: `@annotation`, `@within`, `within`, `execution` with `*`/`void` return types and `(..)`/`()` parameters,
 * `this`/`target` with a type, named pointcuts and `&&`, `||`, `!`. Anything else (`bean`, `args` with types, `+`
 * subtypes, `throws`) makes the result unknown, and unknown never shows a match.
 */
object AopAdviceMatching {
  enum class Match { YES, NO, UNKNOWN }

  class Advice(val method: PsiMethod, val kind: String, val expression: String) {
    /**
     * The regexes of this advice's `execution`/`within` patterns (named pointcuts included), by kind and pattern text,
     * compiled on first use: the gutter matches every advice against every bean method, and compiling them per method
     * was most of its cost. Dropped with the advice list when declarations change.
     */
    internal val patterns = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Regex>>()
  }

  /** Advice methods in project sources, cached until declarations change ([SpringStructureTracker]). */
  fun advices(project: Project): List<Advice> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val scope = GlobalSearchScope.projectScope(project)
    val result = ArrayList<Advice>()
    for (fqn in AopModel.ADVICE) {
      val annotation = facade.findClass(fqn, all) ?: continue
      AnnotatedElementsSearch.searchPsiMethods(annotation, scope).forEach { method ->
        val uMethod = method.toUElement() as? UMethod ?: return@forEach
        val uAnnotation = uMethod.findAnnotation(fqn) ?: return@forEach
        val expression = AopModel.expressionOf(uAnnotation)?.evaluateString() ?: return@forEach
        result += Advice(method, fqn.substringAfterLast('.'), expression)
      }
    }
    // advice annotations and the constants they use are declarations: MODIFICATION_COUNT dropped the list (and its
    // compiled patterns) on every keystroke in any language
    CachedValueProvider.Result.create(result, *SpringStructureTracker.deps(project))
  }

  fun matches(advice: Advice, target: PsiMethod): Match = Evaluator(advice, advice.method, target, 0).evaluate(advice.expression)

  private val EXECUTION_PATTERN = Regex("""^((?:(?:public|protected|private|static|final)\s+)*)(\S+)\s+([^\s(]+)\s*\(([^)]*)\)$""")
  private val WHITESPACE = Regex("\\s+")

  private class Evaluator(private val advice: Advice, private val owner: PsiMethod, private val target: PsiMethod, private val depth: Int) {
    private var text = ""
    private var i = 0

    fun evaluate(expression: String): Match {
      text = expression
      i = 0
      return try {
        val result = or()
        skip()
        if (i < text.length) Match.UNKNOWN else result
      }
      catch (_: IllegalStateException) {
        Match.UNKNOWN
      }
    }

    private fun skip() { while (i < text.length && text[i].isWhitespace()) i++ }

    private fun keyword(word: String): Boolean {
      skip()
      if (!text.startsWith(word, i)) return false
      val end = i + word.length
      if (word.first().isLetter() && end < text.length && text[end].isJavaIdentifierPart()) return false
      i = end
      return true
    }

    private fun or(): Match {
      var result = and()
      while (keyword("||") || keyword("or")) result = either(result, and())
      return result
    }

    private fun and(): Match {
      var result = not()
      while (keyword("&&") || keyword("and")) result = both(result, not())
      return result
    }

    private fun not(): Match {
      if (keyword("!") || keyword("not")) return when (not()) { Match.YES -> Match.NO; Match.NO -> Match.YES; Match.UNKNOWN -> Match.UNKNOWN }
      return primary()
    }

    private fun primary(): Match {
      skip()
      check(i < text.length)
      if (text[i] == '(') {
        i++
        val inner = or()
        skip()
        check(i < text.length && text[i] == ')')
        i++
        return inner
      }
      val start = i
      if (text[i] == '@') i++
      while (i < text.length && (text[i].isJavaIdentifierPart() || text[i] == '.')) i++
      val word = text.substring(start, i)
      check(word.isNotEmpty() && word != "@")
      skip()
      check(i < text.length && text[i] == '(')
      val argsStart = i + 1
      var level = 0
      while (i < text.length) {
        when (text[i]) {
          '(' -> level++
          ')' -> { level--; if (level == 0) break }
        }
        i++
      }
      check(i < text.length)
      val args = text.substring(argsStart, i).trim()
      i++
      return designator(word, args)
    }

    private fun designator(word: String, args: String): Match = when (word) {
      "@annotation" -> typeOf(args)?.let { if (target.hasAnnotation(it)) Match.YES else Match.NO } ?: Match.UNKNOWN
      "@within" -> typeOf(args)?.let { fqn -> if (target.containingClass?.hasAnnotation(fqn) == true) Match.YES else Match.NO } ?: Match.UNKNOWN
      "within" -> cachedTypePattern(advice, args)?.let { pattern -> if (target.containingClass?.qualifiedName?.matches(pattern) == true) Match.YES else Match.NO } ?: Match.UNKNOWN
      "this", "target" -> typeOf(args)?.let { fqn -> if (InheritanceUtil.isInheritor(target.containingClass, false, fqn) || target.containingClass?.qualifiedName == fqn) Match.YES else Match.NO } ?: Match.UNKNOWN
      "args" -> if (args == "..") Match.YES else Match.UNKNOWN
      "execution" -> execution(args)
      in AopModel.DESIGNATORS -> Match.UNKNOWN
      else -> namedPointcut(word)
    }

    /** A type name, or the name of an advice parameter whose type is meant (`@annotation(lock)`). */
    private fun typeOf(arg: String): String? {
      if (arg.isEmpty() || arg.any { it == '*' || it == '+' || it.isWhitespace() || it == '(' }) return null
      if ('.' in arg) return arg
      val parameter = owner.parameterList.parameters.firstOrNull { it.name == arg } ?: return null
      return (parameter.type as? PsiClassType)?.resolve()?.qualifiedName
    }

    private fun namedPointcut(reference: String): Match {
      if (depth >= 4) return Match.UNKNOWN
      val ownerClass = owner.containingClass ?: return Match.UNKNOWN
      val pointcut = AopPointcutInspection.resolvePointcut(ownerClass, reference) as? PsiMethod ?: return Match.UNKNOWN
      val annotation = (pointcut.toUElement() as? UMethod)?.findAnnotation(AopModel.POINTCUT) ?: return Match.UNKNOWN
      val expression = AopModel.expressionOf(annotation)?.evaluateString()?.takeIf { it.isNotBlank() } ?: return Match.UNKNOWN
      return Evaluator(advice, pointcut, target, depth + 1).evaluate(expression)
    }

    private fun execution(args: String): Match {
      val m = EXECUTION_PATTERN.matchEntire(args) ?: return Match.UNKNOWN
      val (modifiers, returnType, declaration, parameters) = m.destructured
      val results = ArrayList<Match>()
      if ("public" in modifiers.split(WHITESPACE)) results += if (target.hasModifierProperty(PsiModifier.PUBLIC)) Match.YES else Match.NO
      results += when (returnType) {
        "*" -> Match.YES
        "void" -> if (target.returnType == PsiTypes.voidType()) Match.YES else Match.NO
        else -> Match.UNKNOWN
      }
      // `com.app..*.find*`: the method pattern after the last single dot, the declaring type before it
      val split = declaration.lastIndexOf('.')
      val methodPattern = if (split < 0) declaration else declaration.substring(split + 1)
      val typeText = when {
        split < 0 -> null
        split > 0 && declaration[split - 1] == '.' -> declaration.substring(0, split + 1) + "*"
        else -> declaration.substring(0, split)
      }
      results += if (cachedGlob(advice, methodPattern).matches(target.name)) Match.YES else Match.NO
      if (typeText != null) {
        val pattern = cachedTypePattern(advice, typeText)
        results += if (pattern == null) Match.UNKNOWN
                   else if (target.containingClass?.qualifiedName?.matches(pattern) == true) Match.YES else Match.NO
      }
      results += when (parameters.trim()) {
        ".." -> Match.YES
        "" -> if (target.parameterList.isEmpty) Match.YES else Match.NO
        else -> Match.UNKNOWN
      }
      return results.fold(Match.YES, ::both)
    }
  }

  private fun both(a: Match, b: Match): Match = when {
    a == Match.NO || b == Match.NO -> Match.NO
    a == Match.YES && b == Match.YES -> Match.YES
    else -> Match.UNKNOWN
  }

  private fun either(a: Match, b: Match): Match = when {
    a == Match.YES || b == Match.YES -> Match.YES
    a == Match.NO && b == Match.NO -> Match.NO
    else -> Match.UNKNOWN
  }

  private fun cachedGlob(advice: Advice, pattern: String): Regex =
    advice.patterns.getOrPut("glob:$pattern") { java.util.Optional.of(glob(pattern)) }.get()

  private fun cachedTypePattern(advice: Advice, pattern: String): Regex? =
    advice.patterns.getOrPut("type:$pattern") { java.util.Optional.ofNullable(typePattern(pattern)) }.orElse(null)

  private fun glob(pattern: String): Regex = Regex(pattern.split('*').joinToString("[\\w$]*") { Regex.escape(it) })

  /** An AspectJ type pattern as a regex over qualified names: `*` is one name part, `..` any number of packages. */
  internal fun typePattern(pattern: String): Regex? {
    val text = pattern.trim()
    if (text.isEmpty() || text.any { it == '+' || it == '(' || it == '!' || it == '&' || it == '|' || it.isWhitespace() }) return null
    if (text == "*") return Regex("[\\w$.]+")
    // a bare simple name would need the aspect's imports to resolve; leave it undecided
    if ('.' !in text && '*' !in text) return null
    val regex = text.split("..").joinToString("(?:\\.[\\w$]+)*\\.") { part -> part.split('*').joinToString("[\\w$]*") { Regex.escape(it) } }
    return Regex(regex)
  }
}

/** On a bean method that advice of the project's aspects applies to: the advice methods, by kind. */
class AopAdvisedMethodLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBundle.message("aop.advised.line.marker.name")

  override fun getIcon(): Icon = AllIcons.Nodes.Aspect

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (!isNameIdentifier(element) || !AopModel.isAopProject(element.project)) return null
    val uMethod = getUParentForIdentifier(element) as? UMethod ?: return null
    if (uMethod.isConstructor) return null
    val advices = AopAdviceMatching.advices(element.project).ifEmpty { return null }
    val method = uMethod.javaPsi
    // Spring AOP proxies public and protected methods of beans; aspects themselves are not advised
    if (method.hasModifierProperty(PsiModifier.PRIVATE) || method.hasModifierProperty(PsiModifier.STATIC)) return null
    if (AopModel.ADVICE.any { method.hasAnnotation(it) } || method.hasAnnotation(AopModel.POINTCUT)) return null
    val psiClass = method.containingClass ?: return null
    if (psiClass.hasAnnotation(AopModel.ASPECT) || !SpringBeans.isBeanClass(psiClass)) return null
    val matching = advices.filter { it.method != method && AopAdviceMatching.matches(it, method) == AopAdviceMatching.Match.YES }
      .sortedWith(compareBy({ AopModel.ADVICE.indexOfFirst { fqn -> fqn.endsWith(".${it.kind}") } }, { it.method.containingClass?.name }, { it.method.name }))
    if (matching.isEmpty()) return null
    val label = matching.joinToString(", ") { "@${it.kind} ${it.method.containingClass?.name}.${it.method.name}" }
    return NavigationGutterIconBuilder.create(AllIcons.Nodes.Aspect)
      .setTargets(NotNullLazyValue.lazy { matching.map { it.method }.filter { it.isValid }.map { it.navigationElement } })
      .setTooltipText(PhantomSpringBundle.message("aop.advised.gutter.tooltip", label))
      .setPopupTitle(PhantomSpringBundle.message("aop.advised.line.marker.name"))
      .createLineMarkerInfo(element)
  }
}
