// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.mybatis

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlTokenType
import javax.swing.Icon

/** On a mapper interface and its methods: the mapper XML files and the statements for each method. */
class MapperJavaLineMarkerProvider : RelatedItemLineMarkerProvider() {
  override fun getName(): String = PhantomMyBatisBundle.message("gutter.java.name")
  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
    if (element !is PsiIdentifier) return
    val targets: List<PsiElement> = when (val parent = element.parent) {
      is PsiClass -> if (parent.nameIdentifier == element && parent.isInterface) MyBatis.mapperFiles(parent).mapNotNull { MyBatis.mapperRoot(it) } else return
      is PsiMethod -> if (parent.nameIdentifier == element && parent.containingClass?.isInterface == true) MyBatis.statementsFor(parent) else return
      else -> return
    }
    if (targets.isEmpty()) return
    result += NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
      .setTargets(targets)
      .setTooltipText(PhantomMyBatisBundle.message("gutter.java.tooltip"))
      .setPopupTitle(PhantomMyBatisBundle.message("gutter.popup"))
      .createLineMarkerInfo(element)
  }
}

/** On `<mapper>` and its statements: the interface, and the method each statement is for. */
class MapperXmlLineMarkerProvider : RelatedItemLineMarkerProvider() {
  override fun getName(): String = PhantomMyBatisBundle.message("gutter.xml.name")
  override fun getIcon(): Icon = AllIcons.Gutter.ImplementingMethod

  override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
    if (element.node?.elementType != XmlTokenType.XML_NAME) return
    val tag = element.parent as? XmlTag ?: return
    // the name of the start tag, not of the end tag
    if (tag.children.firstOrNull { it.node.elementType == XmlTokenType.XML_NAME } != element) return
    val root = MyBatis.mapperOf(tag) ?: return
    val targets: List<PsiElement> = when {
      tag == root -> listOfNotNull(MyBatis.mapperClass(tag.containingFile))
      tag.parentTag == root && tag.name in MyBatis.STATEMENTS -> MyBatis.methodsFor(tag)
      else -> return
    }
    if (targets.isEmpty()) return
    result += NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementingMethod)
      .setTargets(targets)
      .setTooltipText(PhantomMyBatisBundle.message("gutter.xml.tooltip"))
      .setPopupTitle(PhantomMyBatisBundle.message("gutter.popup"))
      .createLineMarkerInfo(element)
  }
}
