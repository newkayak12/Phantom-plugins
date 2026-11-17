// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.properties

import dev.phantom.springboot.core.BootConfigCompletionContributor
import dev.phantom.springboot.core.BootConfigDocumentationProvider
import dev.phantom.springboot.core.PropertiesConfigSyntax

class BootPropertiesCompletionContributor : BootConfigCompletionContributor(PropertiesConfigSyntax, yaml = false)

class BootPropertiesDocumentationProvider : BootConfigDocumentationProvider(PropertiesConfigSyntax)
