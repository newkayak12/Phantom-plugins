// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.jetbrains.jsonSchema.impl.validations

import com.intellij.json.JsonBundle
import com.jetbrains.jsonSchema.extension.JsonErrorPriority
import com.jetbrains.jsonSchema.extension.JsonSchemaValidation
import com.jetbrains.jsonSchema.extension.JsonValidationHost
import com.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter
import com.jetbrains.jsonSchema.impl.JsonComplianceCheckerOptions
import com.jetbrains.jsonSchema.impl.JsonSchemaObject
import com.jetbrains.jsonSchema.impl.JsonSchemaType

internal object ConstantSchemaValidation : JsonSchemaValidation {
  override fun validate(propValue: JsonValueAdapter, schema: JsonSchemaObject, schemaType: JsonSchemaType?, consumer: JsonValidationHost, options: JsonComplianceCheckerOptions): Boolean {
    if (schema.constantSchema == false) {
      consumer.error(JsonBundle.message("schema.validation.constant.schema"), propValue.delegate.parent, JsonErrorPriority.LOW_PRIORITY)
      return false
    }
    return true
  }
}