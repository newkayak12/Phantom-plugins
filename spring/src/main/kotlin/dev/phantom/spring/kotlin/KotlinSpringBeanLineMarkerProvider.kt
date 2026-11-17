// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.kotlin

import dev.phantom.spring.core.ide.SpringBeanLineMarkerProvider

/** Same UAST-based logic as Java; registered separately so it loads only when the Kotlin plugin is present. */
class KotlinSpringBeanLineMarkerProvider : SpringBeanLineMarkerProvider()
