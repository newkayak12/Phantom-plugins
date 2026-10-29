// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.theme

import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.VizState

/** A wall decoration or furniture piece at iso position ([u], [v], height [z]); drawn in [layer] by [key] (back to front). */
data class Decor(val frame: String, val u: Int, val v: Int, val z: Int = 0, val layer: Int, val key: Double)

/**
 * The name plate is a sign board plus two legs: the board's [leftInset]/[rightInset] columns are caps and the columns between
 * stretch to the label; the rows from [boardHeight] down are the legs, copied as they are, centred under the board.
 */
data class NameplateSlice(val leftInset: Int, val rightInset: Int, val boardHeight: Int)

data class ChairFrames(val seat: String, val back: String?)

/** A whole-pixel shift of a sprite in scene (native) pixels; negative [dy] is up. */
data class Offset(val dx: Int, val dy: Int) {
  companion object { val ZERO = Offset(0, 0) }
}

/** RGB light factors, about 0..1 per channel. */
data class Tint(val r: Float, val g: Float, val b: Float)

/**
 * A light cast by every scene item drawn with [frame]: centred ([dx], [dy]) scene pixels from the item's anchor, reaching [radius]
 * pixels across (half that up and down, the 2:1 floor), adding up to [color] x [strength] at its centre with a dithered falloff.
 */
data class LightSource(val frame: String, val dx: Int, val dy: Int, val radius: Int, val color: Tint, val strength: Float)

/**
 * A lit scene. The theme's frames are already shaded by [ambient] (an unlit pixel keeps these factors of its day color); pixels
 * brighter than [ambient] allows are the sheet's own glowing pixels (screens, lamps, lit windows) and stay as they are. Every other
 * pixel inside a light's reach is brightened by it, as if the light were added to the ambient light.
 */
data class Lighting(val ambient: Tint, val sources: List<LightSource>)

/**
 * A look for the office: an atlas (image + frame index in the classpath), a [Palette] and the frame names the renderer asks for.
 * Adding a theme is a new implementation, its sprites and one line in [Themes]; model, layout and renderer do not change.
 * All themes share the grid: 16-unit tiles, a 96-unit room, 2:1 iso (see OfficeLayout). Geometry that follows from sprite sizes
 * that may differ per theme (where the state bubble sits on a head, which frame and which shift each animation phase uses) is
 * asked of the theme too, so the renderer and layout name no frame, color or size of one theme.
 */
interface Theme {
  val id: String
  val displayName: String

  /** Classpath resource of the sprite sheet, absolute (leading slash). */
  val atlasImageResource: String

  /** Classpath resource of the atlas index (frames with x/y/w/h/anchor). */
  val atlasIndexResource: String

  val palette: Palette

  /** Floor tile variants, picked per tile. */
  val floorTiles: List<String>
  val wallRight: String
  val wallRightWindow: String
  val wallLeft: String
  val wallLeftWindow: String
  val wallCorner: String

  /** Which of the six segments of each wall have a window. */
  val windowSegmentsRight: Set<Int>
  val windowSegmentsLeft: Set<Int>

  fun desk(o: Orientation): String
  fun chair(o: Orientation): ChairFrames

  /** Rug frames (styles); a rug is these laid edge to edge, one frame covers [rugTileU] x [rugTileV] floor units. */
  val rugFrames: List<String>
  val rugTileU: Int get() = 24
  val rugTileV: Int get() = 16
  fun rugFrame(style: Int): String = rugFrames[Math.floorMod(style, rugFrames.size)]
  val nameplate: String
  val nameplateSlice: NameplateSlice
  val decor: List<Decor>

  /**
   * The partition segment running along [o] (one 16-unit wall piece, anchored at its start on the floor), set between desk areas
   * where the layout has room for one; null (the default) means the theme has no partitions and the layout adds none.
   */
  fun partition(o: Orientation): String? = null

  /**
   * The scene's lighting, drawn over the room after every sprite and before the bubbles, plate labels and tags (which are never
   * dimmed); null (the default) draws the frames as they are.
   */
  val lighting: Lighting? get() = null

  /** How many different people (hair, shirt) there are. */
  val personVariants: Int
  fun person(variant: Int, state: VizState): String
  fun bubble(state: VizState): String

  /**
   * A session's running subagents are drawn as its subordinates: full-size people (person frames shrunk by this whole factor, 1 =
   * not at all), working, each on a chair of its own ([helperStool], shrunk the same way; null for none). The defaults reuse frames
   * every theme has, so no theme needs new art.
   */
  val helperScale: Int get() = 1
  val helperStool: String? get() = chair(Orientation.RW).seat

  /**
   * Whether a subordinate may stand in front of its own area's desks, covering some of them. False (the default) keeps every desk
   * of the area whole; a theme whose desks fill the area (a kitchen's counters) and whose working pose belongs at a desk says true.
   */
  val helperMayCoverOwnDesks: Boolean get() = false

  /** Where a bubble's anchor goes relative to the point just above the head: ([bubbleAnchorDx], [bubbleAnchorDy]) native pixels. */
  val bubbleAnchorDx: Int get() = 3
  val bubbleAnchorDy: Int get() = -1

  /** Animation: [animationPhases] phases (2..4), one every [animationPeriodMs]. */
  val animationPhases: Int get() = 4
  val animationPeriodMs: Int get() = 250

  /** The person frame in animation [phase]; a theme with real animation frames returns them here. */
  fun personFrame(variant: Int, state: VizState, phase: Int): String = person(variant, state)

  /** The bubble frame in animation [phase]. */
  fun bubbleFrame(state: VizState, phase: Int): String = bubble(state)

  /** The person's shift in [phase]: a typing jitter while working, none otherwise. */
  fun personOffset(state: VizState, phase: Int): Offset =
    if (state == VizState.WORKING) Offset(0, if (Math.floorMod(phase, 2) == 1) -1 else 0) else Offset.ZERO

  /** The bubble's shift in [phase]: a bob of up to 2 pixels. */
  fun bubbleOffset(state: VizState, phase: Int): Offset = Offset(0, -intArrayOf(0, 1, 2, 1)[Math.floorMod(phase, 4)])

  /** Every frame name this theme can ask for; the atlas loader checks each one exists. */
  fun frameNames(): Set<String> = buildSet {
    addAll(floorTiles)
    addAll(listOf(wallRight, wallRightWindow, wallLeft, wallLeftWindow, wallCorner, nameplate))
    addAll(rugFrames)
    decor.forEach { add(it.frame) }
    lighting?.sources?.forEach { add(it.frame) }
    helperStool?.let { add(it) }
    for (o in Orientation.values()) {
      partition(o)?.let { add(it) }
      add(desk(o))
      val c = chair(o)
      add(c.seat)
      c.back?.let { add(it) }
    }
    for (s in VizState.values()) {
      add(bubble(s))
      for (v in 0 until personVariants) add(person(v, s))
      for (ph in 0 until animationPhases) {
        add(bubbleFrame(s, ph))
        for (v in 0 until personVariants) add(personFrame(v, s, ph))
      }
    }
  }
}
