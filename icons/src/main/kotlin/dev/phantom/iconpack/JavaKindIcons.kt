// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.iconpack

import com.intellij.ide.FileIconPatcher
import com.intellij.ide.IconProvider
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Iconable
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.impl.ElementPresentationUtil
import com.intellij.ui.IconManager
import com.intellij.util.BitUtil
import javax.swing.Icon

/**
 * Which icon a Java class gets: by the same kinds the platform tells apart with its C/I/E/@/R icons. The icons are
 * Phantom's own, drawn in the Material set's style, and live apart from the generated `icons/material/` directory.
 */
object JavaKindIcons {
  /** The icon for an id from [iconName]. */
  fun icon(name: String): Icon = IconLoader.getIcon("/icons/phantom/$name.svg", JavaKindIcons::class.java.classLoader)

  /** The icon id for a Java class, or null to keep the platform's icon (anything else, anonymous classes, type parameters). */
  fun iconName(element: PsiElement): String? {
    val aClass = element as? PsiClass ?: return null
    if (aClass is PsiTypeParameter || aClass is PsiAnonymousClass || aClass.language != JavaLanguage.INSTANCE) return null
    return when {
      aClass.isAnnotationType -> "java-annotation"
      aClass.isEnum -> "java-enum"
      aClass.isRecord -> "java-record"
      aClass.isInterface -> "java-interface"
      // cached by the platform; tells exceptions apart only outside dumb mode, as the platform's own icon does
      ElementPresentationUtil.getClassKind(aClass) == ElementPresentationUtil.CLASS_KIND_EXCEPTION -> "java-exception"
      // explicit modifier only, like the platform: hasModifierProperty could run augmenters
      aClass.modifierList?.hasExplicitModifier(PsiModifier.ABSTRACT) == true -> "java-abstract"
      else -> "java-class"
    }
  }

  /**
   * The icon id for a Java file: the kind of its class (the class named as the file, else the first), or the Java cup
   * for a file without a class.
   */
  fun fileIconName(file: PsiFile): String {
    val classes = (file as? PsiJavaFile)?.classes ?: return "java-file"
    val main = classes.firstOrNull { it.name == file.virtualFile?.nameWithoutExtension } ?: classes.firstOrNull()
    return main?.let(::iconName) ?: "java-file"
  }
}

/**
 * A `.java` file shows its class's kind icon in editor tabs, Recent Files, Search Everywhere and other file lists, as
 * in the Project view: the plain kind, where the Java plugin's patcher would put the class icon with its marks. A file
 * without a class shows the Java cup.
 */
internal class JavaFileKindPatcher : FileIconPatcher {
  override fun patchIcon(icon: Icon, file: VirtualFile, flags: Int, project: Project?): Icon {
    if (file.isDirectory || file.extension != "java") return icon
    val name = project?.takeIf { !it.isDisposed && file.isValid }?.let {
      ReadAction.compute<String?, RuntimeException> { PsiManager.getInstance(it).findFile(file)?.let(JavaKindIcons::fileIconName) }
    }
    return JavaKindIcons.icon(name ?: "java-file")
  }
}

/**
 * Java classes in the Project view, structure view and navigation bar. Builds the icon as the platform's class icon does, with only the base
 * swapped, so the static/final/test/runnable marks, the lock and the visibility column stay. Runs where the platform's
 * deferred class icon is evaluated; members keep their platform icons.
 */
internal class JavaKindIconProvider : IconProvider(), DumbAware {
  override fun getIcon(element: PsiElement, flags: Int): Icon? {
    val name = JavaKindIcons.iconName(element) ?: return null
    val aClass = element as PsiClass
    val locked = BitUtil.isSet(flags, Iconable.ICON_FLAG_READ_STATUS) && !aClass.isWritable
    val row = IconManager.getInstance().createLayeredIcon(aClass, JavaKindIcons.icon(name), ElementPresentationUtil.getFlags(aClass, locked))
    return ElementPresentationUtil.addVisibilityIcon(aClass, flags, row)
  }
}
