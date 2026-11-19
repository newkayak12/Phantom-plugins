// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.theme

/** The registered themes. Adding one is a new [Theme], its resources and one line here. */
object Themes {
  val all: List<Theme> = listOf(ThemeA, ThemeB, ThemeC, ThemeD, ThemeE, ThemeF)
  val default: Theme get() = all.first()

  /** The theme with [id]; the default when it is unknown (a setting left by a theme that no longer exists). */
  fun byId(id: String?): Theme = all.firstOrNull { it.id == id } ?: default
}
