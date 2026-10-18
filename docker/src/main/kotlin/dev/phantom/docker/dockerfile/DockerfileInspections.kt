// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker.dockerfile

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.phantom.docker.ComposeFiles
import dev.phantom.docker.compose.PhantomDockerBundle
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import java.nio.file.FileSystems

/** Base for the Dockerfile checks: they run on files named like a Dockerfile, whatever their file type. */
abstract class DockerfileInspection : LocalInspectionTool() {
  override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
    val dockerfile = Dockerfile.of(file) ?: return null
    val out = ArrayList<ProblemDescriptor>()
    check(file, dockerfile) { range, message, type ->
      if (!range.isEmpty && range.endOffset <= file.textLength) {
        out.add(manager.createProblemDescriptor(file, range, message, type, isOnTheFly))
      }
    }
    return out.toTypedArray()
  }

  abstract fun check(file: PsiFile, dockerfile: Dockerfile, report: (TextRange, String, ProblemHighlightType) -> Unit)
}

/**
 * What the builder rejects or misreads: unknown instructions, an indented line after an instruction that did not end
 * with `\`, wrong argument counts, malformed `ENV`/`LABEL`/`ARG`, exec form that is not JSON, unclosed heredocs,
 * duplicate stage names and `COPY --from` indexes of later stages.
 */
class DockerfileInspectionImpl : DockerfileInspection() {
  override fun check(file: PsiFile, dockerfile: Dockerfile, report: (TextRange, String, ProblemHighlightType) -> Unit) {
    val error = ProblemHighlightType.GENERIC_ERROR_OR_WARNING
    for (p in dockerfile.problems) report(p.range, PhantomDockerBundle.message("dockerfile.heredoc.unclosed", p.delimiter), error)
    val stages = HashMap<String, Int>()
    var stage = -1
    for (ins in dockerfile.instructions) {
      val upper = ins.upper
      if (upper !in Dockerfile.INSTRUCTIONS) {
        val previous = ins.previous
        if (ins.indentedAfterComplete && previous != null) {
          report(previous.keywordRange, PhantomDockerBundle.message("dockerfile.missing.continuation", previous.upper), error)
        }
        else {
          report(ins.keywordRange, PhantomDockerBundle.message("dockerfile.unknown.instruction", ins.keyword), error)
        }
        continue
      }
      when (upper) {
        "FROM" -> {
          stage++
          val ops = ins.operands
          val ok = ops.size == 1 || (ops.size == 3 && ops[1].equals("AS", ignoreCase = true))
          if (!ok) report(ins.keywordRange, PhantomDockerBundle.message("dockerfile.from.arguments"), error)
          if (ops.size == 3) {
            val name = ops[2].lowercase()
            if (stages.put(name, stage) != null) report(ins.argsRange, PhantomDockerBundle.message("dockerfile.stage.duplicate", ops[2]), error)
          }
        }
        "COPY", "ADD" -> {
          val count = if (ins.isExecForm) Dockerfile.jsonArray(ins.args.substring(ins.args.indexOf('[')))?.size else ins.operands.size
          if (count != null && count < 2 && ins.heredocs.isEmpty()) report(ins.keywordRange, PhantomDockerBundle.message("dockerfile.copy.arguments", upper), error)
          ins.flag("from")?.toIntOrNull()?.let { index ->
            if (index >= stage) report(ins.argsRange, PhantomDockerBundle.message("dockerfile.from.index", index), error)
          }
        }
        "WORKDIR", "USER", "STOPSIGNAL" -> if (ins.tokens.size != 1) report(ins.keywordRange, PhantomDockerBundle.message("dockerfile.one.argument", upper), error)
        "EXPOSE", "VOLUME", "RUN", "CMD", "ENTRYPOINT", "MAINTAINER" ->
          if (ins.args.isBlank() && ins.heredocs.isEmpty()) report(ins.keywordRange, PhantomDockerBundle.message("dockerfile.needs.argument", upper), error)
        "ARG" -> {
          if (ins.tokens.size != 1 || !ARG.matches(ins.tokens.firstOrNull().orEmpty())) report(ins.argsRange.takeIf { !it.isEmpty } ?: ins.keywordRange, PhantomDockerBundle.message("dockerfile.arg.form"), error)
        }
        "LABEL" -> {
          if (ins.tokens.isEmpty() || ins.tokens.any { !it.contains('=') }) report(ins.argsRange.takeIf { !it.isEmpty } ?: ins.keywordRange, PhantomDockerBundle.message("dockerfile.label.form"), error)
        }
        "ENV" -> {
          val tokens = ins.tokens
          // `ENV key value` (old form, one variable) or `ENV a=1 b=2`
          val newForm = tokens.firstOrNull()?.contains('=') == true
          if (tokens.isEmpty() || (newForm && tokens.any { !it.contains('=') }) || (!newForm && tokens.size < 2)) {
            report(ins.argsRange.takeIf { !it.isEmpty } ?: ins.keywordRange, PhantomDockerBundle.message("dockerfile.env.form"), error)
          }
        }
        "SHELL" -> if (Dockerfile.jsonArray(ins.args).isNullOrEmpty()) report(ins.keywordRange, PhantomDockerBundle.message("dockerfile.shell.json"), error)
        "ONBUILD" -> {
          val inner = ins.tokens.firstOrNull()?.uppercase()
          if (inner == null || inner !in Dockerfile.INSTRUCTIONS || inner in setOf("ONBUILD", "FROM", "MAINTAINER")) {
            report(ins.keywordRange, PhantomDockerBundle.message("dockerfile.onbuild"), error)
          }
        }
      }
      // exec form that is not JSON (single quotes, missing comma) runs through the shell instead
      if (upper in EXEC_FORM && ins.isExecForm && Dockerfile.jsonArray(ins.args) == null) {
        report(ins.argsRange, PhantomDockerBundle.message("dockerfile.exec.not.json", upper), error)
      }
    }
  }

