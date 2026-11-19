// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import dev.phantom.spring.core.SpringBeans
import dev.phantom.spring.core.SpringNames
import dev.phantom.spring.core.SpringProjectGate

/**
 * Spring creates beans and calls their constructors, `@Bean` factories, listeners and handlers by reflection.
 * Without this, the community IDE reports them as unused (Java and Kotlin, through light elements).
 */
class SpringImplicitUsageProvider : ImplicitUsageProvider {
  override fun isImplicitUsage(element: PsiElement): Boolean {
    if (!SpringProjectGate.isSpringProject(element.project)) return false
    return when (element) {
      is PsiClass -> SpringBeans.isBeanClass(element) || SpringBeans.isStereotypeAnnotation(element)
      is PsiMethod -> isFrameworkCalled(element)
      is PsiField -> isInjected(element)
      else -> false
    }
  }

  override fun isImplicitRead(element: PsiElement): Boolean = false

  override fun isImplicitWrite(element: PsiElement): Boolean {
    if (element !is PsiField || !SpringProjectGate.isSpringProject(element.project)) return false
    return isInjected(element) || element.containingClass?.hasAnnotation(SpringNames.CONFIGURATION_PROPERTIES) == true
  }

  private fun isFrameworkCalled(method: PsiMethod): Boolean {
    val containingClass = method.containingClass ?: return false
    if (method.isConstructor) return SpringBeans.isBeanClass(containingClass)
    return MetaAnnotationUtil.isMetaAnnotated(method, SpringNames.FRAMEWORK_CALLED_METHOD_MARKERS)
  }

  private fun isInjected(field: PsiField): Boolean =
    SpringBeans.isInjectMarked(field) || field.hasAnnotation(SpringNames.VALUE)
}
