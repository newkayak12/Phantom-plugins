// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ignore.templates

/** The templates bundled under ignoreTemplates/, adapted from github/gitignore (CC0). */
object IgnoreTemplates {
  val NAMES: List<String> = listOf(
    "Java", "Kotlin", "Gradle", "Maven", "IntelliJ", "VisualStudioCode", "macOS", "Windows", "Linux",
    "Node", "Python", "Go", "Rust", "Docker",
  )

  /** The ignore files the action can write; .gitignore first. */
  val FILE_NAMES: List<String> = listOf(
    ".gitignore", ".dockerignore", ".npmignore", ".helmignore", ".prettierignore", ".eslintignore", ".stylelintignore",
    ".gcloudignore", ".vscodeignore",
  )

  fun load(name: String): String =
    IgnoreTemplates::class.java.getResourceAsStream("/ignoreTemplates/$name.gitignore")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

  /**
   * [existing] with each template appended under a `### name ###` header, leaving out the lines [existing] (or an earlier
   * template) already has and the templates left with nothing new.
   */
  fun merge(existing: String, templates: List<Pair<String, String>>): String {
    val seen = existing.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
    val out = StringBuilder(existing)
    for ((name, content) in templates) {
      val fresh = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && seen.add(it) }.toList()
      if (fresh.isEmpty()) continue
      if (out.isNotEmpty()) {
        if (!out.endsWith("\n")) out.append('\n')
        out.append('\n')
      }
      out.append("### ").append(name).append(" ###\n")
      fresh.forEach { out.append(it).append('\n') }
    }
    return out.toString()
  }
}