  companion object {
    private val ARG = Regex("""[A-Za-z_][A-Za-z0-9_]*(=.*)?""")
    private val EXEC_FORM = setOf("RUN", "CMD", "ENTRYPOINT")
  }
}

/** `CMD` and `ENTRYPOINT` in shell form: the process runs under `/bin/sh -c`, so it is not PID 1 and misses SIGTERM. */
class DockerfileShellFormInspection : DockerfileInspection() {
  override fun check(file: PsiFile, dockerfile: Dockerfile, report: (TextRange, String, ProblemHighlightType) -> Unit) {
    for (ins in dockerfile.instructions) {
      if (ins.upper in setOf("CMD", "ENTRYPOINT") && !ins.isExecForm && ins.args.isNotBlank()) {
        report(ins.keywordRange, PhantomDockerBundle.message("dockerfile.shell.form", ins.upper), ProblemHighlightType.WEAK_WARNING)
      }
    }
  }
}

/**
 * `COPY`/`ADD` sources missing from the build context. The context is the `build.context` of a compose file beside or
 * above that names this Dockerfile, else the Dockerfile's directory. Because `docker build -f` can point anywhere, a
 * source found in any directory above the Dockerfile (up to the project) is not reported.
 */
class DockerfileCopyPathsInspection : DockerfileInspection() {
  override fun check(file: PsiFile, dockerfile: Dockerfile, report: (TextRange, String, ProblemHighlightType) -> Unit) {
    val vFile = file.originalFile.virtualFile ?: return
    val dir = vFile.parent ?: return
    val context = composeContext(file, vFile) ?: dir
    val stop = file.project.guessProjectDir()
    for (ins in dockerfile.instructions) {
      if (ins.upper !in setOf("COPY", "ADD") || ins.flag("from") != null || ins.heredocs.isNotEmpty() || ins.isExecForm) continue
      val sources = ins.operands.dropLast(1)
      for (source in sources) {
        val path = source.trim('"', '\'')
        if (path.contains('$') || path.contains("://") || path.startsWith("git@") || path.isEmpty()) continue
        if (exists(context, path)) continue
        // the build may run with another context: accept the source if any directory above has it
        var up: VirtualFile? = dir
        var found = false
        while (up != null) {
          if (exists(up, path)) { found = true; break }
          if (up == stop) break
          up = up.parent
        }
        if (found) continue
        val range = locate(file.text, ins.argsRange, source) ?: ins.keywordRange
        report(range, PhantomDockerBundle.message("dockerfile.copy.missing", path, (stop?.let { VfsUtilCore.getRelativePath(context, it) } ?: context.name).ifEmpty { "." }), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
      }
    }
  }

  private fun locate(text: String, within: TextRange, token: String): TextRange? {
    val at = text.indexOf(token, within.startOffset)
    return if (at >= 0 && at + token.length <= within.endOffset) TextRange(at, at + token.length) else null
  }

  private fun exists(context: VirtualFile, path: String): Boolean {
    val relative = path.removePrefix("/").removePrefix("./").trimEnd('/')
    if (relative.isEmpty() || relative == ".") return true
    if (relative.none { it == '*' || it == '?' || it == '[' }) return context.findFileByRelativePath(relative) != null
    // a glob: the directory part must exist and some child must match the last part
    val dirPart = relative.substringBeforeLast('/', "")
    val namePart = relative.substringAfterLast('/')
    val base = if (dirPart.isEmpty()) context else context.findFileByRelativePath(dirPart) ?: return dirPart.any { it == '*' || it == '?' }
    val matcher = runCatching { FileSystems.getDefault().getPathMatcher("glob:$namePart") }.getOrNull() ?: return true
    return base.children.any { matcher.matches(java.nio.file.Path.of(it.name)) }
  }

  /** The `build.context` of a service in a compose file beside or up to two levels above whose Dockerfile is [dockerfile]. */
  private fun composeContext(file: PsiFile, dockerfile: VirtualFile): VirtualFile? {
    var dir = dockerfile.parent
    repeat(3) {
      val compose = dir?.children.orEmpty().filter { ComposeFiles.isComposeFile(it.name) }
      for (c in compose) {
        val yaml = PsiManager.getInstance(file.project).findFile(c) as? YAMLFile ?: continue
        val root = yaml.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: continue
        val services = root.getKeyValueByKey("services")?.value as? YAMLMapping ?: continue
        for (service in services.keyValues) {
          val build = (service.value as? YAMLMapping)?.getKeyValueByKey("build")?.value ?: continue
          val (contextPath, dockerfilePath) = when (build) {
            is YAMLScalar -> build.textValue to "Dockerfile"
            is YAMLMapping -> ((build.getKeyValueByKey("context")?.value as? YAMLScalar)?.textValue ?: ".") to
              ((build.getKeyValueByKey("dockerfile")?.value as? YAMLScalar)?.textValue ?: "Dockerfile")
            else -> continue
          }
          val ctx = c.parent.findFileByRelativePath(contextPath) ?: continue
          if (ctx.findFileByRelativePath(dockerfilePath) == dockerfile) return ctx
        }
      }
      dir = dir?.parent
    }
    return null
  }
}
