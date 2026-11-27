// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terraform

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiFile

class HclStructureViewFactory : PsiStructureViewFactory {
  override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
    if (psiFile !is HclFile) return null
    return object : TreeBasedStructureViewBuilder() {
      override fun createStructureViewModel(editor: Editor?): StructureViewModel = HclStructureViewModel(psiFile, editor)
    }
  }
}

class HclStructureViewModel(file: HclFile, editor: Editor?) :
  StructureViewModelBase(file, editor, HclStructureElement(file)),
  StructureViewModel.ElementInfoProvider {
  init {
    withSuitableClasses(HclBlock::class.java, HclAttribute::class.java)
  }

  override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false
  override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = element.value is HclAttribute
}

/** The file, then its blocks and attributes in file order, with nested blocks under their block. */
class HclStructureElement(private val element: NavigatablePsiElement) : StructureViewTreeElement {
  override fun getValue(): Any = element
  override fun getPresentation(): ItemPresentation = element.presentation ?: error("Terraform elements have a presentation")
  override fun navigate(requestFocus: Boolean) = element.navigate(requestFocus)
  override fun canNavigate(): Boolean = element.canNavigate()
  override fun canNavigateToSource(): Boolean = element.canNavigateToSource()

  override fun getChildren(): Array<TreeElement> {
    val members = when (element) {
      is HclFile -> element.children.filter { it is HclBlock || it is HclAttribute }
      is HclBlock -> element.body?.children?.filter { it is HclBlock || it is HclAttribute }.orEmpty()
      else -> emptyList()
    }
    return members.map { HclStructureElement(it as NavigatablePsiElement) }.toTypedArray()
  }
}
