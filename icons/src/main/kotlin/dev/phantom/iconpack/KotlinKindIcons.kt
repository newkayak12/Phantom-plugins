// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.iconpack

import com.intellij.ide.FileIconPatcher
import com.intellij.ide.IconProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.IconManager
import com.intellij.ui.PlatformIcons
import com.intellij.util.BitUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import javax.swing.Icon

/**
 * Which icon a Kotlin class gets: a Material shape by kind (circle, dashed circle, rounded square, hexagon, @, card,
 * diamond) with the Kotlin mark in the corner. Kinds are told apart by syntax only, so they show in dumb mode too.
 */
object KotlinKindIcons {
  /** The icon for an id from [iconName]; the same directory as the Java kinds, without loading their Java-only code. */
  fun icon(name: String): Icon = IconLoader.getIcon("/icons/phantom/$name.svg", KotlinKindIcons::class.java.classLoader)

  /** The icon id for a Kotlin class or object, or null to keep the Kotlin plugin's icon (companions, object literals). */
  fun iconName(element: PsiElement): String? = when (val declaration = element as? KtClassOrObject) {
    is KtObjectDeclaration -> "kotlin-object".takeIf { !declaration.isCompanion() && !declaration.isObjectLiteral() }
    is KtClass -> when {
      declaration.isAnnotation() -> "kotlin-annotation"
      declaration.isEnum() -> "kotlin-enum"
      declaration.isInterface() -> "kotlin-interface"
      declaration.isData() -> "kotlin-data"
      declaration.isSealed() || declaration.hasModifier(KtTokens.ABSTRACT_KEYWORD) -> "kotlin-abstract"
      else -> "kotlin-class"
    }
    else -> null
  }

  /**
   * The icon id for a Kotlin file that declares one class or object and nothing else, as the Kotlin plugin shows such a
   * file as that class; null for any other file, which keeps the Kotlin logo.
   */
  fun fileIconName(file: PsiFile): String? {
    val declaration = (file as? KtFile)?.takeIf { !it.isScript() }?.declarations?.singleOrNull() ?: return null
    return iconName(declaration)
  }
}

/**
 * A Kotlin file of one class shows that class's kind in editor tabs, Recent Files, Search Everywhere and other file
 * lists; other Kotlin files keep the Kotlin logo.
 */
internal class KotlinFileKindPatcher : FileIconPatcher {
  override fun patchIcon(icon: Icon, file: VirtualFile, flags: Int, project: Project?): Icon {
    if (file.isDirectory || file.extension != "kt") return icon
    val name = project?.takeIf { !it.isDisposed && file.isValid }?.let {
      ReadAction.compute<String?, RuntimeException> { PsiManager.getInstance(it).findFile(file)?.let(KotlinKindIcons::fileIconName) }
    }
    return name?.let(KotlinKindIcons::icon) ?: icon
  }
}

/** Kotlin classes and objects in the structure view, completion and wherever their PSI icon shows, with their visibility. */
internal class KotlinKindIconProvider : IconProvider(), DumbAware {
  override fun getIcon(element: PsiElement, flags: Int): Icon? {
    val name = KotlinKindIcons.iconName(element) ?: return null
    val base = KotlinKindIcons.icon(name)
    if (!BitUtil.isSet(flags, Iconable.ICON_FLAG_VISIBILITY)) return base
    val declaration = element as KtClassOrObject
    val visibility = when {
      declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) -> PlatformIcons.Private
      declaration.hasModifier(KtTokens.PROTECTED_KEYWORD) -> PlatformIcons.Protected
      declaration.hasModifier(KtTokens.INTERNAL_KEYWORD) -> PlatformIcons.Local
      else -> PlatformIcons.Public
    }
    val manager = IconManager.getInstance()
    return manager.createRowIcon(base, manager.getPlatformIcon(visibility))
  }
}
