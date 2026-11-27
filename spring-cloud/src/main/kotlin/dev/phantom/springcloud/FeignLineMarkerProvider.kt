// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springcloud

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import javax.swing.Icon

/** Gutter icons between a @FeignClient method and the controller method serving the same endpoint, both ways. */
class FeignLineMarkerProvider : RelatedItemLineMarkerProvider() {
  override fun getName(): String = SpringCloudBundle.message("gutter.name")
  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
    if (element !is PsiIdentifier) return
    val method = element.parent as? PsiMethod ?: return
    if (method.nameIdentifier != element) return
    val owner = method.containingClass
    val (targets, icon, tooltip) = when {
      Feign.isFeignClient(owner) -> Triple(Feign.controllersFor(method), AllIcons.Gutter.ImplementedMethod, "gutter.feign.tooltip")
      Feign.isController(owner) -> Triple(Feign.feignClientsFor(method), AllIcons.Gutter.ImplementingMethod, "gutter.controller.tooltip")
      else -> return
    }
    if (targets.isEmpty()) return
    result += NavigationGutterIconBuilder.create(icon)
      .setTargets(targets)
      .setTooltipText(SpringCloudBundle.message(tooltip))
      .setPopupTitle(SpringCloudBundle.message("gutter.popup"))
      .createLineMarkerInfo(element)
  }
}
