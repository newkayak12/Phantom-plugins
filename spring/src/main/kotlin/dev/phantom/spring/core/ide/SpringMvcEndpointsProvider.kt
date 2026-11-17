// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.microservices.endpoints.EndpointsFilter
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.FrameworkPresentation
import com.intellij.microservices.endpoints.HTTP_SERVER_TYPE
import com.intellij.microservices.endpoints.ModuleEndpointsFilter
import com.intellij.microservices.endpoints.SearchScopeEndpointsFilter
import com.intellij.microservices.endpoints.presentation.HttpMethodPresentation
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiModificationTracker
import dev.phantom.spring.core.PhantomSpringIcons
import dev.phantom.spring.core.SpringEndpoint
import dev.phantom.spring.core.SpringMvcEndpoints
import dev.phantom.spring.core.SpringProjectGate

/** The controllers of one module (or of no module, for sources outside every module). */
class SpringEndpointsGroup(val module: Module?, val endpoints: List<SpringEndpoint>)

/** Spring MVC handlers for the platform's endpoints API, which the Endpoints tool window reads. */
internal class SpringMvcEndpointsProvider : EndpointsProvider<SpringEndpointsGroup, SpringEndpoint> {
  override val endpointType = HTTP_SERVER_TYPE

  override val presentation = FrameworkPresentation("Spring-MVC", "Spring MVC", PhantomSpringIcons.Bean)

  override fun getStatus(project: Project): EndpointsProvider.Status = when {
    !SpringProjectGate.isSpringProject(project) -> EndpointsProvider.Status.UNAVAILABLE
    DumbService.isDumb(project) || SpringMvcEndpoints.all(project).isEmpty() -> EndpointsProvider.Status.AVAILABLE
    else -> EndpointsProvider.Status.HAS_ENDPOINTS
  }

  override fun getEndpointGroups(project: Project, filter: EndpointsFilter): Iterable<SpringEndpointsGroup> {
    if (DumbService.isDumb(project)) return emptyList()
    val endpoints = SpringMvcEndpoints.all(project).filter { accepts(filter, it) }
    return endpoints.groupBy { it.module }.map { (module, list) -> SpringEndpointsGroup(module, list) }
  }

  private fun accepts(filter: EndpointsFilter, endpoint: SpringEndpoint): Boolean = when (filter) {
    is ModuleEndpointsFilter -> endpoint.module == filter.module
    is SearchScopeEndpointsFilter -> endpoint.handler.virtualFile?.let { filter.contentSearchScope.contains(it) } ?: false
    else -> true
  }

  override fun getEndpoints(group: SpringEndpointsGroup): Iterable<SpringEndpoint> = group.endpoints

  override fun isValidEndpoint(group: SpringEndpointsGroup, endpoint: SpringEndpoint): Boolean = endpoint.handler.element?.isValid == true

  override fun getEndpointPresentation(group: SpringEndpointsGroup, endpoint: SpringEndpoint): ItemPresentation =
    HttpMethodPresentation(SpringMvcEndpoints.readablePath(endpoint.path), endpoint.methods, "${endpoint.controllerName}.${endpoint.handlerName}", PhantomSpringIcons.Bean)

  override fun getModificationTracker(project: Project): ModificationTracker = PsiModificationTracker.getInstance(project)

  override fun getNavigationElement(group: SpringEndpointsGroup, endpoint: SpringEndpoint): PsiElement? = endpoint.handler.element

  override fun getDocumentationElement(group: SpringEndpointsGroup, endpoint: SpringEndpoint): PsiElement? = endpoint.handler.element
}
