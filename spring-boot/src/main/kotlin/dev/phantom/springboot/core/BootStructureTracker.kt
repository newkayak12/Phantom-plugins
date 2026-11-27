// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.impl.PsiTreeChangeEventImpl

/**
 * Counts Java and Kotlin changes outside method bodies: declarations, annotations, Javadoc, field and parameter
 * defaults. The platform's language tracker also moves on every keystroke inside a method, which cannot change the
 * keys read from `@ConfigurationProperties` classes. Created on the first request for those keys, so nothing listens in
 * projects that never ask.
 */
@Service(Service.Level.PROJECT)
class BootStructureTracker(project: Project) : SimpleModificationTracker(), Disposable {
  init {
    PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
      override fun childAdded(event: PsiTreeChangeEvent) = changed(event)
      override fun childRemoved(event: PsiTreeChangeEvent) = changed(event)
      override fun childReplaced(event: PsiTreeChangeEvent) = changed(event)
      override fun childMoved(event: PsiTreeChangeEvent) = changed(event)
      override fun childrenChanged(event: PsiTreeChangeEvent) = changed(event)
      override fun propertyChanged(event: PsiTreeChangeEvent) = incModificationCount()
    }, this)
  }

  private fun changed(event: PsiTreeChangeEvent) {
    // PomModelImpl wraps every change in a generic event on the whole file; the specific events tell where it was.
    if ((event as? PsiTreeChangeEventImpl)?.isGenericChange == true) return
    // An incremental reparse replaces a whole method body: the new child is the code block, its parent the method.
    val element = event.newChild ?: event.child?.takeIf { it.parent != null } ?: event.parent ?: event.file
    if (element == null) {
      incModificationCount()
      return
    }
    val language = element.containingFile?.language?.id ?: element.language.id
    if (language != "JAVA" && language != "kotlin") return
    if (!insideBody(element)) incModificationCount()
  }

  override fun dispose() {
    // Disposing the service removes the PSI listener registered with it.
  }

  companion object {
    fun getInstance(project: Project): BootStructureTracker = project.service()

    /**
     * Inside a Java code block, or a Kotlin function's block body. The Kotlin PSI is matched by element type name
     * (`BLOCK` under `FUN`) because this plugin does not depend on the Kotlin plugin.
     */
    internal fun insideBody(element: PsiElement): Boolean {
      var e: PsiElement? = element
      while (e != null && e !is com.intellij.psi.PsiFile) {
        if (e is PsiCodeBlock) return true
        val type = e.node?.elementType?.toString()
        if (type == "BLOCK" && e.parent?.node?.elementType?.toString() == "FUN") return true
        e = e.parent
      }
      return false
    }
  }
}
