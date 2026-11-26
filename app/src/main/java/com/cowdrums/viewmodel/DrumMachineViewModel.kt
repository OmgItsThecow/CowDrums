package com.cowdrums.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cowdrums.audio.AudioMidiExporter
import com.cowdrums.audio.BuiltInKitSynthesizer
import com.cowdrums.audio.DrumAudioEngine
import com.cowdrums.audio.SoundFont2Parser
import com.cowdrums.data.CowDrumsRepository
import com.cowdrums.data.PresetLibrary
import com.cowdrums.model.BuiltInKit
import com.cowdrums.model.ChannelMix
import com.cowdrums.model.DrumGenre
import com.cowdrums.model.DrumInstrument
import com.cowdrums.model.DrumPreset
import com.cowdrums.model.LoadedSoundFont
import com.cowdrums.model.PatternSlot
import com.cowdrums.model.SongSection
import com.cowdrums.model.SoundSource
import com.cowdrums.model.StepPattern
import com.cowdrums.model.StepVelocity
import com.cowdrums.model.TimeSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class DrumMachineUiState(
    val allPresets: List<DrumPreset> = PresetLibrary.builtInPresets,
    val activePreset: DrumPreset = PresetLibrary.builtInPresets.first(),
    val selectedSlot: PatternSlot = PatternSlot.MAIN_A,
    val playingSlot: PatternSlot = PatternSlot.MAIN_A,
    val bpm: Int = PresetLibrary.builtInPresets.first().bpm,
    val swing: Float = PresetLibrary.builtInPresets.first().swing,
    val isPlaying: Boolean = false,
    val currentStep: Int = -1,
    val currentBar: Int = 0,
    val fillActive: Boolean = false,
    val autoFillEvery4Bars: Boolean = false,
    val metronomeEnabled: Boolean = false,
    val liveRecordEnabled: Boolean = false,
    val lockKitOnPresetChange: Boolean = false,
    val soundSource: SoundSource = SoundSource.BuiltIn(BuiltInKit.ACOUSTIC),
    val loadedSoundFonts: List<LoadedSoundFont> = emptyList(),
    val isLoadingSound: Boolean = false,
    val mixer: Map<DrumInstrument, ChannelMix> = DrumInstrument.ALL.associateWith { ChannelMix() },
    val songMode: Boolean = false,
    val songSections: List<SongSection> = emptyList(),
    val activeSongSectionIndex: Int = 0,
    val statusMessage: String? = null
)

class DrumMachineViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = CowDrumsRepository(application.applicationContext)
    private val kitCache = HashMap<BuiltInKit, Map<DrumInstrument, FloatArray>>()

    private val _uiState = MutableStateFlow(DrumMachineUiState())
    val uiState: StateFlow<DrumMachineUiState> = _uiState.asStateFlow()

    private val tapTimestamps = ArrayDeque<Long>(6)

    private val audioEngine = DrumAudioEngine { step, bar, activeSlot, fillActive, songIdx ->
        _uiState.update { state ->
            state.copy(
                currentStep = step,
                currentBar = bar,
                playingSlot = activeSlot,
                fillActive = fillActive,
                activeSongSectionIndex = songIdx
            )
        }
    }

    init {
        val defaultKit = BuiltInKit.ACOUSTIC
        val initialSamples = BuiltInKitSynthesizer.renderKit(defaultKit)
        kitCache[defaultKit] = initialSamples
        audioEngine.setKitSamples(initialSamples)
        audioEngine.setMixerState(_uiState.value.mixer)
        pushSequencerSnapshot(_uiState.value)
        audioEngine.startEngine()

        viewModelScope.launch(Dispatchers.IO) {
            val customPresets = repo.loadCustomPresets()
            val savedSong = repo.loadSongSections().ifEmpty { defaultDemoSong() }
            val sf2s = repo.listLoadedSoundFonts()
            _uiState.update { state ->
                val merged = customPresets + PresetLibrary.builtInPresets
                state.copy(
                    allPresets = merged,
                    songSections = savedSong,
                    loadedSoundFonts = sf2s
                )
            }
            pushSequencerSnapshot(_uiState.value)
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.stopEngine()
    }

    fun togglePlay() {
        val next = !_uiState.value.isPlaying
        _uiState.update { it.copy(isPlaying = next) }
        audioEngine.setPlaying(next, restart = next)
    }

    fun stopPlayback() {
        _uiState.update { it.copy(isPlaying = false, fillActive = false) }
        audioEngine.setPlaying(false)
    }

    fun selectPreset(preset: DrumPreset, autoPlay: Boolean = true) {
        val keepKit = _uiState.value.lockKitOnPresetChange
        _uiState.update { state ->
            state.copy(
                activePreset = preset,
                bpm = preset.bpm,
                swing = preset.swing,
                selectedSlot = PatternSlot.MAIN_A,
                songMode = false,
                isPlaying = if (autoPlay) true else state.isPlaying
            )
        }
        pushSequencerSnapshot(_uiState.value)

        if (!keepKit && _uiState.value.soundSource is SoundSource.BuiltIn) {
            selectBuiltInKit(preset.recommendedKit)
        }
        if (autoPlay) {
            audioEngine.setPlaying(true, restart = true)
        }
    }

    fun selectSlot(slot: PatternSlot) {
        if (slot == PatternSlot.FILL && _uiState.value.isPlaying) {
            audioEngine.requestFill()
            return
        }
        _uiState.update { it.copy(selectedSlot = slot, playingSlot = slot) }
        pushSequencerSnapshot(_uiState.value)
    }

    fun triggerFill() {
        if (_uiState.value.isPlaying) {
            audioEngine.requestFill()
        } else {
            _uiState.update { it.copy(selectedSlot = PatternSlot.FILL) }
            pushSequencerSnapshot(_uiState.value)
        }
    }

    fun toggleAutoFill() {
        _uiState.update { it.copy(autoFillEvery4Bars = !it.autoFillEvery4Bars) }
        pushSequencerSnapshot(_uiState.value)
    }

    fun toggleMetronome() {
        _uiState.update { it.copy(metronomeEnabled = !it.metronomeEnabled) }
        pushSequencerSnapshot(_uiState.value)
    }

    fun toggleLiveRecord() {
        _uiState.update { it.copy(liveRecordEnabled = !it.liveRecordEnabled) }
    }

    fun toggleLockKit() {
        _uiState.update { it.copy(lockKitOnPresetChange = !it.lockKitOnPresetChange) }
    }

    fun setBpm(bpm: Int) {
        val clamped = bpm.coerceIn(40, 240)
        _uiState.update { it.copy(bpm = clamped) }
        pushSequencerSnapshot(_uiState.value)
    }

    fun tapTempo() {
        val now = System.currentTimeMillis()
        if (tapTimestamps.isNotEmpty() && now - tapTimestamps.last() > 2000L) {
            tapTimestamps.clear()
        }
        tapTimestamps.addLast(now)
        if (tapTimestamps.size > 5) tapTimestamps.removeFirst()
        if (tapTimestamps.size >= 2) {
            val intervals = tapTimestamps.zipWithNext { a, b -> b - a }
            val avgMs = intervals.average()
            if (avgMs > 0) {
                val bpm = (60_000.0 / avgMs).toInt().coerceIn(40, 240)
                setBpm(bpm)
            }
        }
    }

    fun setSwing(swing: Float) {
        val clamped = swing.coerceIn(0f, 0.70f)
        _uiState.update { it.copy(swing = clamped) }
        pushSequencerSnapshot(_uiState.value)
    }

    fun triggerPad(instrument: DrumInstrument, velocity: Float = 0.92f) {
        audioEngine.triggerPad(instrument, velocity)
        val state = _uiState.value
        if (state.liveRecordEnabled && state.isPlaying && !state.songMode) {
            val step = audioEngine.currentStep()
            if (step >= 0) {
                val velLevel = when {
                    velocity >= 0.85f -> StepVelocity.ACCENT
                    velocity >= 0.55f -> StepVelocity.MEDIUM
                    else -> StepVelocity.SOFT
                }
                setStepVelocity(instrument, step, velLevel)
            }
        }
    }

    fun cycleStep(instrument: DrumInstrument, step: Int) {
        val state = _uiState.value
        val slot = state.selectedSlot
        val pattern = state.activePreset.patternFor(slot)
        val cur = pattern.velocityAt(instrument, step)
        val next = StepVelocity.next(cur)
        setStepVelocity(instrument, step, next)
        if (next > 0 && !state.isPlaying) {
            audioEngine.triggerPad(instrument, StepVelocity.toGain(next))
        }
    }

    fun setStepVelocity(instrument: DrumInstrument, step: Int, velocity: Int) {
        _uiState.update { state ->
            val slot = state.selectedSlot
            val updatedPattern = state.activePreset.patternFor(slot).withVelocity(instrument, step, velocity)
            val updatedPreset = state.activePreset.withPattern(slot, updatedPattern)
            state.copy(activePreset = updatedPreset)
        }
        pushSequencerSnapshot(_uiState.value)
    }

    fun clearActiveSlot() {
        _uiState.update { state ->
            val slot = state.selectedSlot
            val count = state.activePreset.patternFor(slot).stepCount
            val updated = state.activePreset.withPattern(slot, StepPattern.empty(count))
            state.copy(activePreset = updated)
        }
        pushSequencerSnapshot(_uiState.value)
    }

    fun toggleBarLength() {
        _uiState.update { state ->
            val ts = state.activePreset.timeSignature
            val singleBar = ts.stepsPerBar
            val doubleBar = (singleBar * 2).coerceAtMost(32)
            val curSteps = state.activePreset.patternFor(state.selectedSlot).stepCount
            val targetSteps = if (curSteps <= singleBar) doubleBar else singleBar
            val updated = state.activePreset.copy(
                mainA = state.activePreset.mainA.resized(targetSteps),
                varB = state.activePreset.varB.resized(targetSteps),
                fill = state.activePreset.fill.resized(targetSteps)
            )
            state.copy(activePreset = updated)
        }
        pushSequencerSnapshot(_uiState.value)
    }

    fun setTimeSignature(ts: TimeSignature) {
        _uiState.update { state ->
            val updated = state.activePreset.copy(
                timeSignature = ts,
                mainA = state.activePreset.mainA.resized(ts.stepsPerBar),
                varB = state.activePreset.varB.resized(ts.stepsPerBar),
                fill = state.activePreset.fill.resized(ts.stepsPerBar)
            )
            state.copy(activePreset = updated)
        }
        pushSequencerSnapshot(_uiState.value)
    }

    fun saveCurrentAsCustomPreset(name: String) {
        val cleanName = name.trim().ifBlank { "My Groove" }
        val state = _uiState.value
        val newPreset = state.activePreset.copy(
            id = "custom_${UUID.randomUUID().toString().take(8)}",
            name = cleanName,
            genre = DrumGenre.CUSTOM,
            bpm = state.bpm,
            swing = state.swing,
            isCustom = true,
            description = "Custom saved pattern (${state.activePreset.timeSignature.label})"
        )
        viewModelScope.launch(Dispatchers.IO) {
            val existingCustom = _uiState.value.allPresets.filter { it.isCustom }
            val updatedCustom = listOf(newPreset) + existingCustom
            repo.saveCustomPresets(updatedCustom)
            _uiState.update { s ->
                s.copy(
                    allPresets = updatedCustom + PresetLibrary.builtInPresets,
                    activePreset = newPreset,
                    statusMessage = "Saved \"$cleanName\" to My Presets"
                )
            }
            pushSequencerSnapshot(_uiState.value)
        }
    }

    fun deleteCustomPreset(preset: DrumPreset) {
        if (!preset.isCustom) return
        viewModelScope.launch(Dispatchers.IO) {
            val remainingCustom = _uiState.value.allPresets.filter { it.isCustom && it.id != preset.id }
            repo.saveCustomPresets(remainingCustom)
            _uiState.update { s ->
                s.copy(
                    allPresets = remainingCustom + PresetLibrary.builtInPresets,
                    statusMessage = "Deleted \"${preset.name}\""
                )
            }
        }
    }

    // ==================== KIT & SOUNDFONT ====================

    fun selectBuiltInKit(kit: BuiltInKit) {
        val cached = kitCache[kit]
        if (cached != null) {
            audioEngine.setKitSamples(cached)
            _uiState.update { it.copy(soundSource = SoundSource.BuiltIn(kit)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSound = true) }
            val samples = withContext(Dispatchers.Default) {
                BuiltInKitSynthesizer.renderKit(kit)
            }
            kitCache[kit] = samples
            audioEngine.setKitSamples(samples)
            _uiState.update {
                it.copy(
                    soundSource = SoundSource.BuiltIn(kit),
                    isLoadingSound = false
                )
            }
        }
    }

    fun importSoundFont(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSound = true) }
            val result = withContext(Dispatchers.IO) {
                repo.importSoundFont(uri)
            }
            result.onSuccess { loaded ->
                val updatedList = withContext(Dispatchers.IO) { repo.listLoadedSoundFonts() }
                _uiState.update {
                    it.copy(
                        loadedSoundFonts = updatedList,
                        isLoadingSound = false,
                        statusMessage = "Loaded SoundFont: ${loaded.displayName} (${loaded.presets.size} presets)"
                    )
                }
                val firstPreset = loaded.presets.firstOrNull()
                if (firstPreset != null) {
                    selectSoundFontPreset(loaded, firstPreset.bank, firstPreset.preset, firstPreset.name)
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingSound = false,
                        statusMessage = err.message ?: "Failed to load SoundFont (.sf2)"
                    )
                }
            }
        }
    }

    fun selectSoundFontPreset(sf: LoadedSoundFont, bank: Int, preset: Int, presetName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSound = true) }
            val samples = withContext(Dispatchers.IO) {
                SoundFont2Parser.loadKitSamples(repo.soundFontFile(sf.fileName), bank, preset)
            }
            audioEngine.setKitSamples(samples)
            _uiState.update {
                it.copy(
                    soundSource = SoundSource.SoundFont(
                        fileName = sf.fileName,
                        displayName = sf.displayName,
                        bank = bank,
                        preset = preset,
                        presetName = presetName
                    ),
                    isLoadingSound = false,
                    statusMessage = "Active SF2: $presetName"
                )
            }
        }
    }

    fun deleteSoundFont(sf: LoadedSoundFont) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteSoundFont(sf.fileName)
            val updated = repo.listLoadedSoundFonts()
            _uiState.update { s ->
                val resetSource = (s.soundSource as? SoundSource.SoundFont)?.fileName == sf.fileName
                s.copy(
                    loadedSoundFonts = updated,
                    statusMessage = "Removed ${sf.displayName}"
                ).also {
                    if (resetSource) selectBuiltInKit(BuiltInKit.ACOUSTIC)
                }
            }
        }
    }

    // ==================== MIXER ====================

    fun updateChannelMix(instrument: DrumInstrument, transform: (ChannelMix) -> ChannelMix) {
        _uiState.update { state ->
            val current = state.mixer[instrument] ?: ChannelMix()
            val updatedMap = state.mixer.toMutableMap()
            updatedMap[instrument] = transform(current)
            audioEngine.setMixerState(updatedMap)
            state.copy(mixer = updatedMap)
        }
    }

    fun resetMixer() {
        val fresh = DrumInstrument.ALL.associateWith { ChannelMix() }
        audioEngine.setMixerState(fresh)
        _uiState.update { it.copy(mixer = fresh, statusMessage = "Mixer reset to defaults") }
    }

    // ==================== SONG MODE ====================

    fun toggleSongMode(playImmediately: Boolean = false) {
        val nextMode = !_uiState.value.songMode
        _uiState.update { state ->
            state.copy(
                songMode = nextMode,
                isPlaying = if (playImmediately && nextMode) true else state.isPlaying
            )
        }
        pushSequencerSnapshot(_uiState.value)
        if (playImmediately && nextMode) {
            audioEngine.setPlaying(true, restart = true)
        }
    }

    fun playSong() {
        if (_uiState.value.songSections.isEmpty()) return
        _uiState.update { it.copy(songMode = true, isPlaying = true) }
        pushSequencerSnapshot(_uiState.value)
        audioEngine.setPlaying(true, restart = true)
    }

    fun addSongSection(preset: DrumPreset, slot: PatternSlot = PatternSlot.MAIN_A, bars: Int = 4) {
        val newSec = SongSection(
            id = UUID.randomUUID().toString().take(8),
            presetId = preset.id,
            presetName = preset.name,
            slot = slot,
            bars = bars,
            playFillOnLastBar = true
        )
        updateSongSections(_uiState.value.songSections + newSec)
    }

    fun updateSongSection(sectionId: String, transform: (SongSection) -> SongSection) {
        val updated = _uiState.value.songSections.map { if (it.id == sectionId) transform(it) else it }
        updateSongSections(updated)
    }

    fun removeSongSection(sectionId: String) {
        updateSongSections(_uiState.value.songSections.filterNot { it.id == sectionId })
    }

    fun moveSongSection(index: Int, delta: Int) {
        val list = _uiState.value.songSections.toMutableList()
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return
        val item = list.removeAt(index)
        list.add(target, item)
        updateSongSections(list)
    }

    fun exportActivePatternWav(uri: Uri) {
        val state = _uiState.value
        val kitSamples = audioEngine.currentKitSamples()
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    AudioMidiExporter.exportPatternToWav(
                        output = out,
                        preset = state.activePreset,
                        slot = state.selectedSlot,
                        bpm = state.bpm,
                        swing = state.swing,
                        bars = 2,
                        kitSamples = kitSamples,
                        mixer = state.mixer
                    )
                } ?: error("Could not open output stream for WAV export.")
            }
            _uiState.update {
                it.copy(
                    statusMessage = result.fold(
                        onSuccess = { "Exported WAV: ${state.activePreset.name} (${state.selectedSlot.label})" },
                        onFailure = { err -> err.message ?: "WAV export failed" }
                    )
                )
            }
        }
    }

    fun exportActivePatternMidi(uri: Uri) {
        val state = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    AudioMidiExporter.exportPatternToMidi(
                        output = out,
                        preset = state.activePreset,
                        slot = state.selectedSlot,
                        bpm = state.bpm,
                        swing = state.swing,
                        bars = 2
                    )
                } ?: error("Could not open output stream for MIDI export.")
            }
            _uiState.update {
                it.copy(
                    statusMessage = result.fold(
                        onSuccess = { "Exported MIDI: ${state.activePreset.name} (${state.selectedSlot.label})" },
                        onFailure = { err -> err.message ?: "MIDI export failed" }
                    )
                )
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private fun updateSongSections(sections: List<SongSection>) {
        _uiState.update { it.copy(songSections = sections) }
        pushSequencerSnapshot(_uiState.value)
        viewModelScope.launch(Dispatchers.IO) {
            repo.saveSongSections(sections)
        }
    }

    private fun pushSequencerSnapshot(state: DrumMachineUiState) {
        val byId = state.allPresets.associateBy { it.id }.toMutableMap()
        byId[state.activePreset.id] = state.activePreset
        audioEngine.updateSequencerState(
            DrumAudioEngine.SequencerSnapshot(
                preset = state.activePreset,
                baseSlot = if (state.selectedSlot == PatternSlot.FILL) PatternSlot.MAIN_A else state.selectedSlot,
                bpm = state.bpm,
                swing = state.swing,
                metronomeEnabled = state.metronomeEnabled,
                autoFillEvery4Bars = state.autoFillEvery4Bars,
                songMode = state.songMode,
                songSections = state.songSections,
                songPresetsById = byId
            )
        )
    }

    private fun defaultDemoSong(): List<SongSection> {
        val p1 = PresetLibrary.builtInPresets.first()
        return listOf(
            SongSection("demo1", p1.id, p1.name, PatternSlot.MAIN_A, bars = 4, playFillOnLastBar = true),
            SongSection("demo2", p1.id, p1.name, PatternSlot.VAR_B, bars = 4, playFillOnLastBar = true)
        )
    }
}
