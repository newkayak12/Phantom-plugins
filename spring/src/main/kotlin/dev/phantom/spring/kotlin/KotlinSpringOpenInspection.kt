// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.kotlin

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.codeInspection.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import dev.phantom.spring.core.KotlinOpenPlugins
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.SpringBeans
import dev.phantom.spring.core.SpringProjectGate
import dev.phantom.spring.core.SpringProxies
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Kotlin classes and functions are final. Spring subclasses `@Configuration` classes and beans with proxy annotations,
 * so without the kotlin-spring (all-open) compiler plugin those must be `open`; with it, `open` is redundant.
 */
class KotlinSpringOpenInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return object : KtVisitorVoid() {
      override fun visitClass(klass: KtClass) {
        if (klass.isInterface() || klass.isAnnotation() || klass.isEnum()) return
        val light = klass.toLightClass() ?: return
        val opened = KotlinOpenPlugins.opensClass(light)
        val needsProxy = needsSubclass(light)
        val explicitOpen = klass.hasModifier(KtTokens.OPEN_KEYWORD) || klass.hasModifier(KtTokens.ABSTRACT_KEYWORD)
        when {
          needsProxy && !opened && !explicitOpen -> {
            val anchor = klass.nameIdentifier ?: return
            holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.kotlin.final.class", klass.name.orEmpty()), AddOpenFix())
          }
          opened && klass.hasModifier(KtTokens.OPEN_KEYWORD) && SpringBeans.isBeanClass(light) -> redundant(klass)
        }
      }

      override fun visitNamedFunction(function: KtNamedFunction) {
        val klass = function.parent?.parent as? KtClass ?: return
        if (klass.isInterface()) return
        val light = klass.toLightClass() ?: return
        val method = function.toLightMethods().firstOrNull() ?: return
        val opened = KotlinOpenPlugins.opensClass(light)
        if (function.hasModifier(KtTokens.PRIVATE_KEYWORD)) return // reported by the proxy-method inspection
        if (opened) {
          if (function.hasModifier(KtTokens.OPEN_KEYWORD) && SpringBeans.isBeanClass(light)) redundant(function)
          return
        }
        val advice = SpringProxies.methodAdvice(method) ?: return
        if (function.hasModifier(KtTokens.OPEN_KEYWORD) || function.hasModifier(KtTokens.OVERRIDE_KEYWORD) && !function.hasModifier(KtTokens.FINAL_KEYWORD)) return
        val anchor = function.nameIdentifier ?: return
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.kotlin.final.method", SpringProxies.shortName(advice)), AddOpenFix())
      }

      private fun redundant(owner: KtModifierListOwner) {
        val keyword = owner.modifierList?.getModifier(KtTokens.OPEN_KEYWORD) ?: return
        holder.registerProblem(keyword, PhantomSpringBundle.message("inspection.kotlin.redundant.open"), ProblemHighlightType.LIKE_UNUSED_SYMBOL, RemoveOpenFix())
      }
    }
  }

  /** `@Configuration` classes (unless `proxyBeanMethods = false`) and classes with proxy annotations on the class or a method. */
  private fun needsSubclass(psiClass: PsiClass): Boolean {
    psiClass.getAnnotation(SpringProxies.CONFIGURATION)?.let { configuration ->
      return AnnotationUtil.getBooleanAttributeValue(configuration, "proxyBeanMethods") != false
    }
    if (!SpringBeans.isBeanClass(psiClass)) return false
    if (MetaAnnotationUtil.isMetaAnnotated(psiClass, SpringProxies.ADVICE)) return true
    return psiClass.methods.any { SpringProxies.methodAdvice(it) != null }
  }

  private class AddOpenFix : LocalQuickFix {
    override fun getFamilyName(): String = PhantomSpringBundle.message("inspection.kotlin.add.open")
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      (descriptor.psiElement.parent as? KtModifierListOwner)?.addModifier(KtTokens.OPEN_KEYWORD)
    }
  }

  private class RemoveOpenFix : LocalQuickFix {
    override fun getFamilyName(): String = PhantomSpringBundle.message("inspection.kotlin.remove.open")
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val owner = com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.psiElement, KtModifierListOwner::class.java) ?: return
      owner.removeModifier(KtTokens.OPEN_KEYWORD)
    }
  }
}
