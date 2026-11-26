package com.cowdrums

import com.cowdrums.audio.BuiltInKitSynthesizer
import com.cowdrums.audio.SoundFont2Parser
import com.cowdrums.data.PresetLibrary
import com.cowdrums.model.BuiltInKit
import com.cowdrums.model.DrumInstrument
import com.cowdrums.model.StepPattern
import com.cowdrums.model.StepVelocity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class PresetLibraryAndSynthTest {

    @Test
    fun presetLibraryHasAtLeast50ValidUniquePresets() {
        val presets = PresetLibrary.builtInPresets
        assertTrue("Expected at least 50 built-in presets, got ${presets.size}", presets.size >= 50)

        val ids = presets.map { it.id }.toSet()
        assertEquals("Preset IDs must be unique", presets.size, ids.size)

        for (p in presets) {
            assertTrue("Preset ${p.id} BPM out of range: ${p.bpm}", p.bpm in 40..240)
            assertEquals("Preset ${p.id} mainA stepCount mismatch", p.timeSignature.stepsPerBar, p.mainA.stepCount)
            assertEquals("Preset ${p.id} varB stepCount mismatch", p.timeSignature.stepsPerBar, p.varB.stepCount)
            assertEquals("Preset ${p.id} fill stepCount mismatch", p.timeSignature.stepsPerBar, p.fill.stepCount)
            assertTrue("Preset ${p.id} mainA should not be empty", p.mainA.rows.isNotEmpty())
            assertTrue("Preset ${p.id} varB should not be empty", p.varB.rows.isNotEmpty())
            assertTrue("Preset ${p.id} fill should not be empty", p.fill.rows.isNotEmpty())
        }
    }

    @Test
    fun allBuiltInKitsSynthesizeFiniteNonSilentSamples() {
        for (kit in BuiltInKit.entries) {
            val samples = BuiltInKitSynthesizer.renderKit(kit)
            assertEquals("Kit $kit must have all 12 instruments", DrumInstrument.ALL.size, samples.size)
            for (inst in DrumInstrument.ALL) {
                val buf = samples[inst]!!
                assertTrue("Sample for $kit/$inst too short", buf.size > 500)
                var maxAbs = 0f
                for (s in buf) {
                    assertTrue("NaN/Inf in $kit/$inst", s.isFinite())
                    val a = abs(s)
                    if (a > maxAbs) maxAbs = a
                }
                assertTrue("Sample for $kit/$inst is silent", maxAbs > 0.1f)
            }
        }
    }

    @Test
    fun stepPatternResizeAndVelocityCycleWork() {
        val p = StepPattern.fromAscii(16, DrumInstrument.KICK to "X...|....|X...|....")
        assertEquals(StepVelocity.ACCENT, p.velocityAt(DrumInstrument.KICK, 0))
        assertEquals(StepVelocity.OFF, p.velocityAt(DrumInstrument.KICK, 1))

        val doubled = p.resized(32)
        assertEquals(32, doubled.stepCount)
        assertEquals(StepVelocity.ACCENT, doubled.velocityAt(DrumInstrument.KICK, 16))
        assertEquals(StepVelocity.ACCENT, doubled.velocityAt(DrumInstrument.KICK, 24))
    }

    @Test
    fun soundFontParserHandlesInvalidFilesGracefully() {
        val missing = File("/tmp/non_existent_cowdrums_test.sf2")
        assertTrue(SoundFont2Parser.listPresets(missing).isEmpty())
    }

    @Test
    fun wavAndMidiExportProduceValidBinaryFiles() {
        val preset = PresetLibrary.builtInPresets.first()
        val kit = BuiltInKitSynthesizer.renderKit(BuiltInKit.ACOUSTIC)
        val mixer = DrumInstrument.ALL.associateWith { com.cowdrums.model.ChannelMix() }

        val wavOut = java.io.ByteArrayOutputStream()
        com.cowdrums.audio.AudioMidiExporter.exportPatternToWav(
            output = wavOut,
            preset = preset,
            slot = com.cowdrums.model.PatternSlot.MAIN_A,
            bpm = preset.bpm,
            swing = preset.swing,
            bars = 1,
            kitSamples = kit,
            mixer = mixer
        )
        val wavBytes = wavOut.toByteArray()
        assertTrue("WAV file too small", wavBytes.size > 44_000)
        assertEquals("RIFF", String(wavBytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wavBytes, 8, 4, Charsets.US_ASCII))

        val midOut = java.io.ByteArrayOutputStream()
        com.cowdrums.audio.AudioMidiExporter.exportPatternToMidi(
            output = midOut,
            preset = preset,
            slot = com.cowdrums.model.PatternSlot.MAIN_A,
            bpm = preset.bpm,
            swing = preset.swing,
            bars = 2
        )
        val midBytes = midOut.toByteArray()
        assertTrue("MIDI file too small", midBytes.size > 40)
        assertEquals("MThd", String(midBytes, 0, 4, Charsets.US_ASCII))
        assertEquals("MTrk", String(midBytes, 14, 4, Charsets.US_ASCII))
    }

    @Test
    fun bundledGeneralUserSf2LoadsAllElevenRealKitsAndEightBasses() {
        val sf2File = File("src/main/assets/soundfonts/GeneralUser_GS_Drums.sf2")
        assertTrue("Bundled SF2 missing at ${sf2File.absolutePath}", sf2File.exists())

        val presets = SoundFont2Parser.listPresets(sf2File)
        assertEquals("Expected 18 real studio presets (10 drums + 8 basses)", 18, presets.size)

        val bytes = sf2File.readBytes()
        for (kit in BuiltInKit.entries) {
            val samples = SoundFont2Parser.loadKitSamples(
                bytes = bytes,
                bank = 128,
                preset = kit.sf2Preset,
                applyLoFi = kit.applyLoFiFilter
            )
            assertEquals("Kit $kit missing instruments", DrumInstrument.ALL.size, samples.size)
            for (inst in DrumInstrument.ALL) {
                val pcm = samples[inst]!!
                assertTrue("Sample too short for $kit/$inst", pcm.size > 500)
                var maxAbs = 0f
                for (s in pcm) {
                    assertTrue("Non-finite sample in $kit/$inst", s.isFinite())
                    val a = abs(s)
                    if (a > maxAbs) maxAbs = a
                }
                assertTrue("Silent sample in $kit/$inst", maxAbs > 0.2f)
            }
        }

        for (bp in com.cowdrums.model.BassPreset.entries) {
            val notes = SoundFont2Parser.loadBassNotes(bytes = bytes, bank = bp.bank, preset = bp.gmProgram, midiRange = 36..47)
            assertEquals("Bass $bp missing octave notes", 12, notes.size)
            val c2 = notes[36]!!
            assertTrue("Bass $bp C2 too short", c2.size > 2000)
        }
    }
}
