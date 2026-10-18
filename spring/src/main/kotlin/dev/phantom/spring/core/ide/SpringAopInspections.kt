// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.icons.AllIcons
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.PhantomSpringBundle
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.getUParentForIdentifier
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor
import javax.swing.Icon

/** AspectJ annotation-style aspects, as Spring AOP and AspectJ weaving both read them. */
object AopModel {
  private const val PACKAGE = "org.aspectj.lang.annotation"
  const val ASPECT: String = "$PACKAGE.Aspect"
  const val POINTCUT: String = "$PACKAGE.Pointcut"
  const val AFTER_RETURNING: String = "$PACKAGE.AfterReturning"
  const val AFTER_THROWING: String = "$PACKAGE.AfterThrowing"
  val ADVICE: List<String> = listOf("$PACKAGE.Before", "$PACKAGE.After", "$PACKAGE.Around", AFTER_RETURNING, AFTER_THROWING)

  /** Pointcut designators of the AspectJ language (Spring AOP supports a subset, weaving all of them) and Spring's `bean`. */
  val DESIGNATORS: Set<String> = setOf(
    "execution", "within", "this", "target", "args", "@annotation", "@within", "@target", "@args", "bean",
    "call", "get", "set", "handler", "initialization", "preinitialization", "staticinitialization", "adviceexecution",
    "withincode", "cflow", "cflowbelow", "if", "@this", "@withincode",
  )

  fun isAopProject(project: Project): Boolean {
    if (project.isDefault || project.isDisposed) return false
    if (JavaLibraryUtil.hasLibraryClass(project, ASPECT)) return true
    return ApplicationManager.getApplication().isUnitTestMode &&
           JavaPsiFacade.getInstance(project).findClass(ASPECT, GlobalSearchScope.allScope(project)) != null
  }

  /** The advice or pointcut annotation of a method, if any. */
  fun annotationOf(method: UMethod): UAnnotation? = (ADVICE + POINTCUT).firstNotNullOfOrNull { method.findAnnotation(it) }

  /** The expression attribute: `pointcut` overrides `value` on the after-returning and after-throwing advice. */
  fun expressionOf(annotation: UAnnotation): org.jetbrains.uast.UExpression? =
    annotation.findDeclaredAttributeValue("pointcut")?.takeIf { (it.evaluateString() ?: "").isNotBlank() }
    ?: annotation.findDeclaredAttributeValue("value")
}

/**
 * A small reader of pointcut expressions: designators and named pointcut references joined by `&&`, `||`, `!` (or
 * `and`, `or`, `not`) and parentheses. Designator arguments are only checked for balance. Problems are offsets into
 * the expression.
 */
internal class PointcutReader(private val text: String) {
  class Problem(val key: String, val arg: String?, val start: Int, val end: Int)
  class Reference(val name: String, val start: Int, val end: Int)

  val problems = ArrayList<Problem>()
  val references = ArrayList<Reference>()
  private var i = 0

  fun read(): PointcutReader {
    if (text.isBlank()) { problems += Problem("aop.pointcut.empty", null, 0, text.length); return this }
    unbalancedAt()?.let { problems += Problem("aop.pointcut.unbalanced", null, it, (it + 1).coerceAtMost(text.length)); return this }
    expression(stopAtClose = false)
    return this
  }

  /** The first unmatched bracket, or null. Brackets inside quotes count too, but pointcuts rarely quote any. */
  private fun unbalancedAt(): Int? {
    val stack = ArrayList<Int>()
    for ((index, c) in text.withIndex()) {
      when (c) {
        '(' -> stack += index
        ')' -> if (stack.isEmpty()) return index else stack.removeAt(stack.lastIndex)
      }
    }
    return stack.firstOrNull()
  }

  private fun skipSpaces() { while (i < text.length && text[i].isWhitespace()) i++ }

  private fun expression(stopAtClose: Boolean) {
    var expectOperand = true
    while (true) {
      skipSpaces()
      if (i >= text.length) return
      val c = text[i]
      if (c == ')') { if (stopAtClose) return; i++; continue }
      if (text.startsWith("&&", i) || text.startsWith("||", i)) { i += 2; expectOperand = true; continue }
      if (c == '!') { i++; continue }
      if (c == '(') {
        i++
        expression(stopAtClose = true)
        if (i < text.length && text[i] == ')') i++
        expectOperand = false
        continue
      }
      val start = i
      if (c == '@') i++
      while (i < text.length && (text[i].isJavaIdentifierPart() || text[i] == '.')) i++
      val word = text.substring(start, i)
      if (word.isEmpty() || word == "@") {
        i = (start + 1).coerceAtLeast(i)
        problems += Problem("aop.pointcut.unexpected", c.toString(), start, start + 1)
        return
      }
      if (word in setOf("and", "or")) { expectOperand = true; continue }
      if (word == "not") continue
      skipSpaces()
      if (i >= text.length || text[i] != '(') {
        problems += Problem("aop.pointcut.unexpected", word, start, start + word.length)
        return
      }
      if (!expectOperand) {
        problems += Problem("aop.pointcut.unexpected", word, start, start + word.length)
        return
      }
      skipArguments()
      if (word !in AopModel.DESIGNATORS) references += Reference(word, start, start + word.length)
      expectOperand = false
    }
  }

