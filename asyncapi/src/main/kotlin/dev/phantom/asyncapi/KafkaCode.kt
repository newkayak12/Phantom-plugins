// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.asyncapi

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** A place in Java code that receives from or sends to [topic]; [element] is the name to navigate to. */
class TopicUse(val topic: String, val element: PsiElement)

/** Spring Kafka listeners and `KafkaTemplate.send` calls with constant topics. */
object KafkaCode {
  const val LISTENER = "org.springframework.kafka.annotation.KafkaListener"
  private val TEMPLATES = listOf("org.springframework.kafka.core.KafkaTemplate", "org.springframework.kafka.core.KafkaOperations")

  /** The `topics` of a @KafkaListener on a method, or on a class whose @KafkaHandler methods it dispatches to. */
  fun listenerTopics(owner: PsiModifierListOwner): List<String> = owner.getAnnotation(LISTENER)?.let { strings(it, "topics") }.orEmpty()

  /** The topic of `send("orders", ...)` on a KafkaTemplate; null for a ProducerRecord, a Message or a topic that is no constant. */
  fun sendTopic(call: PsiMethodCallExpression): String? {
    if (call.methodExpression.referenceName != "send") return null
    val first = call.argumentList.expressions.firstOrNull() ?: return null
    if (call.resolveMethod()?.containingClass?.qualifiedName !in TEMPLATES) return null
    return constant(first)
  }

  /** Listeners and send calls of the project, recomputed after a Java change. Empty without Spring Kafka on the class path. */
  fun uses(project: Project): List<TopicUse> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val scope = GlobalSearchScope.projectScope(project)
    val result = ArrayList<TopicUse>()
    facade.findClass(LISTENER, all)?.let { listener ->
      for (member in AnnotatedElementsSearch.searchPsiMembers(listener, scope).findAll()) {
        val name = (member as? PsiNameIdentifierOwner)?.nameIdentifier ?: continue
        listenerTopics(member).forEach { result += TopicUse(it, name) }
      }
    }
    val sends = TEMPLATES.mapNotNull { facade.findClass(it, all) }.flatMap { it.findMethodsByName("send", false).asList() }
    for (reference in sends.flatMap { MethodReferencesSearch.search(it, scope, true).findAll() }.distinct()) {
      val expression = reference.element as? PsiReferenceExpression ?: continue
      val call = expression.parent as? PsiMethodCallExpression ?: continue
      val topic = sendTopic(call) ?: continue
      result += TopicUse(topic, expression.referenceNameElement ?: expression)
    }
    CachedValueProvider.Result.create(result.toList(), PsiModificationTracker.getInstance(project).forLanguage(JavaLanguage.INSTANCE))
  }

  private fun strings(annotation: PsiAnnotation, name: String): List<String> {
    val value = annotation.findDeclaredAttributeValue(name) ?: return emptyList()
    return AnnotationUtil.arrayAttributeValues(value).mapNotNull { (it as? PsiExpression)?.let(::constant) }
  }

  private fun constant(expression: PsiExpression): String? {
    val helper = JavaPsiFacade.getInstance(expression.project).constantEvaluationHelper
    // computeConstantExpression gives null for some String constants in method bodies (seen in light tests); read the
    // constant's initializer, and a literal's value, then.
    val target = ((expression as? PsiReferenceExpression)?.resolve() as? PsiVariable)?.initializer ?: expression
    val value = helper.computeConstantExpression(expression) ?: helper.computeConstantExpression(target) ?: (target as? PsiLiteralExpression)?.value
    return value as? String
  }
}
