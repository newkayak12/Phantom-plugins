// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.android.tools.idea.gradle.dsl.parser.elements

class GradleDslDelegatedProperty(
  parent: GradleDslElement?,
  name: GradleNameElement,
) : GradlePropertiesDslElement(parent, null, name)