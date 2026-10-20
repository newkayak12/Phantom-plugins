// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * A query string built from a value that is not a constant (`"... where name = '" + name + "'"`, a Kotlin template,
 * `String.format`) passed straight to JDBC, Spring's `JdbcTemplate`/`JdbcClient`, or JPA/Hibernate `createQuery`.
 *
 * Only strings and other non-numeric values count as dangerous; numbers, booleans, enums and constants do not. A
 * variable is followed one step back to its initializer. Builders (`StringBuilder`) and values passed through other
 * methods are not traced, so some injections go unreported rather than flagging safe code.
 */
class SqlInjectionInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val facade = JavaPsiFacade.getInstance(holder.project)
    val scope = holder.file.resolveScope
    if (SINKS.keys.none { facade.findClass(it, scope) != null }) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitCallExpression(node: UCallExpression): Boolean {
        val name = node.methodName ?: return true
        if (SINKS.values.none { name in it }) return true
        val method = node.resolve() ?: return true
        val owner = method.containingClass ?: return true
        val methods = SINKS.entries.firstOrNull { (fqn, _) -> owner.qualifiedName == fqn || InheritanceUtil.isInheritor(owner, fqn) }?.value ?: return true
        if (name !in methods) return true
        val first = method.parameterList.parameters.firstOrNull() ?: return true
        if (!first.type.equalsToText(CommonClassNames.JAVA_LANG_STRING)) return true
        val argument = node.getArgumentForParameter(0) ?: return true
        val dynamic = dynamicPart(argument, 0) ?: return true
        val anchor = argument.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomJpaBundle.message("sql.injection.message", dynamic.sourcePsi?.text?.take(40) ?: "value"))
        return true
      }
    }, arrayOf(UCallExpression::class.java), true)
  }

  /** The first non-constant, string-like piece the query is built from, or null when it is constant or not built here. */
  private fun dynamicPart(expression: UExpression, depth: Int): UExpression? {
    val e = expression.skipParenthesizedExprDown()
    when (e) {
      is UPolyadicExpression -> {
        if (e.operator != UastBinaryOperator.PLUS || !isString(e.getExpressionType())) return null
        return e.operands.firstOrNull { isDangerous(it) }
      }
      is UQualifiedReferenceExpression, is UCallExpression -> {
        val call = (e as? UQualifiedReferenceExpression)?.selector as? UCallExpression ?: e as? UCallExpression ?: return null
        val name = call.methodName
        if (name == "format" || name == "formatted") {
          val receiverIsString = call.receiverType?.let(::isString) == true
          val args = if (receiverIsString) call.valueArguments else call.valueArguments.drop(1)
          if (call.receiverType != null && !receiverIsString && call.resolve()?.containingClass?.qualifiedName != CommonClassNames.JAVA_LANG_STRING) return null
          return args.firstOrNull { isDangerous(it) }
        }
        if (e is UQualifiedReferenceExpression) return reference(e, depth)
        return null
      }
      is USimpleNameReferenceExpression -> return reference(e, depth)
      else -> return null
    }
  }

  private fun reference(e: UReferenceExpression, depth: Int): UExpression? {
    if (depth > 0) return null
    val variable = e.resolve()?.toUElement() as? ULocalVariable ?: return null
    val initializer = variable.uastInitializer ?: return null
    return dynamicPart(initializer, depth + 1)
  }

  private fun isDangerous(operand: UExpression): Boolean {
    if (operand.evaluate() != null) return false
    val inner = operand.skipParenthesizedExprDown()
    if (inner is ULiteralExpression) return false
    val type = operand.getExpressionType() ?: return false
    if (type is PsiPrimitiveType || PsiUtil.resolveClassInClassTypeOnly(type)?.let { it.isEnum || isSafeClass(it) } == true) return false
    // a constant from another file may not evaluate in a light pass: a static final String field still is one
    val field = (inner as? UReferenceExpression)?.resolve() as? PsiField
    if (field != null && field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)) return false
    // an enum's name() is as safe as the enum
    if (inner is UQualifiedReferenceExpression && (inner.selector as? UCallExpression)?.methodName == "name" &&
        PsiUtil.resolveClassInClassTypeOnly(inner.receiver.getExpressionType())?.isEnum == true) return false
    return true
  }

  private fun isSafeClass(psiClass: PsiClass): Boolean =
    InheritanceUtil.isInheritor(psiClass, false, CommonClassNames.JAVA_LANG_NUMBER) || psiClass.qualifiedName in SAFE

  private fun isString(type: PsiType?): Boolean = type != null && (type.equalsToText(CommonClassNames.JAVA_LANG_STRING) || type.equalsToText("kotlin.String"))

  private companion object {
    val JDBC_TEMPLATE = setOf("query", "queryForObject", "queryForList", "queryForMap", "queryForRowSet", "queryForStream", "update", "batchUpdate", "execute")
    val JPA = setOf("createQuery", "createNativeQuery")
    val HIBERNATE = setOf("createQuery", "createNativeQuery", "createSelectionQuery", "createMutationQuery", "createNativeMutationQuery")
    val SINKS: Map<String, Set<String>> = mapOf(
      "org.springframework.jdbc.core.JdbcOperations" to JDBC_TEMPLATE,
      "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations" to JDBC_TEMPLATE,
      "org.springframework.jdbc.core.simple.JdbcClient" to setOf("sql"),
      "org.springframework.r2dbc.core.DatabaseClient" to setOf("sql"),
      "jakarta.persistence.EntityManager" to JPA,
      "javax.persistence.EntityManager" to JPA,
      "org.hibernate.query.QueryProducer" to HIBERNATE,
      "org.hibernate.SharedSessionContract" to HIBERNATE,
      "java.sql.Statement" to setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch"),
      "java.sql.Connection" to setOf("prepareStatement", "prepareCall"),
    )
    val SAFE = setOf("java.lang.Boolean", "java.lang.Character", "java.util.UUID", "java.time.LocalDate", "java.time.LocalDateTime",
                     "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime", "java.time.LocalTime",
                     "kotlin.Int", "kotlin.Long", "kotlin.Boolean")
  }
}
