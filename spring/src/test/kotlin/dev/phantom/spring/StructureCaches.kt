// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.UsefulTestCase.assertTrue
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import dev.phantom.spring.core.SpringBeanCatalog
import dev.phantom.spring.core.SpringBeans
import dev.phantom.spring.core.SpringStructureTracker
import dev.phantom.spring.core.ide.SpringBeanNameTypos
import dev.phantom.spring.core.ide.SpringBeanNames
import junit.framework.TestCase.assertEquals

/**
 * Checks for the caches kept on [SpringStructureTracker]: after each edit the cached results equal a full computation,
 * and an edit inside a body neither moves the tracker nor recomputes.
 */
class StructureCaches(private val fixture: JavaCodeInsightTestFixture) {
  private val project get() = fixture.project

  /** Edits [file] as typing does: through its document, then a commit. Asserts the PSI changed. */
  fun edit(file: VirtualFile, find: String, replace: String) {
    val document = FileDocumentManager.getInstance().getDocument(file)!!
    val offset = document.text.indexOf(find)
    assertTrue("'$find' not in ${file.name}", offset >= 0)
    val modifications = PsiModificationTracker.getInstance(project).modificationCount
    WriteCommandAction.runWriteCommandAction(project) { document.replaceString(offset, offset + find.length, replace) }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertTrue(PsiModificationTracker.getInstance(project).modificationCount > modifications)
  }

  /** Reads every cache, makes the edit, reads again: nothing recomputed, the tracker did not move, results still match. */
  fun assertKept(file: VirtualFile, find: String, replace: String) = assertKept(file, "'$replace'") { edit(file, find, replace) }

  /** The edit moves the tracker, and the caches then match a full computation. */
  fun assertRecomputed(file: VirtualFile, find: String, replace: String) = assertRecomputed("'$replace'") { edit(file, find, replace) }

  /** A PSI write (as refactorings and quick fixes make) on [file] that must keep the caches. */
  fun assertPsiKept(file: VirtualFile, what: String, write: (PsiFile) -> Unit) = assertKept(file, what) { psiWrite(file, write) }

  /** A PSI write on [file] that must move the tracker. */
  fun assertPsiRecomputed(file: VirtualFile, what: String, write: (PsiFile) -> Unit) = assertRecomputed(what) { psiWrite(file, write) }

  private fun psiWrite(file: VirtualFile, write: (PsiFile) -> Unit) {
    val psiFile = PsiManager.getInstance(project).findFile(file)!!
    val modifications = PsiModificationTracker.getInstance(project).modificationCount
    WriteCommandAction.runWriteCommandAction(project) { write(psiFile) }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertTrue(PsiModificationTracker.getInstance(project).modificationCount > modifications)
  }

  private fun assertKept(file: VirtualFile, what: String, change: () -> Unit) {
    // the first document of a file may reload its PSI, a change of its own; have it before counting
    FileDocumentManager.getInstance().getDocument(file)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    readAll()
    val before = counts()
    change()
    readAll()
    assertEquals("caches recomputed after $what", before, counts())
    assertParity()
  }

  private fun assertRecomputed(what: String, change: () -> Unit) {
    readAll()
    val before = SpringStructureTracker.getInstance(project).modificationCount
    change()
    assertTrue("tracker did not move after $what", SpringStructureTracker.getInstance(project).modificationCount > before)
    assertParity()
  }

  private fun counts() = listOf(SpringStructureTracker.getInstance(project).modificationCount, SpringBeanNames.computeCount.get().toLong(),
                                SpringBeans.beanMethodSearches.get().toLong(), SpringBeans.inheritorSearches.get().toLong())

  private fun readAll() {
    SpringBeanNameTypos.knownNames(project)
    beans().forEach { SpringBeanCatalog.dependencies(it) }
  }

  private fun beans(): List<PsiModifierListOwner> =
    SpringBeanCatalog.collect(project).values.flatten().flatMap { listOf(it.element) + it.beanMethods.map { m -> m.element } }

  /** Cached bean names, known names and autowiring candidates equal what a fresh search finds. */
  fun assertParity() {
    val fresh = LinkedHashMap<String, MutableList<String>>()
    val freshNames = HashSet<String>()
    for (beans in SpringBeanCatalog.collect(project).values) {
      for (bean in beans) {
        (listOf(bean) + bean.beanMethods).forEach { info ->
          fresh.getOrPut(info.name) { ArrayList() }.add(describe(info.element))
          freshNames += info.name
          SpringBeans.qualifier(info.element)?.let(freshNames::add)
        }
      }
    }
    assertEquals(fresh, SpringBeanNames.byName(project).mapValues { (_, list) -> list.map(::describe) })
    assertEquals(freshNames, SpringBeanNameTypos.knownNames(project))
    for (bean in beans()) {
      for (dependency in SpringBeanCatalog.dependencies(bean)) {
        // as sets: the order of an annotation search is not stable across edits even without caching (Kotlin @Bean
        // functions of one file come back in either order after a body edit), and nothing relies on it
        assertEquals("candidates of ${describe(dependency.point.element)}", SpringBeans.findCandidates(dependency.point, true).map(::describe).sorted(),
                     dependency.candidates.map(::describe).sorted())
        val target = SpringBeans.beanTypeOf(dependency.point.type) ?: continue
        for (production in listOf(true, false)) {
          val scope = if (production) GlobalSearchScopesCore.projectProductionScope(project) else GlobalSearchScope.projectScope(project)
          assertEquals("inheritors of ${target.qualifiedName}", ClassInheritorsSearch.search(target, scope, true).findAll().map(::describe).sorted(),
                       SpringBeans.inheritors(target, production).map(::describe).sorted())
        }
      }
    }
  }

  /** How many injection points the beans have, so a test can tell the comparison covered something. */
  fun candidateCount(): Int = beans().sumOf { bean -> SpringBeanCatalog.dependencies(bean).sumOf { it.candidates.size } }

  private fun describe(element: PsiElement): String {
    assertTrue("stale element $element", element.isValid)
    return when (element) {
      is PsiMethod -> "${element.containingClass?.qualifiedName}#${element.name}: ${element.returnType?.canonicalText}"
      is PsiClass -> element.qualifiedName ?: "$element in ${element.containingFile?.name}"
      else -> element.toString()
    }
  }
}
