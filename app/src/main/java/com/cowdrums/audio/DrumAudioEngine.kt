package com.cowdrums.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.cowdrums.model.BuiltInKit
import com.cowdrums.model.ChannelMix
import com.cowdrums.model.DrumInstrument
import com.cowdrums.model.DrumPreset
import com.cowdrums.model.PatternSlot
import com.cowdrums.model.SongSection
import com.cowdrums.model.StepPattern
import com.cowdrums.model.StepVelocity
import com.cowdrums.model.TimeSignature
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Sample-accurate low-latency stereo PCM drum mixer & step sequencer engine.
 * Runs a single high-priority audio thread writing directly to AudioTrack.
 */
class DrumAudioEngine(
    private val onStepChanged: (step: Int, bar: Int, activeSlot: PatternSlot, fillActive: Boolean, songSectionIdx: Int) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = BuiltInKitSynthesizer.SAMPLE_RATE
        private const val BUFFER_FRAMES = 512
        private const val MAX_VOICES = 32
    }

    private data class Voice(
        var active: Boolean = false,
        var samples: FloatArray = FloatArray(0),
        var position: Double = 0.0,
        var pitchRate: Double = 1.0,
        var gainL: Float = 0f,
        var gainR: Float = 0f,
        var chokeGroup: Int = 0,
        var choking: Boolean = false,
        var chokeGain: Float = 1f
    )

    private data class PadTrigger(
        val instrument: DrumInstrument,
        val velocity: Float
    )

    data class SequencerSnapshot(
        val preset: DrumPreset,
        val baseSlot: PatternSlot,
        val bpm: Int,
        val swing: Float,
        val metronomeEnabled: Boolean,
        val autoFillEvery4Bars: Boolean,
        val songMode: Boolean,
        val songSections: List<SongSection>,
        val songPresetsById: Map<String, DrumPreset>
    )

    private val running = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    private val fillRequested = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)

    private val kitSamples = AtomicReference<Map<DrumInstrument, FloatArray>>(
        BuiltInKitSynthesizer.renderKit(BuiltInKit.ACOUSTIC)
    )
    private val mixerState = AtomicReference<Map<DrumInstrument, ChannelMix>>(
        DrumInstrument.ALL.associateWith { ChannelMix() }
    )
    private val sequencerState = AtomicReference<SequencerSnapshot?>(null)

    private val metroAccent = BuiltInKitSynthesizer.renderMetronomeClick(accent = true)
    private val metroNormal = BuiltInKitSynthesizer.renderMetronomeClick(accent = false)

    private val padQueue = ConcurrentLinkedQueue<PadTrigger>()
    private val voices = Array(MAX_VOICES) { Voice() }
    private var voiceCursor = 0

    private var audioThread: Thread? = null
    private val currentStepAtomic = AtomicInteger(-1)

    fun startEngine() {
        if (running.getAndSet(true)) return
        audioThread = Thread(::audioLoop, "CowDrums-Audio").apply {
            isDaemon = true
            start()
        }
    }

    fun stopEngine() {
        running.set(false)
        playing.set(false)
        audioThread?.join(500)
        audioThread = null
    }

    fun setKitSamples(samples: Map<DrumInstrument, FloatArray>) {
        kitSamples.set(samples)
    }

    fun currentKitSamples(): Map<DrumInstrument, FloatArray> = kitSamples.get()

    fun setMixerState(mix: Map<DrumInstrument, ChannelMix>) {
        mixerState.set(mix)
    }

    fun updateSequencerState(snapshot: SequencerSnapshot) {
        sequencerState.set(snapshot)
    }

    fun setPlaying(play: Boolean, restart: Boolean = false) {
        if (restart) {
            restartRequested.set(true)
        }
        playing.set(play)
        if (!play) {
            fillRequested.set(false)
            currentStepAtomic.set(-1)
            val snap = sequencerState.get()
            onStepChanged(-1, 0, snap?.baseSlot ?: PatternSlot.MAIN_A, false, 0)
        }
    }

    fun requestFill() {
        if (playing.get()) {
            fillRequested.set(true)
        }
    }

    fun triggerPad(instrument: DrumInstrument, velocity: Float = 0.9f) {
        padQueue.add(PadTrigger(instrument, velocity.coerceIn(0.05f, 1.0f)))
    }

    fun currentStep(): Int = currentStepAtomic.get()

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val minBufBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(BUFFER_FRAMES * 4)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val pcmBuffer = ShortArray(BUFFER_FRAMES * 2)

        var stepIndex = 0
        var barCounter = 0
        var songSectionIndex = 0
        var songBarInSection = 0
        var framesUntilNextStep = 0.0
        var wasPlaying = false
        var activeFillThisBar = false

        try {
            track.play()

            while (running.get()) {
                // Drain manual pad triggers immediately at buffer start
                while (true) {
                    val hit = padQueue.poll() ?: break
                    spawnDrumVoice(hit.instrument, hit.velocity)
                }

                val isPlay = playing.get()
                val snap = sequencerState.get()

                if (isPlay && (!wasPlaying || restartRequested.getAndSet(false))) {
                    stepIndex = 0
                    barCounter = 0
                    songSectionIndex = 0
                    songBarInSection = 0
                    framesUntilNextStep = 0.0
                    activeFillThisBar = false
                }
                wasPlaying = isPlay

                for (frame in 0 until BUFFER_FRAMES) {
                    if (isPlay && snap != null) {
                        if (framesUntilNextStep <= 0.0) {
                            // Resolve active preset and pattern
                            val resolved = resolvePlaybackTarget(
                                snap = snap,
                                songSectionIndex = songSectionIndex,
                                songBarInSection = songBarInSection,
                                barCounter = barCounter,
                                stepIndex = stepIndex,
                                activeFillThisBar = activeFillThisBar
                            )
                            val activePreset = resolved.preset
                            val activeSlot = resolved.slot
                            activeFillThisBar = resolved.fillActive

                            val pattern = activePreset.patternFor(activeSlot)
                            val stepCount = pattern.stepCount.coerceAtLeast(1)
                            val safeStep = stepIndex % stepCount

                            // Trigger all active drums on this step
                            triggerStep(pattern, safeStep)

                            // Trigger metronome on beat boundaries
                            if (snap.metronomeEnabled) {
                                val spb = activePreset.timeSignature.stepsPerBeat.coerceAtLeast(1)
                                if (safeStep % spb == 0) {
                                    spawnRawVoice(
                                        if (safeStep == 0) metroAccent else metroNormal,
                                        gain = if (safeStep == 0) 0.55f else 0.35f
                                    )
                                }
                            }

                            currentStepAtomic.set(safeStep)
                            onStepChanged(
                                safeStep,
                                barCounter,
                                activeSlot,
                                activeFillThisBar,
                                songSectionIndex
                            )

                            // Compute frames until next step (with swing on odd 16th steps)
                            val baseStepFrames = stepDurationFrames(snap.bpm, activePreset.timeSignature)
                            val swingOffset = (snap.swing.coerceIn(0f, 0.70f) * 0.45) * baseStepFrames
                            val stepDur = if (safeStep % 2 == 0) {
                                baseStepFrames + swingOffset
                            } else {
                                (baseStepFrames - swingOffset).coerceAtLeast(baseStepFrames * 0.25)
                            }
                            framesUntilNextStep += stepDur

                            // Advance step & bar counters
                            stepIndex = (safeStep + 1) % stepCount
                            if (stepIndex == 0) {
                                barCounter++
                                activeFillThisBar = false
                                fillRequested.set(false)

                                if (snap.songMode && snap.songSections.isNotEmpty()) {
                                    val sec = snap.songSections[songSectionIndex % snap.songSections.size]
                                    songBarInSection++
                                    if (songBarInSection >= sec.bars.coerceAtLeast(1)) {
                                        songBarInSection = 0
                                        songSectionIndex = (songSectionIndex + 1) % snap.songSections.size
                                    }
                                }
                            }
                        }
                        framesUntilNextStep -= 1.0
                    }

                    // Mix all active voices
                    var outL = 0f
                    var outR = 0f
                    for (v in voices) {
                        if (!v.active) continue
                        val posInt = v.position.toInt()
                        if (posInt >= v.samples.size - 1) {
                            v.active = false
                            continue
                        }
                        val frac = (v.position - posInt).toFloat()
                        val s0 = v.samples[posInt]
                        val s1 = v.samples[posInt + 1]
                        var sample = s0 + (s1 - s0) * frac

                        if (v.choking) {
                            v.chokeGain -= 0.005f // ~4.5 ms anti-click choke fade
                            if (v.chokeGain <= 0f) {
                                v.active = false
                                continue
                            }
                            sample *= v.chokeGain
                        }

                        outL += sample * v.gainL
                        outR += sample * v.gainR
                        v.position += v.pitchRate
                    }

                    // Gentle master soft-clipper so dense beats stay punchy without harsh clipping
                    val softL = tanh(outL * 0.85).toFloat()
                    val softR = tanh(outR * 0.85).toFloat()
                    pcmBuffer[frame * 2] = (softL * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    pcmBuffer[frame * 2 + 1] = (softR * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }

                track.write(pcmBuffer, 0, pcmBuffer.size)
            }
        } finally {
            runCatching {
                track.stop()
                track.release()
            }
        }
    }

    private data class ResolvedTarget(
        val preset: DrumPreset,
        val slot: PatternSlot,
        val fillActive: Boolean
    )

    private fun resolvePlaybackTarget(
        snap: SequencerSnapshot,
        songSectionIndex: Int,
        songBarInSection: Int,
        barCounter: Int,
        stepIndex: Int,
        activeFillThisBar: Boolean
    ): ResolvedTarget {
        if (snap.songMode && snap.songSections.isNotEmpty()) {
            val section = snap.songSections[songSectionIndex % snap.songSections.size]
            val preset = snap.songPresetsById[section.presetId] ?: snap.preset
            val isLastBar = songBarInSection == (section.bars.coerceAtLeast(1) - 1)
            val useFill = (section.playFillOnLastBar && isLastBar) || fillRequested.get() || activeFillThisBar
            return ResolvedTarget(
                preset = preset,
                slot = if (useFill) PatternSlot.FILL else section.slot,
                fillActive = useFill
            )
        }

        val autoFill = snap.autoFillEvery4Bars && ((barCounter + 1) % 4 == 0)
        val useFill = activeFillThisBar || fillRequested.get() || (autoFill && stepIndex == 0)
        return ResolvedTarget(
            preset = snap.preset,
            slot = if (useFill) PatternSlot.FILL else snap.baseSlot,
            fillActive = useFill
        )
    }

    private fun stepDurationFrames(bpm: Int, timeSignature: TimeSignature): Double {
        val safeBpm = bpm.coerceIn(40, 240).toDouble()
        // For 4/4, 3/4, 5/4: 1 beat = quarter note = 4 sixteenth-note steps.
        // For 6/8, 7/8: 1 step = sixteenth note, where BPM is quarter-note pulse (so 1 quarter = 4 sixteenth steps).
        val quarterNoteSeconds = 60.0 / safeBpm
        val sixteenthSeconds = quarterNoteSeconds / 4.0
        return (sixteenthSeconds * SAMPLE_RATE).coerceAtLeast(256.0)
    }

    private fun triggerStep(pattern: StepPattern, step: Int) {
        for (inst in DrumInstrument.ALL) {
            val velLevel = pattern.velocityAt(inst, step)
            if (velLevel > 0) {
                spawnDrumVoice(inst, StepVelocity.toGain(velLevel))
            }
        }
    }

    private fun spawnDrumVoice(instrument: DrumInstrument, velocity: Float) {
        val mixMap = mixerState.get()
        val anySolo = mixMap.values.any { it.solo }
        val ch = mixMap[instrument] ?: ChannelMix()
        if (ch.muted || (anySolo && !ch.solo)) return

        val samples = kitSamples.get()[instrument] ?: return
        if (samples.size < 2) return

        // Choke any ringing voices in the same choke group (e.g., Closed HH chokes Open HH)
        if (instrument.chokeGroup != 0) {
            for (v in voices) {
                if (v.active && v.chokeGroup == instrument.chokeGroup) {
                    v.choking = true
                }
            }
        }

        val gain = (velocity * ch.volume).coerceIn(0f, 1.5f)
        // Equal-power stereo panning
        val panNorm = ((ch.pan.coerceIn(-1f, 1f) + 1f) * 0.5f) * (Math.PI / 2.0)
        val gainL = (gain * cos(panNorm)).toFloat()
        val gainR = (gain * sin(panNorm)).toFloat()
        val pitchRate = 2.0.pow(ch.pitchSemitones.coerceIn(-12f, 12f) / 12.0)

        allocateVoice(
            samples = samples,
            pitchRate = pitchRate,
            gainL = gainL,
            gainR = gainR,
            chokeGroup = instrument.chokeGroup
        )
    }

    private fun spawnRawVoice(samples: FloatArray, gain: Float) {
        allocateVoice(
            samples = samples,
            pitchRate = 1.0,
            gainL = gain,
            gainR = gain,
            chokeGroup = 0
        )
    }

    private fun allocateVoice(
        samples: FloatArray,
        pitchRate: Double,
        gainL: Float,
        gainR: Float,
        chokeGroup: Int
    ) {
        // Prefer an inactive slot; otherwise steal oldest round-robin
        var target: Voice? = null
        for (i in 0 until MAX_VOICES) {
            val idx = (voiceCursor + i) % MAX_VOICES
            if (!voices[idx].active) {
                target = voices[idx]
                voiceCursor = (idx + 1) % MAX_VOICES
                break
            }
        }
        if (target == null) {
            target = voices[voiceCursor]
            voiceCursor = (voiceCursor + 1) % MAX_VOICES
        }
        target.active = true
        target.samples = samples
        target.position = 0.0
        target.pitchRate = pitchRate
        target.gainL = gainL
        target.gainR = gainR
        target.chokeGroup = chokeGroup
        target.choking = false
        target.chokeGain = 1f
    }
}
