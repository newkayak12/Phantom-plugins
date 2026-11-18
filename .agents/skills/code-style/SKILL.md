---
name: code-style
description: Code style rules for IntelliJ codebase. Use when writing or reviewing code for style compliance.
---

# Code Style

Follow the IntelliJ Coding Guidelines with these IntelliJ-specific rules.

## Language

- **ALWAYS use Kotlin for new files** - Only use Java when modifying existing Java code
- Use idiomatic Kotlin: extension functions, data classes, null safety
- Mark experimental APIs with `@ApiStatus.Experimental`

## Formatting

- Indentation: 2 spaces (4 for Go files)
- Line length: 140 characters max
- Braces: else/catch/finally on new lines
- Only in `/rustrover` directory you must always use standard Kotlin formatting rules with 4 spaces indentation.
