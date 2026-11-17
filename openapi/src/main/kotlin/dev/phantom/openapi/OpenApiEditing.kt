// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.openapi

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.icons.AllIcons
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.microservices.endpoints.EndpointsFilter
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.presentation.EndpointMethodPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/** The string value of a `$ref` key, in YAML or JSON. */
internal fun isRefValue(element: PsiElement): Boolean = when (element) {
  is YAMLScalar -> (element.parent as? YAMLKeyValue)?.let { it.keyText == "\$ref" && it.value == element } == true
  is JsonStringLiteral -> (element.parent as? JsonProperty)?.let { it.name == "\$ref" && it.value == element } == true
  else -> false
}

/**
 * A `$ref`: `#/components/schemas/Pet`, `common.yaml#/components/schemas/Error` or `schemas/pet.yaml`. The file part is
 * relative to the referring file. Remote references are soft: they are neither resolved nor reported.
 */
class OpenApiReference(element: PsiElement, range: TextRange, private val value: String) : PsiReferenceBase<PsiElement>(element, range) {
  private val remote = value.startsWith("http://") || value.startsWith("https://")

  override fun isSoft(): Boolean = remote

  override fun resolve(): PsiElement? {
    if (remote) return null
    val filePart = value.substringBefore('#')
    val pointer = if ('#' in value) value.substringAfter('#') else ""
    val here = element.containingFile.originalFile
    val target = if (filePart.isEmpty()) here else {
      val vFile = here.virtualFile?.parent?.findFileByRelativePath(filePart) ?: return null
      PsiManager.getInstance(element.project).findFile(vFile) ?: return null
    }
    return OpenApiSpecs.resolvePointer(target, pointer)
  }

  override fun getVariants(): Array<Any> =
    OpenApiSpecs.componentPointers(element.containingFile.originalFile).map { LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.Field) }.toTypedArray()
}

class OpenApiReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    val provider = object : PsiReferenceProvider() {
      override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        if (!isRefValue(element) || !OpenApiSpecs.isSpec(element.containingFile.originalFile)) return PsiReference.EMPTY_ARRAY
        val range = ElementManipulators.getValueTextRange(element)
        val value = range.substring(element.text)
        return arrayOf(OpenApiReference(element, range, value))
      }
    }
    registrar.registerReferenceProvider(PlatformPatterns.psiElement(YAMLScalar::class.java), provider)
    registrar.registerReferenceProvider(PlatformPatterns.psiElement(JsonStringLiteral::class.java), provider)
  }
}

/** `$ref` values of a spec that resolve to nothing. */
class OpenApiRefInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    if (!OpenApiSpecs.isSpec(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    return object : PsiElementVisitor() {
      override fun visitElement(element: PsiElement) {
        if (!isRefValue(element)) return
        for (reference in element.references.filterIsInstance<OpenApiReference>()) {
          if (reference.isSoft || reference.resolve() != null) continue
          holder.registerProblem(element, reference.rangeInElement, PhantomOpenApiBundle.message("ref.unresolved", reference.canonicalText))
        }
      }
    }
  }
}

/** The endpoints Phantom's Endpoints view knows (Spring MVC and others), with the element that serves them. */
internal object CodeEndpoints {
  class Endpoint(val path: String, val methods: List<String>, val element: PsiElement)

  private object Everything : EndpointsFilter

  fun all(project: Project): List<Endpoint> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val result = ArrayList<Endpoint>()
    for (provider in EndpointsProvider.getAvailableProviders(project)) {
      @Suppress("UNCHECKED_CAST")
      val typed = provider as EndpointsProvider<Any, Any>
      for (group in typed.getEndpointGroups(project, Everything)) {
        for (endpoint in typed.getEndpoints(group)) {
          if (!typed.isValidEndpoint(group, endpoint)) continue
          val presentation = typed.getEndpointPresentation(group, endpoint)
          val path = presentation.presentableText ?: continue
          val element = typed.getNavigationElement(group, endpoint) ?: continue
          result += Endpoint(path, (presentation as? EndpointMethodPresentation)?.endpointMethods.orEmpty().map { it.uppercase() }, element)
        }
      }
    }
    CachedValueProvider.Result.create(result.toList(), PsiModificationTracker.getInstance(project))
  }

  /** An empty method list accepts every method (`@RequestMapping` without `method`). */
  fun serving(project: Project, operation: SpecOperation): List<PsiElement> =
    all(project).filter { OpenApiSpecs.pathsMatch(operation.path, it.path) && (it.methods.isEmpty() || operation.method in it.methods) }.map { it.element }

  fun same(a: PsiElement, b: PsiElement): Boolean = a == b || a.navigationElement == b || a == b.navigationElement || a.navigationElement == b.navigationElement
}

/** On each operation of a spec (`get:` under a path), a gutter link to the code that serves it. */
class SpecOperationLineMarkerProvider : LineMarkerProvider {
  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

  override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
    val file = elements.firstOrNull()?.containingFile ?: return
    if (!OpenApiSpecs.isSpec(file)) return
    val operations = OpenApiSpecs.operations(file).associateBy { PsiTreeUtil.getDeepestFirst(it.element) }
    for (element in elements) {
      val operation = operations[element] ?: continue
      val targets = CodeEndpoints.serving(element.project, operation)
      if (targets.isEmpty()) continue
      result += NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
        .setTargets(targets)
        .setTooltipText(PhantomOpenApiBundle.message("marker.to.code", operation.method, operation.path))
        .setPopupTitle(PhantomOpenApiBundle.message("marker.to.code.title", operation.method, operation.path))
        .createLineMarkerInfo(element)
    }
  }
}

/** On a handler method that a spec documents, a gutter link to the spec operation. */
class EndpointSpecLineMarkerProvider : LineMarkerProvider {
  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

  override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
    val project = elements.firstOrNull()?.project ?: return
    val specs = OpenApiSpecs.specFiles(project)
    if (specs.isEmpty()) return
    val endpoints = CodeEndpoints.all(project)
    if (endpoints.isEmpty()) return
    for (element in elements) {
      if (element.firstChild != null) continue
      val owner = element.parent as? PsiNameIdentifierOwner ?: continue
      if (owner.nameIdentifier != element) continue
      val mine = endpoints.filter { CodeEndpoints.same(it.element, owner) }
      if (mine.isEmpty()) continue
      val operations = specs.flatMap { OpenApiSpecs.operations(it) }.filter { op ->
        mine.any { OpenApiSpecs.pathsMatch(op.path, it.path) && (it.methods.isEmpty() || op.method in it.methods) }
      }
      if (operations.isEmpty()) continue
      val first = operations.first()
      result += NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementingMethod)
        .setTargets(operations.map { it.element })
        .setTooltipText(PhantomOpenApiBundle.message("marker.to.spec", first.method, first.path, first.element.containingFile.name))
        .setPopupTitle(PhantomOpenApiBundle.message("marker.to.spec.title"))
        .createLineMarkerInfo(element)
    }
  }
}
