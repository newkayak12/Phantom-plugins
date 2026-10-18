// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker.dockerfile

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement

/**
 * Ctrl+B from a build stage name to the `FROM ... AS <name>` that starts it: in `COPY --from=<name>`, `ADD --from`,
 * `RUN --mount=...,from=<name>` and `FROM <name>`. A Dockerfile has no PSI of its own, so the target is an offset.
 */
class DockerfileStageGotoDeclarationHandler : GotoDeclarationHandler {
  override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
    val file = source?.containingFile ?: return null
    val dockerfile = Dockerfile.of(file) ?: return null
    val text = file.viewProvider.contents
    val stages = stages(dockerfile, text)
    if (stages.isEmpty()) return null
    val instruction = dockerfile.instructions.firstOrNull { it.argsRange.containsOffset(offset) } ?: return null
    val (name, _) = nameAt(instruction, text, offset) ?: return null
    // a stage can only be used after it is defined
    val stage = stages.lastOrNull { it.name.equals(name, ignoreCase = true) && it.offset < instruction.keywordRange.startOffset } ?: return null
    return arrayOf(StageTarget(file, stage.offset, stage.name))
  }

  class Stage(val name: String, val offset: Int)

  companion object {
    private val FROM_FLAG = Regex("""--from=([^\s]+)""")
    private val MOUNT_FROM = Regex("""--mount=(?:\S*?,)?from=([^,\s]+)""")

    fun stages(dockerfile: Dockerfile, text: CharSequence): List<Stage> = dockerfile.instructions.filter { it.upper == "FROM" }.mapNotNull { from ->
      val operands = from.operands
      val asAt = operands.indexOfFirst { it.equals("AS", ignoreCase = true) }
      val name = operands.getOrNull(asAt + 1)?.takeIf { asAt >= 0 } ?: return@mapNotNull null
      val at = indexIn(text, from.argsRange, name, wholeWord = true) ?: return@mapNotNull null
      Stage(name, at)
    }

    /** The stage name under [offset] in [instruction], with its range. */
    fun nameAt(instruction: Dockerfile.Instruction, text: CharSequence, offset: Int): Pair<String, TextRange>? {
      val args = text.subSequence(instruction.argsRange.startOffset, instruction.argsRange.endOffset).toString()
      val base = instruction.argsRange.startOffset
      val candidates = buildList {
        when (instruction.upper) {
          "COPY", "ADD" -> FROM_FLAG.findAll(args).forEach { add(it.groups[1]!!) }
          "RUN" -> MOUNT_FROM.findAll(args).forEach { add(it.groups[1]!!) }
          "FROM" -> instruction.operands.firstOrNull()?.let { image ->
            Regex("""(?<![\w./:-])""" + Regex.escape(image) + """(?![\w./:-])""").find(args)?.groups?.get(0)?.let { add(it) }
          }
        }
      }
      val hit = candidates.firstOrNull { offset >= base + it.range.first && offset <= base + it.range.last + 1 } ?: return null
      return hit.value to TextRange(base + hit.range.first, base + hit.range.last + 1)
    }

    private fun indexIn(text: CharSequence, range: TextRange, word: String, wholeWord: Boolean): Int? {
      val args = text.subSequence(range.startOffset, range.endOffset).toString()
      val m = Regex((if (wholeWord) """(?<!\S)""" else "") + Regex.escape(word) + (if (wholeWord) """(?!\S)""" else "")).findAll(args).lastOrNull() ?: return null
      return range.startOffset + m.range.first
    }
  }

  /** A place in the Dockerfile: navigating opens the file there. */
  class StageTarget(private val file: PsiFile, private val offset: Int, private val stageName: String) : FakePsiElement() {
    override fun getParent(): PsiElement = file
    override fun getContainingFile(): PsiFile = file
    override fun getTextOffset(): Int = offset
    override fun getTextRange(): TextRange = TextRange(offset, offset + stageName.length)
    override fun getName(): String = stageName
    override fun getPresentableText(): String = stageName
    override fun getLocationString(): String = file.name
    override fun isValid(): Boolean = file.isValid
    override fun canNavigate(): Boolean = true
    override fun navigate(requestFocus: Boolean) {
      val virtualFile = file.virtualFile ?: return
      OpenFileDescriptor(file.project, virtualFile, offset).navigate(requestFocus)
    }
  }
}
