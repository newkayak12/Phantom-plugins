// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.codeInspection.*
import com.intellij.psi.*
import com.intellij.psi.util.PsiUtil
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.*
import org.jetbrains.annotations.NonNls
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

@NonNls
internal object SpringMethodContracts {
  const val SCHEDULED: String = "org.springframework.scheduling.annotation.Scheduled"
  const val EVENT_LISTENER: String = "org.springframework.context.event.EventListener"
  const val TRANSACTIONAL_EVENT_LISTENER: String = "org.springframework.transaction.event.TransactionalEventListener"

  /** Exactly one of these kinds of trigger; the `…String` forms take a placeholder. */
  val TRIGGERS: List<Pair<String, List<String>>> = listOf(
    "cron" to listOf("cron"),
    "fixedDelay" to listOf("fixedDelay", "fixedDelayString"),
    "fixedRate" to listOf("fixedRate", "fixedRateString"),
  )
  val INITIAL_DELAY: List<String> = listOf("initialDelay", "initialDelayString")

  val CRON_MACROS: Set<String> = setOf("@yearly", "@annually", "@monthly", "@weekly", "@daily", "@midnight", "@hourly")

  private val CRON_FIELD = Regex("""[0-9A-Za-z*?/,\-#]+""")

  /** Why a literal cron expression cannot parse, or null. Placeholders and the disabled marker `-` are not checked. */
  fun cronProblem(expression: String): String? {
    val cron = expression.trim()
    if (cron.isEmpty() || cron == "-" || cron.contains("\${") || cron.contains("#{")) return null
    if (cron.startsWith("@")) return if (cron.lowercase() in CRON_MACROS) null else "unknown macro '$cron'"
    val fields = cron.split(Regex("\\s+"))
    if (fields.size != 6) return "${fields.size} fields; Spring cron has 6: second minute hour day-of-month month day-of-week"
    fields.firstOrNull { !CRON_FIELD.matches(it) }?.let { return "'$it' is not a cron field" }
    return null
  }

  /** Return types `@Async` can hand back to the caller; anything else reaches the caller as null. */
  val ASYNC_RESULTS: List<String> = listOf("java.util.concurrent.Future", "org.springframework.util.concurrent.ListenableFuture")

  fun isAsyncResultType(type: PsiType?): Boolean {
    if (type == null || type == PsiTypes.voidType() || type.equalsToText("kotlin.Unit")) return true
    val psiClass = PsiUtil.resolveClassInClassTypeOnly(type) ?: return true // unresolved: do not guess
    return ASYNC_RESULTS.any { com.intellij.psi.util.InheritanceUtil.isInheritor(psiClass, false, it) || psiClass.qualifiedName == it }
  }

  fun attribute(annotation: UAnnotation, names: List<String>): UExpression? = names.firstNotNullOfOrNull { annotation.findDeclaredAttributeValue(it) }
}

/** `@Scheduled` needs a method without parameters and exactly one trigger, and a cron expression Spring can parse. */
class SpringScheduledInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        for (annotation in node.uAnnotations) {
          if (annotation.qualifiedName != SpringMethodContracts.SCHEDULED) continue
          check(node, annotation)
        }
        return true
      }

      private fun check(node: UMethod, annotation: UAnnotation) {
        val anchor = annotation.sourcePsi ?: return
        if (node.uastParameters.isNotEmpty()) {
          holder.registerProblem(node.uastAnchor?.sourcePsi ?: anchor, PhantomSpringBundle.message("inspection.scheduled.parameters"))
        }
        val triggers = SpringMethodContracts.TRIGGERS.filter { (_, names) -> SpringMethodContracts.attribute(annotation, names) != null }.map { it.first }
        val oneShot = SpringMethodContracts.attribute(annotation, SpringMethodContracts.INITIAL_DELAY) != null
        when {
          triggers.size > 1 -> holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.scheduled.several", triggers.joinToString(", ")))
          // Spring 6.1 runs a task once when only an initial delay is given
          triggers.isEmpty() && !oneShot -> holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.scheduled.none"))
        }
        val cron = annotation.findDeclaredAttributeValue("cron") ?: return
        val text = cron.evaluateString() ?: return
        val problem = SpringMethodContracts.cronProblem(text) ?: return
        holder.registerProblem(cron.sourcePsi ?: anchor, PhantomSpringBundle.message("inspection.scheduled.cron", problem))
      }
    }, arrayOf(UMethod::class.java), true)
  }
}

