// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.lang

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import dev.phantom.graphql.GraphQLBundle
import javax.swing.Icon

object GraphQLLanguage : Language("GraphQL") {
  private fun readResolve(): Any = GraphQLLanguage

  override fun getDisplayName(): String = "GraphQL"
}

object GraphQLFileType : LanguageFileType(GraphQLLanguage) {
  private val icon = IconLoader.getIcon("/icons/graphql.svg", GraphQLFileType::class.java)

  override fun getName(): String = "GraphQL"

  override fun getDescription(): String = GraphQLBundle.message("filetype.description")

  override fun getDefaultExtension(): String = "graphql"

  override fun getIcon(): Icon = icon
}
