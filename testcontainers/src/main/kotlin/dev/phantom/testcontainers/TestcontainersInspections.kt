// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.testcontainers

import com.intellij.codeInsight.intention.QuickFixFactory
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

internal object Tc {
  const val CONTAINER = "org.testcontainers.junit.jupiter.Container"
  const val TESTCONTAINERS = "org.testcontainers.junit.jupiter.Testcontainers"
  const val EXTENSION = "org.testcontainers.junit.jupiter.TestcontainersExtension"
  const val STARTABLE = "org.testcontainers.lifecycle.Startable"
  const val GENERIC_CONTAINER = "org.testcontainers.containers.GenericContainer"
  const val IMAGE_NAME = "org.testcontainers.utility.DockerImageName"
  const val SERVICE_CONNECTION = "org.springframework.boot.testcontainers.service.connection.ServiceConnection"
  const val DYNAMIC_PROPERTY_SOURCE = "org.springframework.test.context.DynamicPropertySource"
  const val TEST_INSTANCE = "org.junit.jupiter.api.TestInstance"
  const val EXTEND_WITH = "org.junit.jupiter.api.extension.ExtendWith"
  const val JVM_STATIC = "kotlin.jvm.JvmStatic"

  /**
   * Testcontainers is on the classpath of [file]'s module. Without it nothing here can be reported, and the image tag
   * check would otherwise resolve every call of every Java and Kotlin file.
   */
  fun isTestcontainersModule(file: PsiFile): Boolean {
    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return false
    if (module.isDisposed) return false
    if (JavaLibraryUtil.hasLibraryClass(module, STARTABLE)) return true
    // Light test fixtures declare the classes as source stubs instead of a library.
    return ApplicationManager.getApplication().isUnitTestMode &&
           JavaPsiFacade.getInstance(module.project).findClass(STARTABLE, module.getModuleWithDependenciesAndLibrariesScope(false)) != null
  }

  /** Whether [psi] is (meta-)annotated with [name], two levels deep: `@IntegrationTest` carrying `@Testcontainers`. */
  fun annotated(psi: PsiModifierListOwner, name: String, depth: Int = 2): Boolean =
    psi.annotations.any { a ->
      a.qualifiedName == name || (depth > 0 && a.resolveAnnotationType()?.let { annotated(it, name, depth - 1) } == true)
    }

  /** The class, a superclass, an enclosing class (for @Nested) or a meta-annotation brings in the Testcontainers extension. */
  fun startsContainers(psi: PsiClass): Boolean =
    generateSequence(psi) { it.containingClass }.any { outer ->
      InheritanceUtil.getSuperClasses(outer).plus(outer).any { c ->
        annotated(c, TESTCONTAINERS) || c.annotations.any { a ->
          a.qualifiedName == EXTEND_WITH && a.findAttributeValue("value")?.text?.contains("TestcontainersExtension") == true
        }
      }
    }

  /** A JUnit class with one test instance for all its tests (`@TestInstance(PER_CLASS)`). */
  fun perClass(psi: PsiClass): Boolean =
    generateSequence(psi) { it.containingClass }.any { c -> c.getAnnotation(TEST_INSTANCE)?.findAttributeValue("value")?.text?.contains("PER_CLASS") == true }

  /** Static in Java; in Kotlin, a member of a companion or other object (where Testcontainers and Spring find it static). */
  fun isStatic(member: PsiModifierListOwner, node: UElement): Boolean {
    if (member.language.id == "JAVA") return member.hasModifierProperty(PsiModifier.STATIC)
    if (member.hasModifierProperty(PsiModifier.STATIC)) return true
    // no Kotlin PSI classes here: the object declaration is found by its PSI class name
    return generateSequence(node.sourcePsi?.parent) { it.parent }.takeWhile { it !is PsiFile }
      .any { it.javaClass.simpleName == "KtObjectDeclaration" }
  }

  /** What is wrong with an image reference, or null when it has a tag or digest. */
  fun imageProblem(image: String): String? {
    if (image.isBlank() || '@' in image) return null
    val last = image.substringAfterLast('/')
    val tag = last.substringAfter(':', "")
    return when {
      ':' !in last -> TestcontainersBundle.message("image.no.tag", image)
      tag == "latest" -> TestcontainersBundle.message("image.latest", image)
      else -> null
    }
  }

  fun makeStatic(member: PsiModifierListOwner): Array<LocalQuickFix> =
    if (member.language.id == "JAVA") arrayOf(QuickFixFactory.getInstance().createModifierListFix(member, PsiModifier.STATIC, true, false))
    else LocalQuickFix.EMPTY_ARRAY
}

