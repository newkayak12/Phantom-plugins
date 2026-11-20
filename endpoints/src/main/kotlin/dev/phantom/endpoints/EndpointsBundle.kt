// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.endpoints

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.EndpointsBundle"

internal object EndpointsBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
