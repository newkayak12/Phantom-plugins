// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.asyncapi

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import javax.swing.Icon

/** A gutter icon on an AsyncAPI channel key that goes to the Kafka listeners and senders of its topic. */
class ChannelLineMarkerProvider : RelatedItemLineMarkerProvider() {
  override fun getName(): String = AsyncApiBundle.message("gutter.channel.name")
  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
    if (element.firstChild != null || !AsyncApiDocs.isSpec(element.containingFile)) return
    val channel = AsyncApiDocs.channels(element.containingFile).firstOrNull { it.anchor == element } ?: return
    val targets = KafkaCode.uses(element.project).filter { it.topic == channel.topic }.map { it.element }
    if (targets.isEmpty()) return
    result += NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
      .setTargets(targets)
      .setTooltipText(AsyncApiBundle.message("gutter.channel.tooltip", channel.topic))
      .setPopupTitle(AsyncApiBundle.message("gutter.channel.popup"))
      .createLineMarkerInfo(element)
  }
}

/** A gutter icon on a @KafkaListener or a `KafkaTemplate.send` call that goes to the AsyncAPI channels of its topic. */
class KafkaTopicLineMarkerProvider : RelatedItemLineMarkerProvider() {
  override fun getName(): String = AsyncApiBundle.message("gutter.code.name")
  override fun getIcon(): Icon = AllIcons.Gutter.ImplementingMethod

  override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
    if (element !is PsiIdentifier) return
    val topics = when (val parent = element.parent) {
      is PsiMethod -> if (parent.nameIdentifier == element) KafkaCode.listenerTopics(parent) else return
      is PsiClass -> if (parent.nameIdentifier == element) KafkaCode.listenerTopics(parent) else return
      is PsiReferenceExpression -> listOfNotNull((parent.parent as? PsiMethodCallExpression)?.let(KafkaCode::sendTopic))
      else -> return
    }
    if (topics.isEmpty()) return
    val targets = AsyncApiDocs.specFiles(element.project).flatMap { AsyncApiDocs.channels(it) }.filter { it.topic in topics }.map { it.key }
    if (targets.isEmpty()) return
    result += NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementingMethod)
      .setTargets(targets)
      .setTooltipText(AsyncApiBundle.message("gutter.code.tooltip", topics.joinToString()))
      .setPopupTitle(AsyncApiBundle.message("gutter.code.popup"))
      .createLineMarkerInfo(element)
  }
}