/** A `@Container` that never starts: its class lacks `@Testcontainers`, or it is not a container (Startable). */
class ContainerNotStartedInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
    if (!Tc.isTestcontainersModule(holder.file)) PsiElementVisitor.EMPTY_VISITOR
    else UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitField(node: UField): Boolean {
        val field = node.javaPsi as? PsiField ?: return true
        if (!Tc.annotated(field, Tc.CONTAINER, 0)) return true
        val anchor = node.uastAnchor?.sourcePsi ?: return true
        val type = PsiTypesUtil.getPsiClass(field.type)
        if (type != null && !InheritanceUtil.isInheritor(type, Tc.STARTABLE)) {
          holder.registerProblem(anchor, TestcontainersBundle.message("container.not.startable", type.name ?: ""), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
          return true
        }
        // the enclosing classes count too: a Kotlin companion's container belongs to the class around it
        val owner = node.getParentOfType<UClass>()?.javaPsi ?: return true
        if (!Tc.startsContainers(owner)) {
          val testClass = if (owner.name == "Companion") owner.containingClass ?: owner else owner
          holder.registerProblem(anchor, TestcontainersBundle.message("container.no.extension", testClass.name ?: ""), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        }
        return true
      }
    }, arrayOf(UField::class.java), true)
}

/** An instance `@Container` field: a new container starts (and stops) for each test, which is slow and rarely meant. */
class ContainerPerTestInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
    if (!Tc.isTestcontainersModule(holder.file)) PsiElementVisitor.EMPTY_VISITOR
    else UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitField(node: UField): Boolean {
        val field = node.javaPsi as? PsiField ?: return true
        if (!Tc.annotated(field, Tc.CONTAINER, 0)) return true
        val owner = node.getParentOfType<UClass>()
        if (Tc.isStatic(field, node) || owner?.javaPsi?.let(Tc::perClass) == true) return true
        val anchor = node.uastAnchor?.sourcePsi ?: return true
        holder.registerProblem(anchor, TestcontainersBundle.message("container.per.test"), ProblemHighlightType.WEAK_WARNING, *Tc.makeStatic(field))
        return true
      }
    }, arrayOf(UField::class.java), true)
}

/** An image with no tag, or `:latest`: what the tests run against changes whenever the image is pushed. */
class ContainerImageTagInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
    if (!Tc.isTestcontainersModule(holder.file)) PsiElementVisitor.EMPTY_VISITOR
    else UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitCallExpression(node: UCallExpression): Boolean {
        val method = node.resolve() ?: return true
        val owner = method.containingClass ?: return true
        val imageCall = when {
          method.isConstructor -> InheritanceUtil.isInheritor(owner, false, Tc.GENERIC_CONTAINER) || owner.qualifiedName == Tc.GENERIC_CONTAINER ||
            owner.qualifiedName == Tc.IMAGE_NAME
          else -> owner.qualifiedName == Tc.IMAGE_NAME && method.name == "parse"
        }
        if (!imageCall || method.parameterList.parameters.firstOrNull()?.type?.canonicalText != "java.lang.String") return true
        val argument = node.getArgumentForParameter(0) ?: return true
        val image = argument.evaluateString() ?: return true
        val problem = Tc.imageProblem(image) ?: return true
        holder.registerProblem(argument.sourcePsi ?: return true, problem, ProblemHighlightType.WARNING)
        return true
      }
    }, arrayOf(UCallExpression::class.java), true)
}

/** Spring finds `@DynamicPropertySource` methods and `@ServiceConnection` fields only when they are static. */
class SpringStaticRequiredInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
    if (!Tc.isTestcontainersModule(holder.file)) PsiElementVisitor.EMPTY_VISITOR
    else UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        val method: PsiMethod = node.javaPsi
        if (!Tc.annotated(method, Tc.DYNAMIC_PROPERTY_SOURCE, 0)) return true
        val kotlinShared = method.language.id != "JAVA" && Tc.isStatic(method, node) && node.uAnnotations.any {
          // unresolved (no Kotlin stdlib in the module) it has no qualified name; its text still says what it is
          it.qualifiedName == Tc.JVM_STATIC || (it.qualifiedName == null && it.sourcePsi?.text?.removePrefix("@")?.substringAfterLast('.') == "JvmStatic")
        }
        if (method.hasModifierProperty(PsiModifier.STATIC) || kotlinShared) return true
        val anchor = node.uastAnchor?.sourcePsi ?: return true
        holder.registerProblem(anchor, TestcontainersBundle.message("static.dynamic.property.source"), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, *Tc.makeStatic(method))
        return true
      }

      override fun visitField(node: UField): Boolean {
        val field = node.javaPsi as? PsiField ?: return true
        if (!Tc.annotated(field, Tc.SERVICE_CONNECTION, 0) || Tc.isStatic(field, node)) return true
        // a @Bean method's container is not a field; only test class fields are found by the static rule
        val anchor = node.uastAnchor?.sourcePsi ?: return true
        holder.registerProblem(anchor, TestcontainersBundle.message("static.service.connection"), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, *Tc.makeStatic(field))
        return true
      }
    }, arrayOf(UMethod::class.java, UField::class.java), true)
}
