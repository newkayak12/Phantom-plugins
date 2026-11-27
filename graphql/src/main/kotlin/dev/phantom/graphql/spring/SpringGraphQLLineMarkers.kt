// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.spring

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import dev.phantom.graphql.GraphQLBundle
import dev.phantom.graphql.lang.GraphQLElementTypes
import dev.phantom.graphql.psi.GraphQLFieldDefinition
import dev.phantom.graphql.psi.GraphQLSchemaSearch

/** On a schema field: go to the Spring GraphQL handler methods that answer it. */
class SchemaFieldHandlerLineMarkerProvider : RelatedItemLineMarkerProvider() {
  override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
    // Markers go on leaves: the name token of a field definition
    val identifier = element.parent ?: return
    if (element.firstChild != null || identifier.node.elementType != GraphQLElementTypes.IDENTIFIER) return
    val field = identifier.parent as? GraphQLFieldDefinition ?: return
    if (!SpringGraphQLMappings.isAvailable(element.project)) return
    val type = field.ownerTypeName ?: return
    val methods = SpringGraphQLMappings.handlers(element.project)[SchemaCoordinate(type, field.name ?: return)].orEmpty()
    if (methods.isEmpty()) return
    result += NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
      .setTargets(methods)
      .setTooltipText(GraphQLBundle.message("marker.handler"))
      .createLineMarkerInfo(element)
  }
}

/** On a Spring GraphQL handler method (Java): go to the schema fields it answers. */
class HandlerSchemaFieldLineMarkerProvider : RelatedItemLineMarkerProvider() {
  override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
    if (element !is PsiIdentifier) return
    val method = element.parent as? PsiMethod ?: return
    if (method.nameIdentifier != element || SpringGraphQLMappings.ANNOTATIONS.none { method.hasAnnotation(it) }) return
    val fields = SpringGraphQLMappings.coordinates(method).flatMap { GraphQLSchemaSearch.fieldDefinitions(element.project, it.typeName, it.fieldName) }
    if (fields.isEmpty()) return
    result += NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementingMethod)
      .setTargets(fields)
      .setTooltipText(GraphQLBundle.message("marker.schema.field"))
      .createLineMarkerInfo(element)
  }
}
