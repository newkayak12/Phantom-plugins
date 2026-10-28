package dev.phantom.ai

import com.intellij.openapi.wm.ToolWindowType
import dev.phantom.ai.toolwindow.SplitLayout
import dev.phantom.ai.toolwindow.SplitOrientation
import dev.phantom.ai.toolwindow.TreeFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLayoutTest {
  private fun o(w: Int, h: Int) = SplitLayout.orientation(w, h)

  @Test fun wideWindowIsHorizontal() = assertEquals(SplitOrientation.HORIZONTAL, o(1200, 500))

  @Test fun exactly600WideIsHorizontal() = assertEquals(SplitOrientation.HORIZONTAL, o(600, 400))

  @Test fun justUnder600IsVertical() = assertEquals(SplitOrientation.VERTICAL, o(599, 400))

  @Test fun tallWindowIsVerticalEvenWhenWiderThan600() = assertEquals(SplitOrientation.VERTICAL, o(900, 1200))

  @Test fun width080OfHeightIsHorizontal() = assertEquals(SplitOrientation.HORIZONTAL, o(960, 1200))

  @Test fun notYetLaidOutIsVertical() = assertEquals(SplitOrientation.VERTICAL, o(0, 0))

  @Test fun buttonsInHeaderWhenFloatingWindowedOrTreeHidden() {
    assertTrue(SplitLayout.buttonsInHeader(ToolWindowType.FLOATING, false))
    assertTrue(SplitLayout.buttonsInHeader(ToolWindowType.WINDOWED, false))
    assertTrue(SplitLayout.buttonsInHeader(ToolWindowType.DOCKED, true))
    assertTrue(SplitLayout.buttonsInHeader(ToolWindowType.SLIDING, true))
  }

  @Test fun buttonsInTreeColumnWhenDockedOrSlidingWithTreeShown() {
    assertFalse(SplitLayout.buttonsInHeader(ToolWindowType.DOCKED, false))
    assertFalse(SplitLayout.buttonsInHeader(ToolWindowType.SLIDING, false))
  }

  @Test fun defaultsAndKeys() {
    assertEquals(0.3f, SplitLayout.DEFAULT_H, 0f)
    assertEquals(0.35f, SplitLayout.DEFAULT_V, 0f)
    assertEquals("PhantomAi.split.h", SplitLayout.KEY_H)
    assertEquals("PhantomAi.split.v", SplitLayout.KEY_V)
  }

  @Test fun toggleKeepsLastProportion() {
    val fold = TreeFold(hidden = false, proportion = 0.42f)
    assertEquals(0.42f, fold.effectiveProportion(), 0f)
    fold.toggle()
    assertTrue(fold.hidden)
    assertEquals(0f, fold.effectiveProportion(), 0f)
    assertEquals(0.42f, fold.proportion, 0f)
    fold.toggle()
    assertFalse(fold.hidden)
    assertEquals(0.42f, fold.effectiveProportion(), 0f)
  }
}
