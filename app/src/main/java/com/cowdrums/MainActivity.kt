package com.cowdrums

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cowdrums.ui.components.KitSoundFontSheet
import com.cowdrums.ui.components.TransportBar
import com.cowdrums.ui.screens.MixerScreen
import com.cowdrums.ui.screens.PerformScreen
import com.cowdrums.ui.screens.PresetsScreen
import com.cowdrums.ui.screens.SequencerScreen
import com.cowdrums.ui.screens.SongScreen
import com.cowdrums.ui.theme.CowColors
import com.cowdrums.ui.theme.CowDrumsTheme
import com.cowdrums.viewmodel.DrumMachineViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            CowDrumsTheme {
                CowDrumsApp()
            }
        }
    }
}

private enum class CowMode(val title: String, val icon: ImageVector) {
    PRESETS("Presets", Icons.Default.LibraryMusic),
    PADS("Pads", Icons.Default.Apps),
    SEQUENCER("Sequencer", Icons.Default.GridOn),
    SONG("Song", Icons.AutoMirrored.Filled.QueueMusic)
}

@Composable
fun CowDrumsApp(vm: DrumMachineViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var activeMode by remember { mutableStateOf(CowMode.PRESETS) }
    var showMixer by remember { mutableStateOf(false) }
    var showKitSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val sf2PickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importSoundFont(uri)
    }

    val wavExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        if (uri != null) vm.exportActivePatternWav(uri)
    }

    val midiExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/midi")
    ) { uri ->
        if (uri != null) vm.exportActivePatternMidi(uri)
    }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            vm.clearStatusMessage()
        }
    }

    val headerTitle = when {
        showMixer -> "Mixer"
        activeMode == CowMode.SONG -> "Song"
        activeMode == CowMode.PRESETS -> "Presets"
        else -> state.activePreset.name
    }

    Scaffold(
        containerColor = CowColors.BgDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(CowColors.BgDark)) {
                TransportBar(
                    title = headerTitle,
                    showBackArrow = showMixer || activeMode != CowMode.PRESETS,
                    onBackClick = {
                        if (showMixer) showMixer = false else activeMode = CowMode.PRESETS
                    },
                    state = state,
                    onTogglePlay = vm::togglePlay,
                    onBpmChange = vm::setBpm,
                    onTapTempo = vm::tapTempo,
                    onSwingChange = vm::setSwing,
                    onOpenKitPicker = { showKitSheet = true },
                    onToggleMixer = { showMixer = !showMixer },
                    mixerOpen = showMixer,
                    onExportWav = {
                        val slug = state.activePreset.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        wavExportLauncher.launch("${slug}_${state.bpm}bpm.wav")
                    },
                    onExportMidi = {
                        val slug = state.activePreset.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        midiExportLauncher.launch("${slug}_${state.bpm}bpm.mid")
                    },
                    onImportSf2 = { sf2PickerLauncher.launch(arrayOf("*/*")) }
                )

                // Signature SF Drum Machine compact top icon mode strip (orange filled square for active mode!)
                if (!showMixer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CowColors.BgDark)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CowMode.entries.forEach { mode ->
                            val selected = activeMode == mode
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(width = 48.dp, height = 40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) CowColors.AccentOrange else Color.Transparent)
                                    .clickable { activeMode = mode }
                            ) {
                                Icon(
                                    imageVector = mode.icon,
                                    contentDescription = mode.title,
                                    tint = if (selected) Color.White else CowColors.TextPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                )
        ) {
            if (showMixer) {
                MixerScreen(
                    state = state,
                    onUpdateChannel = vm::updateChannelMix,
                    onAudition = { inst -> vm.triggerPad(inst, 0.9f) },
                    onResetMixer = vm::resetMixer,
                    onOpenKitPicker = { showKitSheet = true }
                )
            } else {
                when (activeMode) {
                    CowMode.PRESETS -> PresetsScreen(
                        state = state,
                        onSelectPreset = vm::selectPreset,
                        onTogglePlay = vm::togglePlay,
                        onSelectSlot = vm::selectSlot,
                        onTriggerFill = vm::triggerFill,
                        onToggleAutoFill = vm::toggleAutoFill,
                        onAddToSong = { preset -> vm.addSongSection(preset) },
                        onDeleteCustomPreset = vm::deleteCustomPreset
                    )
                    CowMode.PADS -> PerformScreen(
                        state = state,
                        onTriggerPad = vm::triggerPad,
                        onSelectSlot = vm::selectSlot,
                        onTriggerFill = vm::triggerFill,
                        onToggleMetronome = vm::toggleMetronome,
                        onToggleLiveRecord = vm::toggleLiveRecord,
                        onClearSlot = vm::clearActiveSlot,
                        onPrevKit = { vm.stepBuiltInKit(-1) },
                        onNextKit = { vm.stepBuiltInKit(1) },
                        onOpenKitPicker = { showKitSheet = true }
                    )
                    CowMode.SEQUENCER -> SequencerScreen(
                        state = state,
                        onSelectSlot = vm::selectSlot,
                        onCycleStep = vm::cycleStep,
                        onAuditionInstrument = { inst -> vm.triggerPad(inst, 0.9f) },
                        onClearSlot = vm::clearActiveSlot,
                        onToggleBarLength = vm::toggleBarLength,
                        onSetTimeSignature = vm::setTimeSignature,
                        onSaveCustomPreset = vm::saveCurrentAsCustomPreset
                    )
                    CowMode.SONG -> SongScreen(
                        state = state,
                        onPlaySong = vm::playSong,
                        onStopSong = vm::stopPlayback,
                        onAddCurrentPreset = { vm.addSongSection(state.activePreset, state.selectedSlot) },
                        onUpdateSection = vm::updateSongSection,
                        onRemoveSection = vm::removeSongSection,
                        onMoveSection = vm::moveSongSection
                    )
                }
            }
        }
    }

    if (showKitSheet) {
        KitSoundFontSheet(
            state = state,
            onDismiss = { showKitSheet = false },
            onSelectBuiltInKit = vm::selectBuiltInKit,
            onImportSoundFontClick = { sf2PickerLauncher.launch(arrayOf("*/*")) },
            onSelectSoundFontPreset = vm::selectSoundFontPreset,
            onDeleteSoundFont = vm::deleteSoundFont,
            onToggleLockKit = vm::toggleLockKit
        )
    }
}
