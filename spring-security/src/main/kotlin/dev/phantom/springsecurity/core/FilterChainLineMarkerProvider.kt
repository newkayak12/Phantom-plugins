// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springsecurity.core

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import javax.swing.Icon

/** A gutter icon on SecurityFilterChain bean methods whose tooltip lists the chain's place, matcher and rules. */
class FilterChainLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringSecurityBundle.message("marker.name")

  override fun getIcon(): Icon = AllIcons.Diff.Lock

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element !is PsiIdentifier) return null
    val method = element.parent as? PsiMethod ?: return null
    if (method.nameIdentifier != element || !FilterChains.isFilterChainBean(method)) return null
    if (!SecurityGate.isSecurityElement(method)) return null
    return LineMarkerInfo(element, element.textRange, icon, { tooltip(method) }, null, GutterIconRenderer.Alignment.LEFT, { name })
  }

  companion object {
    fun tooltip(method: PsiMethod): String {
      val chain = FilterChains.of(method) ?: return ""
      val all = FilterChains.all(method.project)
      val position = all.indexOfFirst { it.method == method } + 1
      val order = chain.order?.toString() ?: PhantomSpringSecurityBundle.message("marker.order.default")
      val lines = mutableListOf(PhantomSpringSecurityBundle.message("marker.header", position, all.size, order))
      lines += if (chain.matchesEveryRequest) PhantomSpringSecurityBundle.message("marker.matcher.any")
      else PhantomSpringSecurityBundle.message("marker.matcher", chain.securityMatchers.joinToString(", "))
      if (chain.rules.isEmpty()) {
        lines += PhantomSpringSecurityBundle.message("marker.rules.none")
      } else {
        lines += PhantomSpringSecurityBundle.message("marker.rules")
        chain.rules.forEach { lines += "  " + describe(it) }
      }
      return lines.joinToString("<br>") { StringUtil.escapeXmlEntities(it).replace("  ", "&nbsp;&nbsp;") }.let { "<html>$it</html>" }
    }

    fun describe(rule: AccessRule): String {
      val matcher = if (rule.isAnyRequest) PhantomSpringSecurityBundle.message("marker.rule.any")
      else listOfNotNull(rule.httpMethod, rule.patterns.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" ")
      return "$matcher → ${rule.access}"
    }
  }
}
