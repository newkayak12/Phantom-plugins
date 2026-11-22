// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.markdown.preview

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.phantom.mermaid.MermaidBundle
import com.phantom.mermaid.MermaidPlugin
import com.phantom.mermaid.settings.MermaidSettingsConfigurable
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.util.coroutines.childScope
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.util.application
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
internal class MermaidPreviewEditor(
  private val project: Project,
  file: VirtualFile
): FileEditor, UserDataHolder by UserDataHolderBase() {
  private val pluginScope
    get() = MermaidPlugin.coroutineScope(project)

  private val coroutineScope: CoroutineScope = pluginScope.childScope("MermaidPreviewEditorScope", CoroutineName("MermaidPreviewEditorScope"))
  // Phantom: a signal, not the text: the text is read once the typing pauses instead of copied on every keystroke
  private val updateViewRequests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  private var lastSentText: String? = null

  private val document = FileDocumentManager.getInstance().getDocument(file)!!

  private val component by lazy { createComponent() }

  init {
    document.addDocumentListener(UpdatePreviewDocumentListener(), this)
    coroutineScope.launch(context = Dispatchers.EDT) {
      // debounce to prevent JBCefQuery pool exhaustion; Phantom: long enough that a diagram is not laid out for every
      // keystroke, and text that did not change (undo back to it, a save) is not sent again
      updateViewRequests.debounce(PREVIEW_DEBOUNCE).collectLatest {
        val text = document.text
        if (text != lastSentText) {
          component.diagramComponent().update(text)
          lastSentText = text
        }
      }
    }
    val connection = application.messageBus.connect(this)
    connection.subscribe(LafManagerListener.TOPIC, object: LafManagerListener {
      private var previousLaf = LafManager.getInstance().currentUIThemeLookAndFeel

      override fun lookAndFeelChanged(source: LafManager) {
        if (source.currentUIThemeLookAndFeel != previousLaf) {
          previousLaf = source.currentUIThemeLookAndFeel
          coroutineScope.launch(context = Dispatchers.Default) {
            val component = component.diagramComponent()
            component.load()
            component.update(document.text)
          }
        }
      }
    })

    connection.subscribe(MermaidSettingsConfigurable.ChangeListener.TOPIC, MermaidSettingsConfigurable.ChangeListener {
      coroutineScope.launch(context = Dispatchers.Default) {
        val component = component.diagramComponent()
        component.load()
        component.update(document.text)
      }
    })
  }

  private inner class UpdatePreviewDocumentListener: DocumentListener {
    override fun documentChanged(event: DocumentEvent) {
      updateViewRequests.tryEmit(Unit)
    }
  }

  private fun createComponent(): MermaidPreviewComponentContainer {
    return MermaidPreviewComponentContainer(
      parentDisposable = this,
      coroutineScope = coroutineScope,
      componentDeferred = createDiagramComponent(parentDisposable = this)
    )
  }

  private fun createDiagramComponent(parentDisposable: Disposable): Pair<MermaidDiagramPreviewComponent, Deferred<Unit>> {
    val component = MermaidDiagramPreviewComponent(project)
    Disposer.register(parentDisposable, component)
    return component to coroutineScope.async(context = Dispatchers.Default) {
      component.load()
      component.update(document.text)
    }
  }

  private class MermaidPreviewComponentContainer(
    parentDisposable: Disposable,
    coroutineScope: CoroutineScope,
    componentDeferred: Pair<MermaidDiagramPreviewComponent, Deferred<Unit>>
  ): JBLoadingPanel(BorderLayout(), parentDisposable) {
    private val loadDeferred = coroutineScope.async(context = Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
      startLoading()
      val (component, loadTask) = componentDeferred
      add(component)
      loadTask.await()
      stopLoading()
      return@async component
    }

    suspend fun diagramComponent(): MermaidDiagramPreviewComponent {
      return loadDeferred.await()
    }
  }

  override fun getComponent(): JComponent {
    return component
  }

  override fun getPreferredFocusedComponent(): JComponent {
    return component
  }

  override fun getName(): String {
    return MermaidBundle.message("mermaid.diagram.editor")
  }

  override fun setState(state: FileEditorState) = Unit

  override fun isModified(): Boolean {
    return false
  }

  override fun isValid(): Boolean {
    return true
  }

  override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun dispose() {
    coroutineScope.cancel("Cancel on dispose")
  }
}

private val PREVIEW_DEBOUNCE = 150.milliseconds
