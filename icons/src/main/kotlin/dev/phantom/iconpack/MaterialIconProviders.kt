// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
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

/** Files that show their language's logo (Kotlin's, Java's cup) even where they are shown as the class they declare. */
internal val LOGO_EXTENSIONS: Set<String> = setOf("kt", "java")

/** [file]'s logo; a Java file's cup in the color of its class's kind, where the Java plugin makes [psi] a Java file. */
private fun logo(file: VirtualFile, psi: PsiFile?): Icon? =
  // JavaKindIcons needs the Java plugin's classes: only reached when it is there
  if (psi != null && psi.language.id == "JAVA") JavaKindIcons.icon(JavaKindIcons.fileIconName(psi)) else MaterialIcons.forFile(file)

/**
 * A Kotlin or Java file with one class shows as that class in the Project view, which takes the class icon; give it the
 * file's logo there. Only the Project view: completion and the structure view keep class/interface/object icons.
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
 * icon before the PSI icon; give it the file's logo there too, like the Project view. Only the file-level element: the
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
