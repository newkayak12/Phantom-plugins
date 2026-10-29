// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.hooks

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Paths of the generated POSIX sh scripts. */
data class ScriptPaths(val claudeHook: Path, val codexHook: Path, val claudeStatusline: Path)

/**
 * POSIX sh scripts the agents run. They hold no secret: port, token and tab id come from the env of the terminal the
 * agent was launched in ([HookEnv]); without it they do nothing (an agent started elsewhere is not affected).
 * In the templates the section sign stands for a dollar sign, so Kotlin does not interpolate the shell variables.
 */
object HookScripts {
  const val DIR_NAME = "phantom-ai"

  fun defaultDir(): Path = PathManager.getSystemDir().resolve(DIR_NAME)

  private fun sh(template: String) = template.trimStart().replace('§', '$')
    .replace("@PORT", HookEnv.PORT).replace("@TOKEN_HEADER", HookEnv.TOKEN_HEADER)
    .replace("@TOKEN", HookEnv.TOKEN).replace("@TAB", HookEnv.TAB_ID)

  private const val POST = """printf '%s' "§payload" | LC_ALL= LC_NUMERIC=C curl -sS -X POST --noproxy 127.0.0.1 --connect-timeout 0.5 --max-time 1.5 \
    "http://127.0.0.1:§{@PORT}@PATH" \
    -H "Content-Type: application/json" \
    -H "@TOKEN_HEADER: §{@TOKEN}" \
    -H "X-Phantom-Tab-Id: §{@TAB:-}" \
    --data-binary @- >/dev/null 2>&1"""

  private const val GUARD = """[ -n "§{@PORT:-}" ] && [ -n "§{@TOKEN:-}" ]"""

  fun hookScript(provider: String): String = sh(
    """
#!/bin/sh
# Phantom AI hook ($provider): forwards the hook payload on stdin to the IDE. Always exits 0.
payload=§(cat)
if $GUARD; then
  ${POST.replace("@PATH", "/hook/$provider")}
fi
exit 0
""",
  )

  /**
   * Prints one line (never empty) for Claude's status line; posts the payload only when the env is present. The user's
   * previous statusLine command, if any, comes as the first argument: it gets the same payload and its output wins.
   */
  fun statuslineScript(): String = sh(
    """
#!/bin/sh
# Phantom AI statusLine: prints "<model> · 5h N% · 7d N%" (or "Claude") and forwards the payload to the IDE.
# With an argument (the previous statusLine command), prints that command's output instead when it prints any.
payload=§(cat)
flat=§(printf '%s' "§payload" | tr -d '\n')
model=§(printf '%s' "§flat" | sed -n 's/.*"display_name" *: *"\([^"]*\)".*/\1/p' | head -n 1)
five=§(printf '%s' "§flat" | sed -n 's/.*"five_hour" *: *{[^}]*"used_percentage" *: *\([0-9.]*\).*/\1/p' | head -n 1)
week=§(printf '%s' "§flat" | sed -n 's/.*"seven_day" *: *{[^}]*"used_percentage" *: *\([0-9.]*\).*/\1/p' | head -n 1)
line="§{model:-Claude}"
[ -n "§five" ] && line="§line · 5h §{five}%"
[ -n "§week" ] && line="§line · 7d §{week}%"
if [ -n "§{1:-}" ]; then
  prev=§(printf '%s' "§payload" | /bin/sh -c "§1" 2>/dev/null)
  [ -n "§prev" ] && line=§prev
fi
if $GUARD; then
  ${POST.replace("@PATH", "/statusline/claude")} || true
fi
printf '%s\n' "§line"
exit 0
""",
  )

  /** Writes the three scripts (atomically, executable) under [dir] and returns their paths. */
  fun write(dir: Path = defaultDir()): ScriptPaths {
    Files.createDirectories(dir)
    val paths = ScriptPaths(dir.resolve("claude-hook.sh"), dir.resolve("codex-hook.sh"), dir.resolve("claude-statusline.sh"))
    put(paths.claudeHook, hookScript("claude"))
    put(paths.codexHook, hookScript("codex"))
    put(paths.claudeStatusline, statuslineScript())
    return paths
  }

  private fun put(target: Path, text: String) {
    if (Files.isRegularFile(target) && Files.readString(target) == text && Files.isExecutable(target)) return
    val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
    Files.writeString(tmp, text)
    tmp.toFile().setExecutable(true, false)
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  /** Command line stored in the agents' config: runs the script with /bin/sh so no exec bit is needed. */
  fun command(script: Path): String = "/bin/sh '" + script.toString().replace("'", "'\\''") + "'"
}
