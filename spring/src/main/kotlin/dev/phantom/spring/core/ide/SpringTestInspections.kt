// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.codeInspection.*
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.*
import org.jetbrains.annotations.NonNls
import org.jetbrains.uast.*
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

@NonNls
internal object SpringTestNames {
  const val SQL: String = "org.springframework.test.context.jdbc.Sql"
  const val DIRTIES_CONTEXT: String = "org.springframework.test.annotation.DirtiesContext"
  const val EXTEND_WITH: String = "org.junit.jupiter.api.extension.ExtendWith"
  const val SPRING_EXTENSION: String = "org.springframework.test.context.junit.jupiter.SpringExtension"

  /** `@SpringBootTest`, `@DataJpaTest`, `@WebMvcTest`… all carry one of these. */
  val CONTEXT_MARKERS: List<String> = listOf(
    "org.springframework.test.context.BootstrapWith",
    "org.springframework.test.context.ContextConfiguration",
    "org.springframework.test.context.junit.jupiter.SpringJUnitConfig",
  )

  /** Whether the test class (or a superclass) runs in a Spring test context. */
  fun hasSpringContext(psiClass: PsiClass): Boolean {
    val classes = LinkedHashSet<PsiClass>().apply { add(psiClass); InheritanceUtil.getSuperClasses(psiClass, this, false) }
    return classes.any { c ->
      MetaAnnotationUtil.isMetaAnnotated(c, CONTEXT_MARKERS) ||
      c.annotations.any { a -> a.qualifiedName == EXTEND_WITH && a.text.contains("SpringExtension") }
    }
  }
}

/** `@Sql` scripts that are not on the test classpath: the test fails before it starts. */
class SpringSqlScriptInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        if (node.qualifiedName != SpringTestNames.SQL) return true
        val owner = node.uastParent as? UDeclaration ?: return true
        val testClass = (owner as? UClass)?.javaPsi ?: (owner as? UMethod)?.javaPsi?.containingClass ?: return true
        for (attribute in listOf("value", "scripts")) {
          val value = node.findDeclaredAttributeValue(attribute) ?: continue
          val items = if (value is UCallExpression && value.kind == UastCallKind.NESTED_ARRAY_INITIALIZER) value.valueArguments else listOf(value)
          for (item in items) {
            val path = item.evaluateString() ?: continue
            if (path.isBlank() || path.contains("\${") || exists(testClass, path)) continue
            val psi = (item as? UInjectionHost)?.psiLanguageInjectionHost ?: item.sourcePsi ?: continue
            holder.registerProblem(psi, PhantomSpringBundle.message("inspection.sql.script.message", path))
          }
        }
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }

  /** Resolves like Spring: `classpath:` and `/x` from the classpath root, plain names next to the test class's package. */
  private fun exists(testClass: PsiClass, raw: String): Boolean {
    if (raw.startsWith("http:") || raw.startsWith("https:") || raw.startsWith("file:")) return true
    val path = when {
      raw.startsWith("classpath:") -> raw.removePrefix("classpath:").trimStart('/')
      raw.startsWith("classpath*:") -> return true
      raw.startsWith("/") -> raw.trimStart('/')
      else -> (testClass.qualifiedName?.substringBeforeLast('.', "")?.replace('.', '/')?.let { if (it.isEmpty()) "" else "$it/" } ?: "") + raw
    }
    if (path.contains('*')) return true
    val module = ModuleUtilCore.findModuleForPsiElement(testClass) ?: return true
    val roots = ModuleRootManager.getInstance(module).orderEntries().recursively().withoutSdk().withoutLibraries().sources().roots
    return roots.any { it.findFileByRelativePath(path) != null }
  }
}

/** `@DirtiesContext` on a test that has no Spring test context does nothing. */
class SpringDirtiesContextInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitClass(node: UClass): Boolean {
        val psiClass = node.javaPsi
        if (psiClass.isInterface || psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) return true
        val dirties = node.uAnnotations.firstOrNull { it.qualifiedName == SpringTestNames.DIRTIES_CONTEXT }
                      ?: node.methods.firstNotNullOfOrNull { m -> m.uAnnotations.firstOrNull { it.qualifiedName == SpringTestNames.DIRTIES_CONTEXT } }
                      ?: return true
        if (SpringTestNames.hasSpringContext(psiClass)) return true
        val anchor = dirties.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.dirties.context.message"), ProblemHighlightType.WEAK_WARNING)
        return true
      }
    }, arrayOf(UClass::class.java), true)
  }

}

/**
 * `@TestBean` whose factory method does not exist: a static, parameterless method named by `methodName` (or
 * `Class#method`), by default the field's name, in the test class, its superclasses or enclosing classes. Kotlin
 * companion functions count too, so a missing `@JvmStatic` is not reported.
 */
class SpringTestBeanFactoryInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitField(node: UField): Boolean {
        val annotation = node.findAnnotation(TEST_BEAN) ?: return true
        val owner = node.javaPsi.let { it as? PsiField }?.containingClass ?: return true
        val declared = annotation.findDeclaredAttributeValue("methodName")?.evaluateString()?.takeIf { it.isNotBlank() }
        val found = if (declared != null && '#' in declared) {
          val className = declared.substringBefore('#')
          val psiClass = JavaPsiFacade.getInstance(holder.project).findClass(className, com.intellij.psi.search.GlobalSearchScope.allScope(holder.project))
          psiClass == null || hasFactory(listOf(psiClass), declared.substringAfter('#'))
        }
        else {
          val names = if (declared != null) listOf(declared) else listOf(node.name, node.name + "TestOverride")
          names.any { hasFactory(searched(owner), it) }
        }
        if (found) return true
        val name = declared ?: node.name
        val anchor = annotation.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.test.bean.factory.message", name))
        return true
      }
    }, arrayOf(UField::class.java), true)
  }

  private fun searched(owner: PsiClass): List<PsiClass> {
    val result = LinkedHashSet<PsiClass>()
    var c: PsiClass? = owner
    while (c != null) {
      result += c
      InheritanceUtil.getSuperClasses(c, result, false)
      c = c.containingClass
    }
    return result.toList()
  }

  private fun hasFactory(classes: List<PsiClass>, name: String): Boolean = classes.any { c ->
    val candidates = c.findMethodsByName(name, false).toList() + c.innerClasses.filter { it.name == "Companion" }.flatMap { it.findMethodsByName(name, false).toList() }
    candidates.any { it.parameterList.parametersCount == 0 }
  }

  companion object {
    @NonNls const val TEST_BEAN: String = "org.springframework.test.context.bean.override.convention.TestBean"
  }
}
