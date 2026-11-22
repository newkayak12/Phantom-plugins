// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot

import com.intellij.openapi.module.Module
import dev.phantom.springboot.core.ConfigMetadataService
import org.junit.Assert.assertEquals

/** The layered keys of [module] (libraries, build output, source) equal one flat merge of every source, field by field. */
fun assertLayeredKeysMatchFlat(module: Module) {
  val service = ConfigMetadataService.getInstance(module.project)
  assertEquals(service.flatKeysFor(module).describe(), service.keysFor(module).describe())
}
