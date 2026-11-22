// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core

import com.intellij.lang.LanguageExtension
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.impl.PsiTreeChangeEventImpl
import com.intellij.psi.util.PsiTreeUtil

/**
 * Where edits cannot change what the declarations of a file are, for one language: a method or function body, or the
 * initializer of a field that cannot be a constant. Bodies must not be ones a declaration's type is inferred from.
 */
interface SpringCodeBodies {
  /** The outermost such body around [element] (or [element] itself), or null when [element] is outside every body. */
  fun bodyOf(element: PsiElement): PsiElement?

  /** Whether [body] declares a class (local, anonymous, object literal): such classes are found by class searches. */
  fun declaresClasses(body: PsiElement): Boolean

  companion object {
    val EP: LanguageExtension<SpringCodeBodies> = LanguageExtension("dev.phantom.spring.codeBodies")
  }
}

/**
 * Counts PSI changes that can change the declarations of Java, Kotlin or Groovy code. Typing inside a method body, a
 * non-constant field initializer or a comment, the bulk of all typing, does not count, so a project-wide search of
 * classes, annotations and supertypes cached on this tracker survives it, where `PsiModificationTracker` moves on
 * every keystroke. Java has no such tracker of its own any more (`JAVA_STRUCTURE_MODIFICATION_COUNT` is now the plain
 * modification count) and the Kotlin one is an Analysis API platform interface, not meant for plugins.
 *
 * A body that declares a class always counts: class and annotation searches see local and anonymous classes. Edits of
 * a language without [SpringCodeBodies] count when it is Groovy and are ignored for other non-JVM languages, as in
 * [SpringCacheDeps.jvm]; changes without a file (directories, files created or deleted, unloaded files) always count.
 */
@Service(Service.Level.PROJECT)
class SpringStructureTracker(project: Project) : ModificationTracker, Disposable {
  private val count = SimpleModificationTracker()

  init {
    PsiManager.getInstance(project).addPsiTreeChangeListener(Listener(), this)
  }

  override fun getModificationCount(): Long = count.modificationCount

  override fun dispose() {}

  // Each change comes as a before and an after event; each side is checked where the changed child is attached (an
  // addition after, a removal before), so its own place decides: a comment added to a class body does not count.
  private inner class Listener : PsiTreeChangeAdapter() {
    override fun beforeChildRemoval(event: PsiTreeChangeEvent) = check(event, event.child)
    override fun beforeChildReplacement(event: PsiTreeChangeEvent) = check(event, event.oldChild)
    override fun beforeChildMovement(event: PsiTreeChangeEvent) = check(event, event.child)
    override fun beforeChildrenChange(event: PsiTreeChangeEvent) = check(event, event.parent)
    override fun beforePropertyChange(event: PsiTreeChangeEvent) = count.incModificationCount()
    override fun childAdded(event: PsiTreeChangeEvent) = check(event, event.child)
    override fun childReplaced(event: PsiTreeChangeEvent) = check(event, event.newChild)
    override fun childMoved(event: PsiTreeChangeEvent) = check(event, event.child)
    override fun childrenChanged(event: PsiTreeChangeEvent) = check(event, event.parent)
    override fun propertyChanged(event: PsiTreeChangeEvent) = count.incModificationCount()

    private fun check(event: PsiTreeChangeEvent, element: PsiElement?) {
      // the generic event comes with every change, around the specific ones, and says nothing of where
      if (event is PsiTreeChangeEventImpl && event.isGenericChange) return
      if (element == null || !isInert(element)) count.incModificationCount()
    }
  }

  companion object {
    private val CLASSIFIED = setOf("JAVA", "kotlin")
    private val JVM_LANGUAGES = setOf("JAVA", "kotlin", "Groovy")

    fun getInstance(project: Project): SpringStructureTracker = project.service()

    /**
     * Dependencies of a cache of declarations: this tracker, files created, deleted or renamed, roots (libraries,
     * modules) and dumb mode, which the plain PSI modification count used to cover.
     */
    fun deps(project: Project): Array<Any> =
      arrayOf(getInstance(project), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, ProjectRootManager.getInstance(project),
              DumbService.getInstance(project).modificationTracker)

    /** Whether a change of [element] leaves every declaration as it was. */
    internal fun isInert(element: PsiElement): Boolean {
      if (element is PsiDirectory || element is PsiFile) return false
      val file = element.containingFile ?: return false
      val language = file.language
      if (language.id !in CLASSIFIED) return language.id !in JVM_LANGUAGES
      if (element is PsiWhiteSpace || PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null) return true
      val bodies = SpringCodeBodies.EP.forLanguage(language) ?: return false
      val body = bodies.bodyOf(element) ?: return false
      return !bodies.declaresClasses(body)
    }
  }
}

/** Java bodies: method and initializer blocks, and initializers of fields that are not final (a final one may be a constant). */
class JavaSpringCodeBodies : SpringCodeBodies {
  override fun bodyOf(element: PsiElement): PsiElement? {
    var body: PsiElement? = null
    var current: PsiElement? = element
    while (current != null && current !is PsiFile) {
      val parent = current.parent
      if (current is PsiCodeBlock && (parent is PsiMethod || parent is PsiClassInitializer) ||
          current is PsiExpression && parent is PsiField && parent.initializer == current && !parent.hasModifierProperty(PsiModifier.FINAL)) {
        body = current
      }
      current = parent
    }
    return body
  }

  override fun declaresClasses(body: PsiElement): Boolean = PsiTreeUtil.findChildOfType(body, PsiClass::class.java) != null
}
