// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.kotlin

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.phantom.spring.core.SpringCodeBodies
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

/**
 * Kotlin bodies: block bodies of functions, init blocks and secondary constructors, and expression bodies, accessors
 * and initializers only where the type is written out. `fun x() = …` and `val x = …` take their type from the body, so
 * an edit there can change a `@Bean` method's return type; a `const val` initializer can change a bean name.
 */
class KotlinSpringCodeBodies : SpringCodeBodies {
  override fun bodyOf(element: PsiElement): PsiElement? {
    var body: PsiElement? = null
    var current: PsiElement? = element
    while (current != null && current !is PsiFile) {
      val parent = current.parent
      if (isBody(current, parent)) body = current
      current = parent
    }
    return body
  }

  private fun isBody(element: PsiElement, parent: PsiElement?): Boolean = when (parent) {
    is KtNamedFunction -> parent.bodyExpression == element && (parent.hasBlockBody() || parent.typeReference != null)
    is KtPropertyAccessor -> parent.bodyExpression == element && parent.property.typeReference != null
    is KtProperty -> parent.initializer == element && isTyped(parent)
    is KtPropertyDelegate -> parent.expression == element && (parent.parent as? KtProperty)?.let(::isTyped) == true
    is KtAnonymousInitializer -> parent.body == element
    is KtSecondaryConstructor -> parent.bodyExpression == element
    else -> false
  }

  private fun isTyped(property: KtProperty): Boolean = property.typeReference != null && !property.hasModifier(KtTokens.CONST_KEYWORD)

  override fun declaresClasses(element: PsiElement): Boolean = SpringCodeBodies.containsClass(element) { it is KtClassOrObject }
}
