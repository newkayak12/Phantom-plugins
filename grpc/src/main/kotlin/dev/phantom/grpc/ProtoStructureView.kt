// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.grpc

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

class ProtoStructureViewFactory : PsiStructureViewFactory {
  override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
    if (psiFile !is ProtoFile) return null
    return object : TreeBasedStructureViewBuilder() {
      override fun createStructureViewModel(editor: Editor?): StructureViewModel = ProtoStructureViewModel(psiFile, editor)
    }
  }
}

class ProtoStructureViewModel(file: ProtoFile, editor: Editor?) :
  StructureViewModelBase(file, editor, ProtoStructureElement(file)),
  StructureViewModel.ElementInfoProvider {
  init {
    withSuitableClasses(ProtoNamedElement::class.java)
  }

  override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false
  override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = element.value.let { it is ProtoNamedElement && it.members.isEmpty() }
}

/** The file, then messages, enums and services with their fields, values, oneofs and rpcs, as in the file. */
class ProtoStructureElement(private val element: NavigatablePsiElement) : StructureViewTreeElement {
  override fun getValue(): Any = element
  override fun getPresentation(): ItemPresentation = element.presentation ?: error("Proto elements have a presentation")
  override fun navigate(requestFocus: Boolean) = element.navigate(requestFocus)
  override fun canNavigate(): Boolean = element.canNavigate()
  override fun canNavigateToSource(): Boolean = element.canNavigateToSource()

  override fun getChildren(): Array<TreeElement> {
    val members = when (element) {
      is ProtoFile -> element.definitions
      is ProtoNamedElement -> element.members
      else -> emptyList()
    }
    return members.map { ProtoStructureElement(it) }.toTypedArray()
  }
}
