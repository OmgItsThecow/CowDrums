package com.cowdrums.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cowdrums.audio.AndroidMidiBluetoothManager
import com.cowdrums.audio.AudioMidiExporter
import com.cowdrums.audio.BuiltInKitSynthesizer
import com.cowdrums.audio.DrumAudioEngine
import com.cowdrums.audio.SoundFont2Parser
import com.cowdrums.audio.WavSampleLoader
import com.cowdrums.data.CowDrumsRepository
import com.cowdrums.data.PresetLibrary
import com.cowdrums.model.BassPreset
import com.cowdrums.model.BuiltInKit
import com.cowdrums.model.ChannelMix
import com.cowdrums.model.DrumGenre
import com.cowdrums.model.DrumInstrument
import com.cowdrums.model.DrumPreset
import com.cowdrums.model.GmDrumCatalog
import com.cowdrums.model.LoadedSoundFont
import com.cowdrums.model.MidiDeviceEntry
import com.cowdrums.model.NoteRepeatRate
import com.cowdrums.model.PadConfig
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
import kotlin.random.Random

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
    val soundSource: SoundSource = SoundSource.BuiltIn(BuiltInKit.ROOM),
    val loadedSoundFonts: List<LoadedSoundFont> = emptyList(),
    val isLoadingSound: Boolean = false,
    val bassPreset: BassPreset = BassPreset.ACOUSTIC_BASS,
    val bassOctave: Int = 1,
    val bassVolume: Float = 0.80f,
    val mixer: Map<DrumInstrument, ChannelMix> = DrumInstrument.ALL.associateWith { ChannelMix() },
    val padConfigs: Map<DrumInstrument, PadConfig> = DrumInstrument.ALL.associateWith {
        PadConfig(chokeGroup = it.chokeGroup, midiInputNote = it.gmNote)
    },
    val midiLearnInstrument: DrumInstrument? = null,
    val externalPadFlash: Pair<DrumInstrument, Long>? = null,
    val connectedMidiDevices: List<MidiDeviceEntry> = emptyList(),
    val bleMidiDevices: List<MidiDeviceEntry> = emptyList(),
    val isScanningBleMidi: Boolean = false,
    val lastMidiActivity: String? = null,
    val songMode: Boolean = false,
    val songSections: List<SongSection> = emptyList(),
    val activeSongSectionIndex: Int = 0,
    val statusMessage: String? = null
)

class DrumMachineViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = CowDrumsRepository(application.applicationContext)
    private val kitCache = HashMap<BuiltInKit, Map<DrumInstrument, FloatArray>>()
    private val bassCache = HashMap<BassPreset, Map<Int, FloatArray>>()

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

    private val midiBluetoothManager = AndroidMidiBluetoothManager(
        context = application.applicationContext,
        onNoteOn = { ch, note, vel -> onExternalMidiNoteOn(ch, note, vel) },
        onConnectedDevicesChanged = { list ->
            _uiState.update { it.copy(connectedMidiDevices = list) }
        },
        onBleDevicesChanged = { list ->
            _uiState.update { it.copy(bleMidiDevices = list) }
        },
        onScanningChanged = { scanning ->
            _uiState.update { it.copy(isScanningBleMidi = scanning) }
        },
        onStatus = { msg ->
            _uiState.update { it.copy(statusMessage = msg) }
        }
    )

    init {
        val defaultKit = BuiltInKit.ROOM
        val defaultBass = BassPreset.ACOUSTIC_BASS
        audioEngine.setMixerState(_uiState.value.mixer)
        audioEngine.setPadChokeGroups(_uiState.value.padConfigs.mapValues { it.value.chokeGroup })
        audioEngine.setBassVolume(_uiState.value.bassVolume)
        pushSequencerSnapshot(_uiState.value)
        audioEngine.startEngine()
        midiBluetoothManager.start()

        viewModelScope.launch(Dispatchers.IO) {
            val realSamples = loadBuiltInKitSamples(defaultKit)
            kitCache[defaultKit] = realSamples
            audioEngine.setKitSamples(realSamples)

            val realBass = loadBassPresetSamples(defaultBass)
            bassCache[defaultBass] = realBass
            audioEngine.setBassSamples(realBass)

            val customPresets = repo.loadCustomPresets()
            val savedSong = repo.loadSongSections().ifEmpty { defaultDemoSong() }
            val sf2s = repo.listLoadedSoundFonts()
            _uiState.update { state ->
                val merged = customPresets + PresetLibrary.builtInPresets
                state.copy(
                    allPresets = merged,
                    soundSource = SoundSource.BuiltIn(defaultKit),
                    songSections = savedSong,
                    loadedSoundFonts = sf2s
                )
            }
            pushSequencerSnapshot(_uiState.value)
        }
    }

    private fun loadBuiltInKitSamples(kit: BuiltInKit): Map<DrumInstrument, FloatArray> {
        val sf2Bytes = repo.loadBundledSf2Bytes()
        return if (sf2Bytes != null) {
            SoundFont2Parser.loadKitSamples(
                bytes = sf2Bytes,
                bank = 128,
                preset = kit.sf2Preset,
                applyLoFi = kit.applyLoFiFilter
            )
        } else {
            BuiltInKitSynthesizer.renderKit(kit)
        }
    }

    private fun loadBassPresetSamples(preset: BassPreset): Map<Int, FloatArray> {
        val sf2Bytes = repo.loadBundledSf2Bytes() ?: return emptyMap()
        return SoundFont2Parser.loadBassNotes(
            bytes = sf2Bytes,
            bank = preset.bank,
            preset = preset.gmProgram
        )
    }

    override fun onCleared() {
        super.onCleared()
        midiBluetoothManager.close()
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

    fun stepBuiltInKit(delta: Int) {
        val kits = BuiltInKit.entries
        val curIdx = (uiState.value.soundSource as? SoundSource.BuiltIn)?.kit?.ordinal ?: 0
        val nextIdx = (curIdx + delta + kits.size) % kits.size
        selectBuiltInKit(kits[nextIdx])
    }

    private fun clearPadSoundOverrides(configs: Map<DrumInstrument, PadConfig>): Map<DrumInstrument, PadConfig> =
        configs.mapValues { (_, cfg) ->
            cfg.copy(soundGmNote = null, kitOverride = null, customWavName = null)
        }

    fun selectBuiltInKit(kit: BuiltInKit) {
        val cached = kitCache[kit]
        if (cached != null) {
            audioEngine.setKitSamples(cached)
            _uiState.update {
                it.copy(
                    soundSource = SoundSource.BuiltIn(kit),
                    padConfigs = clearPadSoundOverrides(it.padConfigs)
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSound = true) }
            val samples = withContext(Dispatchers.IO) {
                loadBuiltInKitSamples(kit)
            }
            kitCache[kit] = samples
            audioEngine.setKitSamples(samples)
            _uiState.update {
                it.copy(
                    soundSource = SoundSource.BuiltIn(kit),
                    padConfigs = clearPadSoundOverrides(it.padConfigs),
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
                    padConfigs = clearPadSoundOverrides(it.padConfigs),
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

    // ==================== BASS ====================

    fun selectBassPreset(preset: BassPreset) {
        val cached = bassCache[preset]
        if (cached != null) {
            audioEngine.setBassSamples(cached)
            _uiState.update { it.copy(bassPreset = preset) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSound = true, bassPreset = preset) }
            val samples = withContext(Dispatchers.IO) { loadBassPresetSamples(preset) }
            bassCache[preset] = samples
            audioEngine.setBassSamples(samples)
            _uiState.update { it.copy(isLoadingSound = false) }
        }
    }

    fun stepBassPreset(delta: Int) {
        val all = BassPreset.entries
        val next = (uiState.value.bassPreset.ordinal + delta + all.size) % all.size
        selectBassPreset(all[next])
    }

    fun stepBassOctave(delta: Int) {
        _uiState.update { it.copy(bassOctave = (it.bassOctave + delta).coerceIn(1, 3)) }
    }

    fun setBassVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1.25f)
        audioEngine.setBassVolume(clamped)
        _uiState.update { it.copy(bassVolume = clamped) }
    }

    fun triggerBassNote(midiNote: Int) {
        audioEngine.triggerBassNote(midiNote, 0.92f)
        val state = _uiState.value
        if (state.liveRecordEnabled && state.isPlaying && !state.songMode) {
            val step = audioEngine.currentStep()
            if (step >= 0) {
                _uiState.update { s ->
                    val slot = s.selectedSlot
                    val updatedPattern = s.activePreset.patternFor(slot).withBassNote(step, midiNote)
                    s.copy(activePreset = s.activePreset.withPattern(slot, updatedPattern))
                }
                pushSequencerSnapshot(_uiState.value)
            }
        }
    }

    fun clearBassPattern() {
        _uiState.update { state ->
            val slot = state.selectedSlot
            val cleared = state.activePreset.patternFor(slot).clearBass()
            state.copy(activePreset = state.activePreset.withPattern(slot, cleared))
        }
        pushSequencerSnapshot(_uiState.value)
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

    // ==================== PAD CONTEXT MENU (1, 3, 4) ====================

    fun setPadGmSound(instrument: DrumInstrument, gmNote: Int) {
        val state = _uiState.value
        val curCfg = state.padConfigs[instrument] ?: PadConfig(chokeGroup = instrument.chokeGroup, midiInputNote = instrument.gmNote)
        viewModelScope.launch {
            val pcm = withContext(Dispatchers.IO) {
                val overrideKit = curCfg.kitOverride
                when {
                    overrideKit != null -> {
                        val sf2Bytes = repo.loadBundledSf2Bytes() ?: return@withContext null
                        SoundFont2Parser.loadSingleNoteSample(
                            bytes = sf2Bytes,
                            bank = 128,
                            preset = overrideKit.sf2Preset,
                            midiNote = gmNote,
                            applyLoFi = overrideKit.applyLoFiFilter
                        )
                    }
                    state.soundSource is SoundSource.BuiltIn -> {
                        val sf2Bytes = repo.loadBundledSf2Bytes() ?: return@withContext null
                        SoundFont2Parser.loadSingleNoteSample(
                            bytes = sf2Bytes,
                            bank = 128,
                            preset = state.soundSource.kit.sf2Preset,
                            midiNote = gmNote,
                            applyLoFi = state.soundSource.kit.applyLoFiFilter
                        ) ?: SoundFont2Parser.loadSingleNoteSample(sf2Bytes, 128, 0, gmNote)
                    }
                    state.soundSource is SoundSource.SoundFont -> {
                        val file = repo.soundFontFile(state.soundSource.fileName)
                        if (file.exists()) {
                            SoundFont2Parser.loadSingleNoteSample(
                                bytes = file.readBytes(),
                                bank = state.soundSource.bank,
                                preset = state.soundSource.preset,
                                midiNote = gmNote
                            )
                        } else null
                    }
                    else -> null
                }
            }
            if (pcm != null) {
                audioEngine.setSinglePadSample(instrument, pcm)
                audioEngine.triggerPad(instrument, 0.92f)
                _uiState.update { s ->
                    val next = s.padConfigs.toMutableMap()
                    val existing = next[instrument] ?: PadConfig(chokeGroup = instrument.chokeGroup, midiInputNote = instrument.gmNote)
                    next[instrument] = existing.copy(soundGmNote = gmNote, customWavName = null)
                    s.copy(
                        padConfigs = next,
                        statusMessage = "${instrument.displayName} → ${GmDrumCatalog.nameFor(gmNote)} (GM $gmNote)"
                    )
                }
            } else {
                _uiState.update { it.copy(statusMessage = "GM Note $gmNote not available in current SoundFont") }
            }
        }
    }

    fun setPadKitOverride(instrument: DrumInstrument, kit: BuiltInKit) {
        val curCfg = _uiState.value.padConfigs[instrument] ?: PadConfig(chokeGroup = instrument.chokeGroup, midiInputNote = instrument.gmNote)
        val targetNote = curCfg.soundGmNote ?: instrument.gmNote
        viewModelScope.launch {
            val pcm = withContext(Dispatchers.IO) {
                val sf2Bytes = repo.loadBundledSf2Bytes() ?: return@withContext null
                SoundFont2Parser.loadSingleNoteSample(
                    bytes = sf2Bytes,
                    bank = 128,
                    preset = kit.sf2Preset,
                    midiNote = targetNote,
                    applyLoFi = kit.applyLoFiFilter
                ) ?: loadBuiltInKitSamples(kit)[instrument]
            }
            if (pcm != null) {
                audioEngine.setSinglePadSample(instrument, pcm)
                audioEngine.triggerPad(instrument, 0.92f)
                _uiState.update { s ->
                    val next = s.padConfigs.toMutableMap()
                    val existing = next[instrument] ?: PadConfig(chokeGroup = instrument.chokeGroup, midiInputNote = instrument.gmNote)
                    next[instrument] = existing.copy(kitOverride = kit, customWavName = null)
                    s.copy(
                        padConfigs = next,
                        statusMessage = "${instrument.displayName} kit → ${kit.displayName}"
                    )
                }
            }
        }
    }

    fun loadCustomWavForPad(instrument: DrumInstrument, uri: Uri) {
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val (pcm, displayName) = withContext(Dispatchers.IO) {
                val name = runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull() ?: "Custom Sample"
                val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                val decoded = bytes?.let { WavSampleLoader.decodeWavToMono44k(it) }
                decoded to name.substringBeforeLast('.')
            }
            if (pcm != null) {
                audioEngine.setSinglePadSample(instrument, pcm)
                audioEngine.triggerPad(instrument, 0.92f)
                _uiState.update { s ->
                    val next = s.padConfigs.toMutableMap()
                    val existing = next[instrument] ?: PadConfig(chokeGroup = instrument.chokeGroup, midiInputNote = instrument.gmNote)
                    next[instrument] = existing.copy(
                        customWavName = displayName,
                        soundGmNote = null,
                        kitOverride = null
                    )
                    s.copy(
                        padConfigs = next,
                        statusMessage = "Loaded WAV \"$displayName\" onto ${instrument.displayName}"
                    )
                }
            } else {
                _uiState.update { it.copy(statusMessage = "Unsupported WAV file selected") }
            }
        }
    }

    fun resetPadSound(instrument: DrumInstrument) {
        val src = _uiState.value.soundSource
        viewModelScope.launch {
            val pcm = withContext(Dispatchers.IO) {
                when (src) {
                    is SoundSource.BuiltIn -> {
                        val map = kitCache[src.kit] ?: loadBuiltInKitSamples(src.kit).also { kitCache[src.kit] = it }
                        map[instrument]
                    }
                    is SoundSource.SoundFont -> {
                        SoundFont2Parser.loadKitSamples(repo.soundFontFile(src.fileName), src.bank, src.preset)[instrument]
                    }
                }
            }
            if (pcm != null) {
                audioEngine.setSinglePadSample(instrument, pcm)
                audioEngine.triggerPad(instrument, 0.90f)
            }
            _uiState.update { s ->
                val next = s.padConfigs.toMutableMap()
                val existing = next[instrument] ?: PadConfig(chokeGroup = instrument.chokeGroup, midiInputNote = instrument.gmNote)
                next[instrument] = existing.copy(soundGmNote = null, kitOverride = null, customWavName = null)
                s.copy(
                    padConfigs = next,
                    statusMessage = "Reset ${instrument.displayName} to kit default"
                )
            }
        }
    }

    fun setPadChokeGroup(instrument: DrumInstrument, group: Int) {
        val clamped = group.coerceIn(0, 3)
        _uiState.update { s ->
            val next = s.padConfigs.toMutableMap()
            val existing = next[instrument] ?: PadConfig(chokeGroup = instrument.chokeGroup, midiInputNote = instrument.gmNote)
            next[instrument] = existing.copy(chokeGroup = clamped)
            s.copy(padConfigs = next)
        }
        audioEngine.setPadChokeGroups(_uiState.value.padConfigs.mapValues { it.value.chokeGroup })
    }

    fun startMidiLearn(instrument: DrumInstrument) {
        _uiState.update {
            it.copy(
                midiLearnInstrument = instrument,
                statusMessage = "MIDI Learn: press any pad/key on your MIDI controller for ${instrument.displayName}..."
            )
        }
    }

    fun cancelMidiLearn() {
        _uiState.update { it.copy(midiLearnInstrument = null) }
    }

    fun setPadMidiInputNote(instrument: DrumInstrument, midiNote: Int) {
        val clamped = midiNote.coerceIn(0, 127)
        _uiState.update { s ->
            val next = s.padConfigs.toMutableMap()
            val existing = next[instrument] ?: PadConfig(chokeGroup = instrument.chokeGroup, midiInputNote = instrument.gmNote)
            next[instrument] = existing.copy(midiInputNote = clamped)
            s.copy(
                padConfigs = next,
                midiLearnInstrument = if (s.midiLearnInstrument == instrument) null else s.midiLearnInstrument,
                statusMessage = "${instrument.displayName} MIDI input → ${GmDrumCatalog.midiNoteLabel(clamped)}"
            )
        }
    }

    fun setPadNoteRepeat(instrument: DrumInstrument, rate: NoteRepeatRate) {
        _uiState.update { s ->
            val next = s.padConfigs.toMutableMap()
            val existing = next[instrument] ?: PadConfig(chokeGroup = instrument.chokeGroup, midiInputNote = instrument.gmNote)
            next[instrument] = existing.copy(noteRepeat = rate)
            s.copy(padConfigs = next)
        }
    }

    fun clearPadInActiveSlot(instrument: DrumInstrument) {
        _uiState.update { state ->
            val slot = state.selectedSlot
            val pat = state.activePreset.patternFor(slot)
            val updatedRows = pat.rows.toMutableMap().apply { remove(instrument.name) }
            val updatedPreset = state.activePreset.withPattern(slot, pat.copy(rows = updatedRows))
            state.copy(
                activePreset = updatedPreset,
                statusMessage = "Cleared ${instrument.displayName} from ${slot.label}"
            )
        }
        pushSequencerSnapshot(_uiState.value)
    }

    fun fillPadEveryNSteps(instrument: DrumInstrument, everyN: Int) {
        val stepStride = everyN.coerceAtLeast(1)
        _uiState.update { state ->
            val slot = state.selectedSlot
            val pat = state.activePreset.patternFor(slot)
            val list = MutableList(pat.stepCount) { step ->
                if (step % stepStride == 0) {
                    if (step % 4 == 0) StepVelocity.ACCENT else StepVelocity.MEDIUM
                } else {
                    StepVelocity.OFF
                }
            }
            val updatedRows = pat.rows.toMutableMap().apply { put(instrument.name, list) }
            val updatedPreset = state.activePreset.withPattern(slot, pat.copy(rows = updatedRows))
            state.copy(
                activePreset = updatedPreset,
                statusMessage = "Filled ${instrument.displayName} every $stepStride step(s) in ${slot.label}"
            )
        }
        pushSequencerSnapshot(_uiState.value)
    }

    fun humanizePadInActiveSlot(instrument: DrumInstrument) {
        _uiState.update { state ->
            val slot = state.selectedSlot
            val pat = state.activePreset.patternFor(slot)
            val existing = pat.rows[instrument.name] ?: return@update state.copy(
                statusMessage = "No ${instrument.displayName} hits to humanize in ${slot.label}"
            )
            val humanized = existing.mapIndexed { idx, v ->
                if (v <= 0) 0
                else if (idx % 4 == 0) if (Random.nextFloat() < 0.72f) StepVelocity.ACCENT else StepVelocity.MEDIUM
                else when (Random.nextInt(3)) {
                    0 -> StepVelocity.SOFT
                    1 -> StepVelocity.MEDIUM
                    else -> if (idx % 2 == 0) StepVelocity.MEDIUM else StepVelocity.SOFT
                }
            }
            val updatedRows = pat.rows.toMutableMap().apply { put(instrument.name, humanized) }
            val updatedPreset = state.activePreset.withPattern(slot, pat.copy(rows = updatedRows))
            state.copy(
                activePreset = updatedPreset,
                statusMessage = "Humanized ${instrument.displayName} velocity in ${slot.label}"
            )
        }
        pushSequencerSnapshot(_uiState.value)
    }

    // ==================== BLUETOOTH LE & USB MIDI ====================

    fun startBluetoothMidiScan() = midiBluetoothManager.startBluetoothMidiScan()

    fun stopBluetoothMidiScan() = midiBluetoothManager.stopBluetoothMidiScan()

    fun refreshMidiDevices() = midiBluetoothManager.refreshConnectedDevices()

    fun connectBluetoothMidiDevice(address: String) = midiBluetoothManager.connectBluetoothDevice(address)

    fun disconnectMidiDevice(id: String) = midiBluetoothManager.disconnectDevice(id)

    private fun onExternalMidiNoteOn(channel: Int, note: Int, velocity: Float) {
        val state = _uiState.value
        val velInt = (velocity * 127f).toInt().coerceIn(1, 127)
        val activity = "Ch ${channel + 1} · ${GmDrumCatalog.midiNoteLabel(note)} · Vel $velInt"

        val learnTarget = state.midiLearnInstrument
        if (learnTarget != null) {
            setPadMidiInputNote(learnTarget, note)
            triggerPad(learnTarget, velocity)
            _uiState.update {
                it.copy(
                    lastMidiActivity = activity,
                    externalPadFlash = learnTarget to System.currentTimeMillis()
                )
            }
            return
        }

        val matchedPads = DrumInstrument.ALL.filter { inst ->
            val mapped = state.padConfigs[inst]?.midiInputNote ?: inst.gmNote
            mapped == note
        }
        if (matchedPads.isNotEmpty()) {
            for (inst in matchedPads) {
                triggerPad(inst, velocity)
            }
            _uiState.update {
                it.copy(
                    lastMidiActivity = activity,
                    externalPadFlash = matchedPads.first() to System.currentTimeMillis()
                )
            }
            return
        }

        val alias = DrumInstrument.ALL.firstOrNull { note in it.fallbackGmNotes }
        if (alias != null) {
            triggerPad(alias, velocity)
            _uiState.update {
                it.copy(
                    lastMidiActivity = activity,
                    externalPadFlash = alias to System.currentTimeMillis()
                )
            }
            return
        }

        if (note in 24..59) {
            triggerBassNote(note)
        }
        _uiState.update { it.copy(lastMidiActivity = activity) }
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