/** `@Async` methods return void, `Future` or `CompletableFuture`; any other result never reaches the caller. */
class SpringAsyncInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        val method = node.javaPsi
        if (method.isConstructor || !method.hasAnnotation(SpringProxies.ASYNC)) return true
        // a Kotlin suspend function already returns through its continuation
        if (method.parameterList.parameters.lastOrNull()?.type?.canonicalText?.startsWith("kotlin.coroutines.Continuation") == true) return true
        if (SpringMethodContracts.isAsyncResultType(method.returnType)) return true
        val anchor = node.returnTypeReference?.sourcePsi ?: node.uastAnchor?.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.async.return", method.returnType?.presentableText ?: "?"))
        return true
      }
    }, arrayOf(UMethod::class.java), true)
  }
}

/** An event listener method receives one event; with none it needs the event classes in the annotation. */
class SpringEventListenerInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        val method = node.javaPsi
        val annotation = SpringEvents.listenerAnnotation(node) ?: return true
        val count = node.uastParameters.size
        val classes = annotation.findDeclaredAttributeValue("classes") ?: annotation.findDeclaredAttributeValue("value")
        val anchor = node.uastAnchor?.sourcePsi ?: return true
        when {
          count > 1 -> holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.event.listener.parameters", count))
          count == 0 && (classes == null || SpringEvents.isEmptyArray(classes)) ->
            holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.event.listener.no.event"))
        }
        return true
      }
    }, arrayOf(UMethod::class.java), true)
  }
}

/** Event listeners and the types they listen to. */
object SpringEvents {
  /** `@EventListener`, `@TransactionalEventListener` and annotations built on them (`@ApplicationModuleListener`). */
  fun listenerAnnotation(method: PsiMethod): PsiAnnotation? {
    for (annotation in method.annotations) {
      val fqn = annotation.qualifiedName ?: continue
      if (fqn == SpringMethodContracts.EVENT_LISTENER || fqn == SpringMethodContracts.TRANSACTIONAL_EVENT_LISTENER) return annotation
      if (fqn.startsWith("java.") || fqn.startsWith("kotlin.")) continue
      val type = annotation.resolveAnnotationType() ?: continue
      if (MetaAnnotationUtil.isMetaAnnotated(type, listOf(SpringMethodContracts.EVENT_LISTENER))) return annotation
    }
    return null
  }

  fun listenerAnnotation(method: UMethod): UAnnotation? {
    val psi = listenerAnnotation(method.javaPsi) ?: return null
    return method.uAnnotations.firstOrNull { it.qualifiedName == psi.qualifiedName }
  }

  fun isEmptyArray(value: UExpression): Boolean = value is UCallExpression && value.kind == UastCallKind.NESTED_ARRAY_INITIALIZER && value.valueArgumentCount == 0

  /** The event types [method] receives: its parameter, or the classes named in the annotation. */
  fun eventTypes(method: PsiMethod): List<PsiType> {
    val annotation = listenerAnnotation(method) ?: return emptyList()
    val named = listOf("classes", "value").flatMap { attribute ->
      com.intellij.codeInsight.AnnotationUtil.arrayAttributeValues(annotation.findDeclaredAttributeValue(attribute))
        .mapNotNull { (it as? PsiClassObjectAccessExpression)?.operand?.type }
    }
    if (named.isNotEmpty()) return named
    return listOfNotNull(method.parameterList.parameters.singleOrNull()?.type)
  }
}
