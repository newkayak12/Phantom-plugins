// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInspection.*
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.*
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * An injected field hides a dependency from the constructor, so the class can be built half-wired in tests. Test
 * sources are skipped: field injection is how JUnit test classes get beans. Java gets a fix that moves the field to
 * the constructor; Kotlin `lateinit var` is only reported.
 */
class SpringFieldInjectionInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project) || SpringBeans.isInTestSources(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitField(node: UField): Boolean {
        val field = node.javaPsi as? PsiField ?: return true
        if (field.hasModifierProperty(PsiModifier.STATIC) || !SpringBeans.isInjectMarked(field)) return true
        // Kotlin constructor properties are parameters, not field injection
        if (node.sourcePsi?.toUElementOfType<UParameter>() != null) return true
        val psiClass = field.containingClass ?: return true
        if (!SpringBeans.isBeanClass(psiClass)) return true
        val anchor = node.uastAnchor?.sourcePsi ?: return true
        val fixes = if (field.language.isKindOf(com.intellij.lang.java.JavaLanguage.INSTANCE) && psiClass.constructors.size <= 1) arrayOf<LocalQuickFix>(ToConstructorInjectionFix())
                    else LocalQuickFix.EMPTY_ARRAY
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.field.injection.message"), ProblemHighlightType.WEAK_WARNING, *fixes)
        return true
      }
    }, arrayOf(UField::class.java), true)
  }
}

/** Removes the injection annotation, makes the field final and assigns it from a constructor parameter. */
class ToConstructorInjectionFix : LocalQuickFix {
  override fun getFamilyName(): String = PhantomSpringBundle.message("inspection.field.injection.fix")

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val field = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiField::class.java, false) ?: return
    val psiClass = field.containingClass ?: return
    val factory = JavaPsiFacade.getElementFactory(project)
    SpringNames.INJECT_MARKERS.forEach { field.getAnnotation(it)?.delete() }
    field.modifierList?.setModifierProperty(PsiModifier.FINAL, true)
    val name = field.name
    val constructor = psiClass.constructors.singleOrNull() ?: run {
      val created = factory.createConstructor(psiClass.name ?: return)
      val anchor = psiClass.fields.lastOrNull()
      (if (anchor != null) psiClass.addAfter(created, anchor) else psiClass.add(created)) as PsiMethod
    }
    val parameter = factory.createParameter(name, field.type)
    // keep a qualifier: it chooses the bean for the parameter just as it did for the field
    SpringNames.NAMED_QUALIFIERS.firstNotNullOfOrNull { field.getAnnotation(it) }?.let { qualifier ->
      parameter.modifierList?.addAfter(qualifier.copy(), null)
      qualifier.delete()
    }
    constructor.parameterList.add(parameter)
    val body = constructor.body ?: return
    body.add(factory.createStatementFromText("this.$name = $name;", constructor))
    JavaCodeStyleManager.getInstance(project).shortenClassReferences(constructor)
  }
}

/** Spring does not inject static fields or call static `@Autowired` methods; the field silently stays null. */
class SpringStaticInjectionInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitField(node: UField): Boolean {
        val field = node.javaPsi as? PsiField ?: return true
        if (field.hasModifierProperty(PsiModifier.STATIC) && (SpringBeans.isInjectMarked(field) || field.hasAnnotation(SpringNames.VALUE))) report(node)
        return true
      }

      override fun visitMethod(node: UMethod): Boolean {
        val method = node.javaPsi
        if (method.hasModifierProperty(PsiModifier.STATIC) && SpringBeans.isInjectMarked(method)) report(node)
        return true
      }

      private fun report(node: UDeclaration) {
        val anchor = node.uastAnchor?.sourcePsi ?: return
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.static.injection.message"))
      }
    }, arrayOf(UField::class.java, UMethod::class.java), true)
  }
}
