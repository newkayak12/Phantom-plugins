// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.security

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElementVisitor
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.PhantomSpringBundle
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Role names written with the `ROLE_` prefix where Spring Security adds it itself:
 * - `hasRole("ROLE_ADMIN")` / `hasAnyRole(...)` in the HTTP security configuration, `User.withUsername(..).roles(..)`,
 *   `user(..).roles(..)` in MockMvc tests and `@WithMockUser(roles = ...)` throw ("role should not start with 'ROLE_'
 *   since it is automatically inserted")
 * - `hasRole('ROLE_ADMIN')` in a method security expression works, but the prefix is redundant and hides the
 *   mismatch with configuration that uses the plain name
 */
class SecurityRolePrefixInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SecurityGate.isSecurityProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitCallExpression(node: UCallExpression): Boolean {
        if (node.methodName !in ROLE_METHODS) return true
        val owner = node.resolve()?.containingClass?.qualifiedName ?: return true
        if (!owner.startsWith("org.springframework.security.")) return true
        for (argument in node.valueArguments) {
          val role = argument.evaluateString() ?: continue
          if (!role.startsWith(PREFIX)) continue
          val anchor = argument.sourcePsi ?: continue
          holder.registerProblem(anchor, PhantomSpringBundle.message("security.role.prefix.config", role), ProblemHighlightType.GENERIC_ERROR)
        }
        return true
      }

      override fun visitMethod(node: UMethod): Boolean = checkExpressions(node)
      override fun visitClass(node: UClass): Boolean = checkExpressions(node)

      private fun checkExpressions(node: UDeclaration): Boolean {
        for (annotation in node.uAnnotations) {
          if (annotation.qualifiedName == WITH_MOCK_USER) {
            val roles = annotation.findDeclaredAttributeValue("roles") ?: continue
            val values = (roles as? org.jetbrains.uast.UCallExpression)?.valueArguments ?: listOf(roles)
            for (v in values) {
              val role = v.evaluateString() ?: continue
              if (role.startsWith(PREFIX)) holder.registerProblem(v.sourcePsi ?: continue, PhantomSpringBundle.message("security.role.prefix.config", role), ProblemHighlightType.GENERIC_ERROR)
            }
            continue
          }
          if (annotation.qualifiedName !in MethodSecurity.EXPRESSION_ANNOTATIONS) continue
          val host = annotation.findDeclaredAttributeValue("value") as? UInjectionHost ?: continue
          val psi = host.psiLanguageInjectionHost
          val valueRange = ElementManipulators.getValueTextRange(psi)
          val value = valueRange.substring(psi.text)
          for (call in CALL.findAll(value)) {
            val argumentsStart = call.groups[2]!!.range.first
            for (quoted in QUOTED.findAll(call.groupValues[2])) {
              val role = quoted.groupValues[1]
              if (!role.startsWith(PREFIX)) continue
              val start = valueRange.startOffset + argumentsStart + quoted.range.first
              holder.registerProblem(psi, PhantomSpringBundle.message("security.role.prefix.expression", role), ProblemHighlightType.WEAK_WARNING,
                                     TextRange(start, start + quoted.value.length))
            }
          }
        }
        return true
      }
    }, arrayOf(UCallExpression::class.java, UMethod::class.java, UClass::class.java), true)
  }

  private companion object {
    const val PREFIX = "ROLE_"
    val ROLE_METHODS = setOf("hasRole", "hasAnyRole", "roles")
    const val WITH_MOCK_USER = "org.springframework.security.test.context.support.WithMockUser"
    val CALL = Regex("""\b(hasRole|hasAnyRole)\s*\(([^)]*)\)""")
    val QUOTED = Regex("""'([^']*)'""")
  }
}
