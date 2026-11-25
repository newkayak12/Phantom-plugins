// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.platform.ide.nonModalWelcomeScreen

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionNotApplicableException
import com.intellij.openapi.options.advanced.AdvancedSettingsChangeListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.impl.CloseProjectWindowHelper
import com.intellij.util.application
import com.intellij.util.asSafely

internal class WelcomeScreenProjectActivity : ProjectActivity {
  init {
    val app = ApplicationManager.getApplication()
    val isNotAvailable = app.isCommandLine || app.isHeadlessEnvironment || app.isUnitTestMode
    if (isNotAvailable) {
      throw ExtensionNotApplicableException.create()
    }
  }

  override suspend fun execute(project: Project) {
    if (project.isWelcomeExperienceProject()) {
      dropModalWelcomeScreenOnClose(project)
      // phantom-idea: no subscription to the welcome tab closing, it only fed WelcomeScreenTabUsageCollector (usage statistics)
    }
  }

  private suspend fun dropModalWelcomeScreenOnClose(project: Project) {
    if (isNonModalWelcomeScreenEnabled) {
      CloseProjectWindowHelper.SHOW_WELCOME_FRAME_FOR_PROJECT.set(project, false)
    }
    subscribeToSettingsChanges(project)
  }

  private suspend fun subscribeToSettingsChanges(project: Project) {
    application
      .messageBus
      .connect(WelcomeScreenProjectScopeHolder.getInstanceAsync(project).coroutineScope)
      .subscribe(AdvancedSettingsChangeListener.TOPIC,
                 object : AdvancedSettingsChangeListener {
                   override fun advancedSettingChanged(id: String, oldValue: Any, newValue: Any) {
                     if (id == NON_MODAL_WELCOME_SCREEN_SETTING_ID) {
                       val welcomeScreenEnabled = newValue.asSafely<Boolean>() ?: return
                       CloseProjectWindowHelper.SHOW_WELCOME_FRAME_FOR_PROJECT.set(project, !welcomeScreenEnabled)
                     }
                     // phantom-idea: the setting change is not logged (WelcomeScreenTabUsageCollector, usage statistics)
                   }
                 })
  }
}
