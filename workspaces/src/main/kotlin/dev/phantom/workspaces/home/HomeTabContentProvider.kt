// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.nonModalWelcomeScreen.WelcomeScreenAppScopeHolder
import com.intellij.platform.ide.nonModalWelcomeScreen.rightTab.WelcomeRightTabContentProvider
import com.intellij.platform.ide.nonModalWelcomeScreen.rightTab.WelcomeRightTabContentProvider.FeatureButtonModel
import dev.phantom.workspaces.core.PhantomWorkspacesBundle
import java.util.function.Supplier
import javax.swing.Icon
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The Home tab in the editor area of the Home project: a title and buttons for the IDE's own actions to start from
 * (open, new project, clone, settings). A button whose action the IDE lacks (New Project without the Java plugin) is
 * left out. No links, banners or product promotions.
 */
internal class HomeTabContentProvider : WelcomeRightTabContentProvider {
  override val coroutineScope: CoroutineScope get() = WelcomeScreenAppScopeHolder.getInstance().coroutineScope

  // WelcomeScreenRightTab draws these blurred behind the content; Home has a plain background
  override val backgroundImageVectorLight: ImageVector = EMPTY_BACKGROUND
  override val backgroundImageVectorDark: ImageVector = EMPTY_BACKGROUND

  override val fileTypeIcon: Icon get() = AllIcons.Nodes.HomeFolder
  override val title: Supplier<String> = Supplier { PhantomWorkspacesBundle.message("home.tab.title") }
  override val secondaryTitle: Supplier<String> = Supplier { PhantomWorkspacesBundle.message("home.tab.subtitle") }

  // Phantom Workspaces' setting turns Home off, not the welcome screen's own check box
  override val isDisableOptionVisible: Boolean = false

  @Composable
  override fun getFeatureButtonModels(project: Project): List<FeatureButtonModel> = BUTTONS.mapNotNull { (actionId, key, icon) ->
    val action = ActionManager.getInstance().getAction(actionId) ?: return@mapNotNull null
    FeatureButtonModel(PhantomWorkspacesBundle.message(key), icon) { p, _ ->
      ActionUtil.invokeAction(action, SimpleDataContext.getProjectContext(p), ActionPlaces.WELCOME_SCREEN, null, null)
    }
  }

  private companion object {
    val EMPTY_BACKGROUND: ImageVector =
      ImageVector.Builder("PhantomHomeBackground", 1.dp, 1.dp, viewportWidth = 1f, viewportHeight = 1f).build()

    val BUTTONS: List<Triple<String, String, IconKey>> = listOf(
      Triple("WelcomeScreen.OpenDirectoryProject", "home.tab.open", AllIconsKeys.Actions.MenuOpen),
      Triple("NewProject", "home.tab.new.project", AllIconsKeys.General.Add),
      Triple("Vcs.VcsClone", "home.tab.clone", AllIconsKeys.Vcs.Clone),
      Triple("ShowSettings", "home.tab.settings", AllIconsKeys.General.Settings),
    )
  }
}
