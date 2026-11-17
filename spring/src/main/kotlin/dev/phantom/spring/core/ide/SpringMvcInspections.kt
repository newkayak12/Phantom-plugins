// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.InheritanceUtil
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.SpringMvcEndpoints
import dev.phantom.spring.core.SpringProjectGate
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.getUParentForIdentifier
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor
import javax.swing.Icon

/** Path template variables: `{id}`, `{id:[0-9]+}`, `{*rest}`; `${property}` placeholders are not variables. */
object SpringPathVariables {
  const val PATH_VARIABLE: String = "org.springframework.web.bind.annotation.PathVariable"
  private val VARIABLE = Regex("""\{\*?([A-Za-z_][\w.-]*)}""")
  private val PLACEHOLDER = Regex("""\$\{[^}]*}""")

  fun variables(path: String): List<String> =
    VARIABLE.findAll(SpringMvcEndpoints.readablePath(PLACEHOLDER.replace(path, ""))).map { it.groupValues[1] }.toList()

  /** The name a `@PathVariable` parameter binds: `value`, `name`, or the parameter name. */
  fun boundName(parameter: UParameter): String? {
    val annotation = parameter.findAnnotation(PATH_VARIABLE) ?: return null
    val given = sequenceOf("value", "name").mapNotNull { annotation.findDeclaredAttributeValue(it)?.evaluateString() }.firstOrNull { it.isNotEmpty() }
    return given ?: parameter.name
  }
}

/**
 * A `@PathVariable` parameter whose name is in none of the handler's paths fails every request with a 500; a path
 * variable no parameter binds is usually a typo. The second check stays quiet when a `Map` takes all the variables
 * or a command object may bind them by property name.
 */
class SpringMvcPathVariableInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        if (SpringMvcEndpoints.MAPPING_ANNOTATIONS.none { node.findAnnotation(it) != null }) return true
        val paths = SpringMvcEndpoints.endpointsOf(node.javaPsi).map { it.path }.distinct().ifEmpty { return true }
        val variables = paths.flatMap(SpringPathVariables::variables).toSet()
        val bound = HashMap<String, UParameter>()
        var takesAll = false
        for (parameter in node.uastParameters) {
          val name = SpringPathVariables.boundName(parameter) ?: continue
          val annotation = parameter.findAnnotation(SpringPathVariables.PATH_VARIABLE)
          val explicit = sequenceOf("value", "name").any { annotation?.findDeclaredAttributeValue(it)?.evaluateString()?.isNotEmpty() == true }
          if (!explicit && InheritanceUtil.isInheritor(parameter.type, java.util.Map::class.java.name)) { takesAll = true; continue }
          bound[name] = parameter
          if (name !in variables) {
            val anchor = annotation?.sourcePsi ?: parameter.uastAnchor?.sourcePsi ?: continue
            holder.registerProblem(anchor, PhantomSpringBundle.message("mvc.path.variable.unknown", name, paths.joinToString(", ")))
          }
        }
        if (takesAll || node.uastParameters.any(::mayBindByProperty)) return true
        for (variable in variables - bound.keys) {
          val (element, range) = variableLocation(node, variable) ?: (node.uastAnchor?.sourcePsi to null)
          element ?: continue
          val message = PhantomSpringBundle.message("mvc.path.variable.unbound", variable)
          if (range != null) holder.registerProblem(element, message, ProblemHighlightType.WEAK_WARNING, range)
          else holder.registerProblem(element, message, ProblemHighlightType.WEAK_WARNING)
        }
        return true
      }
    }, arrayOf(UMethod::class.java), true)
  }

  /** The variable inside the method's own mapping string, or null when it comes from the class mapping. */
  private fun variableLocation(method: UMethod, variable: String): Pair<PsiElement, TextRange>? {
    val annotation = SpringMvcEndpoints.MAPPING_ANNOTATIONS.firstNotNullOfOrNull { method.findAnnotation(it) } ?: return null
    for (name in listOf("value", "path")) {
      val value = annotation.findDeclaredAttributeValue(name) ?: continue
      val hosts = ArrayList<UInjectionHost>()
      value.accept(object : org.jetbrains.uast.visitor.AbstractUastVisitor() {
        override fun visitElement(node: org.jetbrains.uast.UElement): Boolean {
          if (node is UInjectionHost) hosts += node
          return false
        }
      })
      for (host in hosts) {
        val psi = host.psiLanguageInjectionHost
        val valueRange = ElementManipulators.getValueTextRange(psi)
        val text = valueRange.substring(psi.text)
        val start = Regex("""\{\*?${Regex.escape(variable)}[:}]""").find(text)?.range?.first ?: continue
        return psi to TextRange(valueRange.startOffset + start, valueRange.startOffset + start + variable.length + 2)
      }
    }
    return null
  }

  /** A parameter without web annotations whose type is a project class: Spring binds path variables to its properties. */
  private fun mayBindByProperty(parameter: UParameter): Boolean {
    if (parameter.uAnnotations.any { it.qualifiedName == "org.springframework.web.bind.annotation.ModelAttribute" }) return true
    if (parameter.uAnnotations.any { it.qualifiedName?.startsWith("org.springframework.web.bind.annotation.") == true }) return false
    val psiClass = (parameter.type as? PsiClassType)?.resolve() ?: return false
    val file = psiClass.containingFile?.virtualFile ?: return false
    return ProjectFileIndex.getInstance(psiClass.project).isInSourceContent(file)
  }
}

/** A gutter icon on each handler method with its HTTP methods and full paths; a click opens the Endpoints tool window. */
class SpringEndpointLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBundle.message("mvc.endpoint.line.marker.name")

  override fun getIcon(): Icon = AllIcons.Webreferences.Server

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null) return null
    if ((element.parent as? PsiNameIdentifierOwner)?.nameIdentifier != element) return null
    if (!SpringProjectGate.isSpringProject(element.project)) return null
    val method = getUParentForIdentifier(element) as? UMethod ?: return null
    if (SpringMvcEndpoints.MAPPING_ANNOTATIONS.none { method.findAnnotation(it) != null }) return null
    val endpoints = SpringMvcEndpoints.endpointsOf(method.javaPsi).ifEmpty { return null }
    val text = endpoints.joinToString("\n") { "${it.methods.ifEmpty { listOf("ANY") }.joinToString("|")} ${SpringMvcEndpoints.readablePath(it.path)}" }
    val handler = GutterIconNavigationHandler<PsiElement> { _, psi ->
      ToolWindowManager.getInstance(psi.project).getToolWindow("Endpoints")?.activate(null)
    }
    return LineMarkerInfo(element, element.textRange, AllIcons.Webreferences.Server,
                          { PhantomSpringBundle.message("mvc.endpoint.tooltip", text) }, handler, GutterIconRenderer.Alignment.LEFT,
                          { PhantomSpringBundle.message("mvc.endpoint.line.marker.name") })
  }
}
