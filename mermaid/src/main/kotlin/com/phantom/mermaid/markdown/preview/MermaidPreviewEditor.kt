// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.markdown.preview

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.phantom.mermaid.MermaidBundle
import com.phantom.mermaid.MermaidPlugin
import com.phantom.mermaid.settings.MermaidSettingsConfigurable
import com.intellij.openapi.application.readAction
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
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
  // phantom-idea: one render at a time. Typing, theme and settings changes each started their own load/update, and
  // two of them interleaving in the browser could leave an older diagram or theme on screen.
  private val renderMutex = Mutex()

  private val document = FileDocumentManager.getInstance().getDocument(file)!!

  // phantom-idea: the diagram, and with it the JCEF browser, is created when the preview is first shown, not when the
  // file opens, so a tab that only shows the editor starts no JCEF.
  private val diagram = lazy { createComponent() }
  private val component by diagram

  // TextEditorWithPreview makes this visible for the split and preview layouts; it starts hidden so that the first
  // showing creates the diagram.
  private val wrapper = JPanel(BorderLayout()).apply {
    isVisible = false
    addComponentListener(object: ComponentAdapter() {
      override fun componentShown(event: ComponentEvent) {
        // A show posted after dispose must not create a browser for a closed editor.
        if (!diagram.isInitialized() && coroutineScope.isActive) {
          add(component)
          revalidate()
        }
      }
    })
  }

  init {
    document.addDocumentListener(UpdatePreviewDocumentListener(), this)
    coroutineScope.launch(context = Dispatchers.EDT) {
      // debounce to prevent JBCefQuery pool exhaustion; Phantom: long enough that a diagram is not laid out for every
      // keystroke, and text that did not change (undo back to it, a save) is not sent again
      updateViewRequests.debounce(PREVIEW_DEBOUNCE).collectLatest {
        // phantom-idea: a diagram not created yet loads the current text when it is shown.
        if (!diagram.isInitialized()) return@collectLatest
        renderMutex.withLock {
          val text = readAction { document.text }
          if (text != lastSentText) {
            component.diagramComponent().update(text)
            lastSentText = text
          }
        }
      }
    }
    val connection = application.messageBus.connect(this)
    connection.subscribe(LafManagerListener.TOPIC, object: LafManagerListener {
      private var previousLaf = LafManager.getInstance().currentUIThemeLookAndFeel

      override fun lookAndFeelChanged(source: LafManager) {
        if (source.currentUIThemeLookAndFeel != previousLaf) {
          previousLaf = source.currentUIThemeLookAndFeel
          // phantom-idea: a diagram not created yet picks up the theme when it is shown.
          if (!diagram.isInitialized()) return
          coroutineScope.launch(context = Dispatchers.Default) {
            reload(component.diagramComponent())
          }
        }
      }
    })

    connection.subscribe(MermaidSettingsConfigurable.ChangeListener.TOPIC, MermaidSettingsConfigurable.ChangeListener {
      // phantom-idea: a diagram not created yet picks up the settings when it is shown.
      if (!diagram.isInitialized()) return@ChangeListener
      coroutineScope.launch(context = Dispatchers.Default) {
        reload(component.diagramComponent())
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
      reload(component)
    }
  }

  // phantom-idea: the document is read inside a read action (it was read from a background thread without one), and
  // under the render lock, so a reload and a typing update never run at the same time.
  private suspend fun reload(component: MermaidDiagramPreviewComponent) {
    renderMutex.withLock {
      component.load()
      val text = readAction { document.text }
      component.update(text)
      lastSentText = text
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
    return wrapper
  }

  override fun getPreferredFocusedComponent(): JComponent {
    return wrapper
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
