// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terminal.completion.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The JSON form of a command spec, as read by ShellJsonCommandSpecsProvider and ShellCommandSpecsManagerImpl from
 * completionSpecs/all_commands.json and completionSpecs/<command>.json. Every field but the names is optional; the
 * defaults are those of the Kotlin spec DSL. terminal/tools/fig-specs writes the bundled files in this form.
 */

/**
 * A command or subcommand. In all_commands.json a command has only its names, description and [loadSpec] (a light spec);
 * the full spec is the file [loadSpec] names. A subcommand with [loadSpec] is loaded from that file when needed.
 */
@Serializable
data class ShellCommand(
  val names: List<String>,
  val displayName: String? = null,
  val description: String? = null,
  val insertValue: String? = null,
  val priority: Int = 50,
  val isHidden: Boolean = false,
  val requiresSubcommand: Boolean = false,
  val parserDirectives: ShellCommandParserDirectives = ShellCommandParserDirectives(),
  /** The path of the full spec under the specs folder, without `.json`: `git`, or `aws/s3` for `aws/s3.json` */
  val loadSpec: String? = null,
  val subcommands: List<ShellCommand> = emptyList(),
  val options: List<ShellOption> = emptyList(),
  val args: List<ShellArgument> = emptyList(),
)

@Serializable
data class ShellCommandParserDirectives(
  val flagsArePosixNoncompliant: Boolean = false,
  val optionsMustPrecedeArguments: Boolean = false,
  val optionArgSeparators: List<String> = emptyList(),
)

@Serializable
data class ShellOption(
  val names: List<String>,
  val displayName: String? = null,
  val description: String? = null,
  val insertValue: String? = null,
  val priority: Int = 50,
  val isHidden: Boolean = false,
  val isRequired: Boolean = false,
  val isPersistent: Boolean = false,
  /** Between the option and its argument (`=` for `--opt=value`); whitespace when null */
  val separator: String? = null,
  /** How many times the option may be given; 0 for any number */
  val repeatTimes: Int = 1,
  val exclusiveOn: List<String> = emptyList(),
  val dependsOn: List<String> = emptyList(),
  val args: List<ShellArgument> = emptyList(),
)

@Serializable
data class ShellArgument(
  @SerialName("name") val displayName: String? = null,
  val isOptional: Boolean = false,
  val isVariadic: Boolean = false,
  val optionsCanBreakVariadicArg: Boolean = true,
  /** The argument is a command name: available commands are suggested */
  val isCommand: Boolean = false,
  val suggestions: List<ShellSuggestion> = emptyList(),
  /** `filepaths` suggests files and folders, `folders` only folders */
  val templates: List<String> = emptyList(),
  val generators: List<ShellArgumentGenerator> = emptyList(),
)

/** A generator is kept only for its [templates]; generators running scripts can't be described in JSON */
@Serializable
data class ShellArgumentGenerator(
  val templates: List<String> = emptyList(),
)

@Serializable
data class ShellSuggestion(
  val names: List<String>,
  val displayName: String? = null,
  val description: String? = null,
  val insertValue: String? = null,
  val priority: Int = 50,
)
