package com.cowdrums.data

import com.cowdrums.model.DrumPreset

/**
 * Built-in catalog of 51 handcrafted, ready-to-play drum presets across 10 genres.
 * Each preset includes Main A, Variation B, and a musical Fill pattern (153 total patterns).
 */
object PresetLibrary {
    val builtInPresets: List<DrumPreset> = PresetLibraryPart1.presets + PresetLibraryPart2.presets
}
