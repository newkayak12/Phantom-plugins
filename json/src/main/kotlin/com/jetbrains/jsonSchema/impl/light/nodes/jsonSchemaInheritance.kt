// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.jetbrains.jsonSchema.impl.light.nodes

import com.intellij.util.asSafely
import com.jetbrains.jsonSchema.impl.JsonSchemaObject

// todo: avoid casting after JsonSchemaObject is made an interface
internal fun inheritBaseSchemaIfNeeded(parent: JsonSchemaObject, child: JsonSchemaObject): JsonSchemaObject {
  val inheritedSchema = child.asSafely<JsonSchemaObjectBackedByJacksonBase>()
    ?.rootSchemaObject
    ?.schemaInterpretationStrategy
    ?.inheritBaseSchema(parent, child)
  if (inheritedSchema != null) {
  }
  return inheritedSchema ?: child
}