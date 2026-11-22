// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core

import com.intellij.lang.ASTNode
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
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.LazyParseableElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Where edits cannot change what the declarations of a file are, for one language: a method or function body, or the
 * initializer of a field that cannot be a constant. Bodies must not be ones a declaration's type is inferred from.
 */
interface SpringCodeBodies {
  /** The outermost such body around [element] (or [element] itself), or null when [element] is outside every body. */
  fun bodyOf(element: PsiElement): PsiElement?

  /**
   * Whether [element] is or contains a class declaration (local, anonymous, object literal): such classes are found by
   * class searches. [element] may be detached from its file (a removed or replaced child).
   */
  fun declaresClasses(element: PsiElement): Boolean

  companion object {
    val EP: LanguageExtension<SpringCodeBodies> = LanguageExtension("dev.phantom.spring.codeBodies")

    /**
     * Whether [element] or a descendant is a class by [isClass]. A detached subtree may hold a lazily parsed block
     * that was never parsed, and parsing it off the tree is not allowed: such a block may declare classes.
     */
    fun containsClass(element: PsiElement, isClass: (PsiElement) -> Boolean): Boolean {
      val root = element.node ?: return isClass(element)
      val detached = root.treeParent == null
      val stack = ArrayDeque<ASTNode>()
      stack.add(root)
      while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        if (detached && node is LazyParseableElement && !node.isParsed) return true
        if (node is CompositeElement && isClass(node.psi)) return true
        var child = node.firstChildNode
        while (child != null) {
          stack.add(child)
          child = child.treeNext
        }
      }
      return false
    }
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
 * a language without [SpringCodeBodies] count when it is Groovy and are ignored otherwise, as in [SpringCacheDeps.jvm]:
 * that includes JVM languages other than Java, Kotlin and Groovy (Scala, Clojure), whose classes these caches would
 * then miss until the next counted change. Changes without a file (directories, files created or deleted, unloaded
 * files) always count.
 */
@Service(Service.Level.PROJECT)
class SpringStructureTracker(project: Project) : ModificationTracker, Disposable {
  private val count = SimpleModificationTracker()

  init {
    PsiManager.getInstance(project).addPsiTreeChangeListener(Listener(), this)
  }

  override fun getModificationCount(): Long = count.modificationCount

  override fun dispose() {}

  // Typing (a document reparse) sends a before and an after event for each change; a PSI write (refactorings, quick
  // fixes, delete(), replace()) sends a generic before event and only the specific after events. So the after events
  // decide on their own: a removed or replaced child is detached by then and is judged by its parent's place plus the
  // classes it declared; a child added where it is inert (a comment in a class body) does not count. Both fold changes
  // under a changed node into a "contents changed" event on it, which cannot show what was removed: after typing, the
  // before events of the folded changes have judged that; after a PSI write it counts unless the node is a leaf.
  private inner class Listener : PsiTreeChangeAdapter() {
    // a specific before event since the batch began: the changes come from a reparse, which announces each removal
    private var announced = false

    override fun beforeChildAddition(event: PsiTreeChangeEvent) { announce(event) }
    override fun beforeChildRemoval(event: PsiTreeChangeEvent) = check(announce(event), isInert(event.child))
    override fun beforeChildReplacement(event: PsiTreeChangeEvent) = check(announce(event), isInert(event.oldChild))
    override fun beforeChildMovement(event: PsiTreeChangeEvent) = check(announce(event), isInert(event.child))
    override fun beforeChildrenChange(event: PsiTreeChangeEvent) = check(announce(event), isInert(event.parent))
    override fun beforePropertyChange(event: PsiTreeChangeEvent) = count.incModificationCount()
    override fun childAdded(event: PsiTreeChangeEvent) = check(event, isInert(event.child))
    override fun childRemoved(event: PsiTreeChangeEvent) = check(event, isInertDetached(event.parent, event.child))
    override fun childReplaced(event: PsiTreeChangeEvent) =
      check(event, isInertReplacement(event.parent, event.oldChild, event.newChild))
    override fun childMoved(event: PsiTreeChangeEvent) =
      check(event, isInert(event.child) && isInertDetached(event.oldParent, event.child))
    override fun childrenChanged(event: PsiTreeChangeEvent) =
      check(event, isInert(event.parent) && (announced || event.parent?.firstChild == null))
    override fun propertyChanged(event: PsiTreeChangeEvent) = count.incModificationCount()

    private fun announce(event: PsiTreeChangeEvent): PsiTreeChangeEvent {
      // the generic before event opens each batch
      announced = !isGeneric(event)
      return event
    }

    private fun check(event: PsiTreeChangeEvent, inert: Boolean) {
      // the generic events come with every change, around the specific ones, and say nothing of where
      if (!isGeneric(event) && !inert) count.incModificationCount()
    }

    private fun isGeneric(event: PsiTreeChangeEvent) = event is PsiTreeChangeEventImpl && event.isGenericChange
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

    /** Whether a change of [element], attached to its file, leaves every declaration as it was. */
    internal fun isInert(element: PsiElement?): Boolean {
      if (element == null || element is PsiDirectory || element is PsiFile) return false
      val file = element.containingFile ?: return false
      val language = file.language
      if (language.id !in CLASSIFIED) return language.id !in JVM_LANGUAGES
      if (element is PsiWhiteSpace || PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null) return true
      val bodies = SpringCodeBodies.EP.forLanguage(language) ?: return false
      val body = bodies.bodyOf(element) ?: return false
      return !bodies.declaresClasses(body)
    }

    /**
     * Whether replacing [old], no longer attached, with [new] leaves every declaration as it was. [old] held the place
     * [new] has, so a body replaced as a whole (`= a` by `= b` under a written type) is still a body.
     */
    internal fun isInertReplacement(parent: PsiElement?, old: PsiElement?, new: PsiElement?): Boolean {
      if (old == null || !isInert(new)) return false
      // whitespace and comments are inert anywhere, so they say nothing of the place
      if (new is PsiWhiteSpace || new is PsiComment) return isInertDetached(parent, old)
      // isInert has already ignored languages without bodies
      val bodies = SpringCodeBodies.EP.forLanguage(new!!.containingFile.language) ?: return true
      return !bodies.declaresClasses(old)
    }

    /** Whether removing [child], no longer attached, from [parent] leaves every declaration as it was. */
    internal fun isInertDetached(parent: PsiElement?, child: PsiElement?): Boolean {
      if (parent == null || child == null || child is PsiDirectory || child is PsiFile) return false
      val language = parent.containingFile?.language ?: return false
      if (language.id !in CLASSIFIED) return language.id !in JVM_LANGUAGES
      val bodies = SpringCodeBodies.EP.forLanguage(language) ?: return false
      if (bodies.declaresClasses(child)) return false
      return child is PsiWhiteSpace || child is PsiComment || isInert(parent)
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

  override fun declaresClasses(element: PsiElement): Boolean = SpringCodeBodies.containsClass(element) { it is PsiClass }
}
