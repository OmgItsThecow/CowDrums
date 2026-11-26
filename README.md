# CowDrums — Android SoundFont Drum Machine

A native Android drum machine (Kotlin + Jetpack Compose) inspired by **SF Drum Machine**, packed with **51 ready-to-play drum presets** (153 distinct patterns with Main A, Variation B, and Drum Fills) across 10 genres, **8 built-in studio drum kits**, and **custom SoundFont 2 (`.sf2`) file support**.

## Features

- **51 Ready-to-Play Drum Presets (10 Genres)**:
  - Rock, Pop, Funk & Soul, Hip-Hop & Trap, EDM & House, Jazz & Blues, Latin & Reggae, Metal, Odd Time (`3/4`, `6/8`, `5/4`, `7/8`), and Afro & R&B.
  - Every preset includes **Main A**, **Variation B**, and a musical **Drum Fill**, plus recommended BPM, Swing %, Time Signature, and Kit.
- **8 Built-in Drum Kits + Custom `.sf2` SoundFont Loader**:
  - Built-in kits: Standard Acoustic, TR-808 Classic, TR-909 Club, Rock & Metal, Jazz & Brush, Modern EDM, Latin Percussion, and Lo-Fi Boom-Bap.
  - Load any standard `.sf2` SoundFont file (Bank 128 GM Percussion prioritized) directly from your Android device storage.
- **Low-Latency Sample-Accurate Audio Engine**:
  - 44.1 kHz stereo `AudioTrack` (`PERFORMANCE_MODE_LOW_LATENCY`), 32-voice polyphony, hi-hat choke groups, soft-clipping master bus, and built-in metronome click.
- **WAV & MIDI Export**:
  - Offline-render any drum pattern (with your active kit or `.sf2` SoundFont, mixer settings, pitch, pan, and swing) to 44.1 kHz 16-bit stereo `.wav`, or export Standard MIDI (`.mid`, GM Channel 10) for any DAW.
- **5 Studio Views**:
  - **Presets**: Instant 1-tap play browser with search, genre filters, and docked `MAIN A` / `VAR B` / `FILL` / `AUTO-FILL` bar.
  - **Perform**: 12 velocity-sensitive drum pads with live overdub recording (`REC`) and real-time Swing control.
  - **Sequencer**: 12-track step grid (`12..32` steps), 4 velocity levels (Off / Ghost / Normal / Accent), time signature & 1B/2B length controls, and Save Custom Preset.
  - **Mixer**: Per-drum Volume, Pan, Pitch (`-12..+12` semitones), Mute, and Solo.
  - **Song**: Chain presets into multi-section song arrangements with bar repeat counts and automatic end-of-section fills.

## Build APK

```bash
gradle assembleDebug
gradle testDebugUnitTest
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
