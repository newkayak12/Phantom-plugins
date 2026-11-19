// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.iconpack

import com.intellij.ide.FileIconProvider
import com.intellij.ide.IconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import javax.swing.Icon

/** Editor tabs, Recent Files, Search Everywhere and other places that ask for a [VirtualFile]'s icon. */
internal class MaterialFileIconProvider : FileIconProvider, DumbAware {
  override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
    if (file.isDirectory) MaterialIcons.forFolder(file, project) else MaterialIcons.forFile(file)
}

/** The Project view, which asks for PSI icons; runs before the language providers that would show plain folders. */
internal class MaterialPsiIconProvider : IconProvider(), DumbAware {
  override fun getIcon(element: PsiElement, flags: Int): Icon? = when (element) {
    is PsiDirectory -> MaterialIcons.forFolder(element.virtualFile, element.project)
    is PsiFile -> element.virtualFile?.let(MaterialIcons::forFile)
    else -> null
  }
}

/** Files that show as the class they declare, which this pack draws instead of the language plugin. */
internal val LOGO_EXTENSIONS: Set<String> = setOf("kt", "java")

/**
 * [file]'s icon where it shows as its class: its class's kind (a Java file; a Kotlin file of one class), else its
 * language's logo. The kind classes need the Java or Kotlin plugin: only reached when [psi] is of its language.
 */
private fun logo(file: VirtualFile, psi: PsiFile?): Icon? = when (psi?.language?.id) {
  "JAVA" -> JavaKindIcons.icon(JavaKindIcons.fileIconName(psi))
  "kotlin" -> KotlinKindIcons.fileIconName(psi)?.let(KotlinKindIcons::icon)
  else -> null
} ?: MaterialIcons.forFile(file)

/**
 * A Kotlin or Java file with one class shows as that class in the Project view, which takes the language plugin's class
 * icon; give it this pack's ([logo]) there, without the marks a class icon carries, as in editor tabs.
 */
internal class LogoFileNodeDecorator : com.intellij.ide.projectView.ProjectViewNodeDecorator {
  override fun decorate(node: com.intellij.ide.projectView.ProjectViewNode<*>, data: com.intellij.ide.projectView.PresentationData) {
    // a class node may not carry the file itself; take it from the class's PSI
    val psi = (node.value as? PsiElement)?.takeIf { it.isValid }?.containingFile
    val file = node.virtualFile ?: psi?.virtualFile ?: return
    if (file.isDirectory || file.extension !in LOGO_EXTENSIONS) return
    val project = node.project
    val psiFile = psi ?: project?.takeIf { !it.isDisposed && file.isValid }?.let { PsiManager.getInstance(it).findFile(file) }
    logo(file, psiFile)?.let(data::setIcon)
  }
}

/**
 * The navigation bar shows a Kotlin or Java file with one class as that class and asks the navbar extensions for its
 * icon before the PSI icon; give it this pack's ([logo]) there too, like the Project view. Only the file-level element: the
 * methods and nested classes after it keep their own icons.
 */
class LogoFileNavBarIcon : com.intellij.ide.navigationToolbar.AbstractNavBarModelExtension() {
  override fun getPresentableText(`object`: Any?): String? = null

  override fun getIcon(`object`: Any?): Icon? {
    val element = `object` as? PsiElement ?: return null
    if (!element.isValid) return null
    val file = element as? PsiFile ?: element.parent as? PsiFile ?: return null
    val virtualFile = file.virtualFile ?: return null
    if (virtualFile.extension !in LOGO_EXTENSIONS) return null
    return logo(virtualFile, file)
  }
}
