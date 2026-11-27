// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.phantom.graphql.lang.GraphQLFileType

/** Looks definitions up across the project's .graphql/.graphqls files; each file's definitions are cached on it. */
object GraphQLSchemaSearch {
  fun files(project: Project): List<GraphQLFile> {
    val psiManager = PsiManager.getInstance(project)
    return FileTypeIndex.getFiles(GraphQLFileType, GlobalSearchScope.projectScope(project)).mapNotNull { psiManager.findFile(it) as? GraphQLFile }
  }

  fun typeDefinitions(project: Project, name: String): List<GraphQLTypeDefinition> =
    files(project).flatMap { file -> file.typeDefinitions.filter { it.name == name } }

  fun fragmentDefinitions(project: Project, name: String): List<GraphQLFragmentDefinition> =
    files(project).flatMap { file -> file.fragmentDefinitions.filter { it.name == name } }

  fun fieldDefinitions(project: Project, typeName: String, fieldName: String): List<GraphQLFieldDefinition> =
    files(project).flatMap { file -> file.fieldDefinitions.filter { it.name == fieldName && it.ownerTypeName == typeName } }
}
