// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.spring

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import dev.phantom.graphql.GraphQLBundle
import dev.phantom.graphql.psi.GraphQLFieldDefinition

/**
 * A Query, Mutation or Subscription field that no Spring GraphQL handler answers fails at runtime with a null or an
 * error. Fields of other types are not reported: Spring GraphQL resolves them from the source object's properties.
 */
class GraphQLUnmappedFieldInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    if (!SpringGraphQLMappings.isAvailable(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return object : PsiElementVisitor() {
      override fun visitElement(element: PsiElement) {
        val field = element as? GraphQLFieldDefinition ?: return
        val type = field.ownerTypeName?.takeIf { it in ROOT_TYPES } ?: return
        val name = field.name ?: return
        if (SpringGraphQLMappings.handlers(holder.project)[SchemaCoordinate(type, name)].isNullOrEmpty()) {
          holder.registerProblem(field.nameIdentifier ?: return, GraphQLBundle.message("inspection.unmapped.field", "$type.$name"))
        }
      }
    }
  }

  private companion object {
    val ROOT_TYPES = setOf("Query", "Mutation", "Subscription")
  }
}
