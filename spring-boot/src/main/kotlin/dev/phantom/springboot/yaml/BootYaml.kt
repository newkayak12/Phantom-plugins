// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.yaml

import dev.phantom.springboot.core.BootConfigCompletionContributor
import dev.phantom.springboot.core.BootConfigDocumentationProvider
import dev.phantom.springboot.core.YamlConfigSyntax

class BootYamlCompletionContributor : BootConfigCompletionContributor(YamlConfigSyntax, yaml = true)

class BootYamlDocumentationProvider : BootConfigDocumentationProvider(YamlConfigSyntax)
