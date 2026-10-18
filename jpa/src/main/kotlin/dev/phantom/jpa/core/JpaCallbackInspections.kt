// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Entity graphs that name attributes the entity does not have: `@NamedEntityGraph(attributeNodes =
 * @NamedAttributeNode("x"))` on an entity, and `@EntityGraph(attributePaths = "a.b")` on a Spring Data repository
 * method, resolved against the repository's entity. Hibernate fails when it builds the graph.
 */
class JpaEntityGraphInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, Visitor(holder), arrayOf(UClass::class.java, UMethod::class.java), true)
  }

  private class Visitor(private val holder: ProblemsHolder) : AbstractUastNonRecursiveVisitor() {
    override fun visitClass(node: UClass): Boolean {
      val entity = node.javaPsi
      if (!JpaModel.isEntity(entity)) return true
      val anchor = node.uastAnchor?.sourcePsi ?: return true
      for (graph in namedGraphs(entity)) {
        for (attributeNode in annotations(graph.findDeclaredAttributeValue("attributeNodes"))) {
          val value = attributeNode.findDeclaredAttributeValue("value") ?: continue
          val name = stringOf(value) ?: continue
          if (JpaModel.attribute(entity, name) == null) {
            holder.registerProblem(target(value, anchor), PhantomJpaBundle.message("graph.unknown.attribute", name, entity.name ?: ""))
          }
        }
      }
      return true
    }

    override fun visitMethod(node: UMethod): Boolean {
      val method = node.javaPsi
      val annotation = method.getAnnotation(ENTITY_GRAPH) ?: return true
      val repository = node.getParentOfType<UClass>()?.javaPsi ?: return true
      if (!repository.isInterface || !InheritanceUtil.isInheritor(repository, DerivedQuery.REPOSITORY)) return true
      val entity = DerivedQuery.entityOf(repository) ?: return true
      val anchor = node.uastAnchor?.sourcePsi ?: return true
      for (value in AnnotationUtil.arrayAttributeValues(annotation.findDeclaredAttributeValue("attributePaths"))) {
        val path = stringOf(value) ?: continue
        var owner: PsiClass? = entity
        for (segment in path.split('.')) {
          val current = owner ?: break
          val member = JpaModel.attribute(current, segment)
          if (member == null) {
            holder.registerProblem(target(value, anchor), PhantomJpaBundle.message("graph.unknown.path", path, segment, current.name ?: ""))
            break
          }
          owner = JpaModel.valueClass(member)
        }
      }
      return true
    }

    private fun namedGraphs(entity: PsiClass): List<PsiAnnotation> =
      NAMED_GRAPH.mapNotNull { entity.getAnnotation(it) } +
      NAMED_GRAPHS.mapNotNull { entity.getAnnotation(it) }.flatMap { annotations(it.findDeclaredAttributeValue("value")) }

    private fun annotations(value: PsiAnnotationMemberValue?): List<PsiAnnotation> =
      AnnotationUtil.arrayAttributeValues(value).filterIsInstance<PsiAnnotation>()

    private fun stringOf(value: PsiAnnotationMemberValue): String? =
      JavaPsiFacade.getInstance(value.project).constantEvaluationHelper.computeConstantExpression(value) as? String

    /** The string itself when it is in this file (Java); the declaration otherwise (Kotlin light annotations). */
    private fun target(value: PsiElement, anchor: PsiElement): PsiElement =
      if (value.isPhysical && value.containingFile == holder.file && value.textLength > 0) value else anchor
  }

  private companion object {
    const val ENTITY_GRAPH = "org.springframework.data.jpa.repository.EntityGraph"
    val NAMED_GRAPH = listOf("jakarta.persistence.NamedEntityGraph", "javax.persistence.NamedEntityGraph")
    val NAMED_GRAPHS = listOf("jakarta.persistence.NamedEntityGraphs", "javax.persistence.NamedEntityGraphs")
  }
}

/**
 * Lifecycle callbacks (`@PrePersist`, `@PostLoad`, ...) with a signature JPA does not call: on an entity or mapped
 * superclass they take no parameters, in an entity listener class exactly one (the entity); both return void and are
 * not static. Also two methods of one class for the same event, which the provider rejects.
 */
class JpaEntityListenerInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, Visitor(holder), arrayOf(UMethod::class.java), true)
  }

  private class Visitor(private val holder: ProblemsHolder) : AbstractUastNonRecursiveVisitor() {
    override fun visitMethod(node: UMethod): Boolean {
      val method = node.javaPsi
      val events = events(method)
      if (events.isEmpty()) return true
      val owner = method.containingClass ?: return true
      if (owner.hasAnnotation("jakarta.persistence.Embeddable") || owner.hasAnnotation("javax.persistence.Embeddable")) return true
      val anchor = node.uastAnchor?.sourcePsi ?: return true
      val onEntity = JpaModel.isPersistent(owner)
      val event = events.first()
      val parameters = method.parameterList.parametersCount
      when {
        onEntity && parameters != 0 -> holder.registerProblem(anchor, PhantomJpaBundle.message("callback.entity.parameters", event))
        !onEntity && parameters != 1 -> holder.registerProblem(anchor, PhantomJpaBundle.message("callback.listener.parameters", event))
      }
      if (method.returnType != null && method.returnType != PsiTypes.voidType()) {
        holder.registerProblem(anchor, PhantomJpaBundle.message("callback.return.type", event))
      }
      if (method.hasModifierProperty(PsiModifier.STATIC)) holder.registerProblem(anchor, PhantomJpaBundle.message("callback.static", event))
      val earlier = owner.methods.takeWhile { it != method }.firstOrNull { other -> events(other).any { it in events } }
      if (earlier != null) {
        holder.registerProblem(anchor, PhantomJpaBundle.message("callback.duplicate", events.first { it in events(earlier) }, earlier.name))
      }
      return true
    }

    private fun events(method: PsiMethod): List<String> =
      EVENTS.filter { event -> PACKAGES.any { method.hasAnnotation("$it.$event") } }.map { "@$it" }
  }

  private companion object {
    val PACKAGES = listOf("jakarta.persistence", "javax.persistence")
    val EVENTS = listOf("PrePersist", "PostPersist", "PreUpdate", "PostUpdate", "PreRemove", "PostRemove", "PostLoad")
  }
}

/**
 * Entity listener classes named in `@EntityListeners` that JPA cannot use or that do nothing: a listener without a
 * no-argument constructor (unless Spring's bean container creates the listeners, as Spring Boot sets up), and a
 * listener without any lifecycle callback method.
 */
class JpaEntityListenerUsageInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    val springCreatesListeners by lazy {
      val scope = module.getModuleWithDependenciesAndLibrariesScope(false)
      SPRING_BEAN_CONTAINER.any { JavaPsiFacade.getInstance(holder.project).findClass(it, scope) != null }
    }
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitClass(node: UClass): Boolean {
        val owner = node.javaPsi
        if (!JpaModel.isPersistent(owner)) return true
        val annotation = LISTENERS.firstNotNullOfOrNull { owner.getAnnotation(it) } ?: return true
        val anchor = node.uastAnchor?.sourcePsi ?: return true
        for (value in AnnotationUtil.arrayAttributeValues(annotation.findDeclaredAttributeValue("value"))) {
          val literal = value as? PsiClassObjectAccessExpression ?: continue
          val listener = com.intellij.psi.util.PsiUtil.resolveClassInClassTypeOnly(literal.operand.type) ?: continue
          val target = if (value.isPhysical && value.containingFile == holder.file && value.textLength > 0) value else anchor
          if (listener.allMethods.none { method -> CALLBACK_EVENTS.any { event -> PACKAGES.any { method.hasAnnotation("$it.$event") } } }) {
            holder.registerProblem(target, PhantomJpaBundle.message("listener.no.callbacks", listener.name ?: ""), com.intellij.codeInspection.ProblemHighlightType.WEAK_WARNING)
          }
          else if (!springCreatesListeners && listener.constructors.isNotEmpty() && listener.constructors.none { it.parameterList.isEmpty }) {
            holder.registerProblem(target, PhantomJpaBundle.message("listener.constructor", listener.name ?: ""))
          }
        }
        return true
      }
    }, arrayOf(UClass::class.java), true)
  }

  private companion object {
    /** Spring Boot hands Hibernate this container, which creates listeners as Spring beans (constructor injection works). */
    val SPRING_BEAN_CONTAINER = listOf("org.springframework.orm.hibernate5.SpringBeanContainer", "org.springframework.orm.jpa.hibernate.SpringBeanContainer")
    val LISTENERS = listOf("jakarta.persistence.EntityListeners", "javax.persistence.EntityListeners")
    val PACKAGES = listOf("jakarta.persistence", "javax.persistence")
    val CALLBACK_EVENTS = listOf("PrePersist", "PostPersist", "PreUpdate", "PostUpdate", "PreRemove", "PostRemove", "PostLoad")
  }
}
