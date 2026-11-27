// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import dev.phantom.spring.core.GhostMode
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.PhantomSpringIcons
import dev.phantom.spring.core.SpringBeans
import dev.phantom.spring.core.SpringProjectGate
import org.jetbrains.uast.*
import javax.swing.Icon

/**
 * Gutter icons on injection points (navigate to candidate beans) and on beans (navigate to injection points).
 * Only cheap annotation checks run during highlighting; targets are searched when the icon is clicked.
 */
open class SpringBeanLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBundle.message("line.marker.name")

  override fun getIcon(): Icon = PhantomSpringIcons.Bean

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {

    if (GhostMode.enabled) return null
    if (element.firstChild != null) return null
    val owner = element.parent as? PsiNameIdentifierOwner ?: return null
    if (owner.nameIdentifier != element) return null
    if (!SpringProjectGate.isSpringProject(element.project)) return null

    return when (val declaration = getUParentForIdentifier(element)) {
      is UParameter, is UField -> injectionPointMarker(element, declaration)
      is UClass -> {
        val psiClass = declaration.javaPsi
        if (SpringBeans.isBeanClass(psiClass)) beanMarker(element, psiClass.name ?: return null) { SpringBeans.findInjectionPoints(psiClass) } else null
      }
      is UMethod -> {
        val method = declaration.javaPsi
        if (SpringBeans.isBeanMethod(method)) beanMarker(element, method.name) { SpringBeans.findInjectionPoints(method) } else null
      }
      else -> null
    }
  }

  private fun injectionPointMarker(element: PsiElement, declaration: UElement): LineMarkerInfo<*>? {
    val point = SpringBeans.injectionPointOf(declaration) ?: return null
    val beanType = SpringBeans.beanTypeOf(point.type) ?: return null
    // Library types (DataSource, ObjectMapper) come from auto-configuration, which v0.1 does not model.
    if (!SpringBeans.isProjectSourceClass(beanType)) return null
    val typeName = beanType.name ?: return null
    return NavigationGutterIconBuilder.create(PhantomSpringIcons.Bean)
      .setTargets(NotNullLazyValue.lazy { SpringBeans.findCandidates(point).map { it.navigationElement } })
      .setTooltipText(PhantomSpringBundle.message("gutter.injection.tooltip"))
      .setPopupTitle(PhantomSpringBundle.message("gutter.injection.popup.title", typeName))
      .setEmptyPopupText(PhantomSpringBundle.message("gutter.injection.empty", typeName))
      .createLineMarkerInfo(element)
  }

  private fun beanMarker(element: PsiElement, beanName: String, points: () -> List<dev.phantom.spring.core.InjectionPoint>): LineMarkerInfo<*> =
    NavigationGutterIconBuilder.create(PhantomSpringIcons.Bean)
      .setTargets(NotNullLazyValue.lazy { points().map { it.navigationTarget } })
      .setTooltipText(PhantomSpringBundle.message("gutter.bean.tooltip"))
      .setPopupTitle(PhantomSpringBundle.message("gutter.bean.popup.title", beanName))
      .setEmptyPopupText(PhantomSpringBundle.message("gutter.bean.empty"))
      .createLineMarkerInfo(element)
}