  /** From an opening parenthesis to just after its match. */
  private fun skipArguments() {
    var depth = 0
    while (i < text.length) {
      when (text[i]) {
        '(' -> depth++
        ')' -> { depth--; if (depth == 0) { i++; return } }
      }
      i++
    }
  }
}

/**
 * Pointcut expressions in `@Before`, `@Around`, `@Pointcut`... that Spring cannot parse (unbalanced parentheses,
 * stray words) or that name a pointcut method that does not exist, and `returning`/`throwing` names that are not a
 * parameter of the advice method. Only expressions that are a single string literal are checked.
 */
class AopPointcutInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!AopModel.isAopProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        val annotation = AopModel.annotationOf(node) ?: return true
        checkBinding(node, annotation, AopModel.AFTER_RETURNING, "returning")
        checkBinding(node, annotation, AopModel.AFTER_THROWING, "throwing")
        val host = AopModel.expressionOf(annotation) as? UInjectionHost ?: return true
        val psi = host.psiLanguageInjectionHost
        val valueRange = ElementManipulators.getValueTextRange(psi)
        val text = valueRange.substring(psi.text)
        // an empty @Pointcut("") is allowed on an if() pointcut's method in AspectJ; Spring rejects it elsewhere
        if (text.isBlank() && annotation.qualifiedName == AopModel.POINTCUT) return true
        val reader = PointcutReader(text).read()
        fun range(start: Int, end: Int) = TextRange(valueRange.startOffset + start, valueRange.startOffset + maxOf(end, start + 1).coerceAtMost(text.length))
        for (problem in reader.problems) {
          val message = if (problem.arg != null) PhantomSpringBundle.message(problem.key, problem.arg) else PhantomSpringBundle.message(problem.key)
          holder.registerProblem(psi, if (text.isEmpty()) valueRange else range(problem.start, problem.end), message)
        }
        if (reader.problems.isNotEmpty()) return true
        val owner = node.javaPsi.containingClass ?: return true
        for (reference in reader.references) {
          if (resolvePointcut(owner, reference.name) == null) {
            holder.registerProblem(psi, range(reference.start, reference.end), PhantomSpringBundle.message("aop.pointcut.unresolved", reference.name))
          }
        }
        return true
      }

      private fun checkBinding(node: UMethod, annotation: UAnnotation, fqn: String, attribute: String) {
        if (annotation.qualifiedName != fqn) return
        val value = annotation.findDeclaredAttributeValue(attribute) ?: return
        val name = value.evaluateString()?.takeIf { it.isNotEmpty() } ?: return
        if (node.uastParameters.any { it.name == name }) return
        val anchor = value.sourcePsi ?: return
        holder.registerProblem(anchor, PhantomSpringBundle.message("aop.pointcut.binding", name))
      }
    }, arrayOf(UMethod::class.java), true)
  }

  companion object {
    /** `name` in the class or its supertypes, or `pkg.Class.name`; only methods annotated with `@Pointcut` count. */
    fun resolvePointcut(owner: PsiClass, reference: String): PsiElement? {
      val methodName = reference.substringAfterLast('.')
      val classes = if ('.' in reference) {
        val qualifier = reference.substringBeforeLast('.')
        val facade = JavaPsiFacade.getInstance(owner.project)
        val scope = GlobalSearchScope.allScope(owner.project)
        val samePackage = owner.qualifiedName?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() }?.let { "$it.$qualifier" }
        listOfNotNull(facade.findClass(qualifier, scope) ?: owner.findInnerClassByName(qualifier, true)
                      ?: samePackage?.let { facade.findClass(it, scope) })
      }
      else listOf(owner)
      return classes.firstNotNullOfOrNull { psiClass ->
        psiClass.findMethodsByName(methodName, true).firstOrNull { it.hasAnnotation(AopModel.POINTCUT) }
      }
    }
  }
}

/** A gutter icon on advice methods of aspects with the advice kind and its pointcut. */
class AopAdviceLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBundle.message("aop.line.marker.name")

  override fun getIcon(): Icon = AllIcons.Nodes.Aspect

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null) return null
    if ((element.parent as? PsiNameIdentifierOwner)?.nameIdentifier != element) return null
    if (!AopModel.isAopProject(element.project)) return null
    val method = getUParentForIdentifier(element) as? UMethod ?: return null
    val annotation = AopModel.ADVICE.firstNotNullOfOrNull { method.findAnnotation(it) } ?: return null
    val kind = annotation.qualifiedName!!.substringAfterLast('.')
    val expression = AopModel.expressionOf(annotation)?.evaluateString().orEmpty()
    return LineMarkerInfo(element, element.textRange, AllIcons.Nodes.Aspect,
                          { PhantomSpringBundle.message("aop.gutter.tooltip", kind, expression) }, null, GutterIconRenderer.Alignment.LEFT,
                          { PhantomSpringBundle.message("aop.line.marker.name") })
  }
}
