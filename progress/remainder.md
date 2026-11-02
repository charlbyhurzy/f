# Futura Audio Remaining Work

This document is maintained alongside `progress.md` as the project continues.

## Immediate Phase 2 work

- Connect `TrackInfo.kt` to real track title, artist, and lyrics availability from playback state.
- Add the remaining Now Playing components:
- Add `NowPlayingViewModel.kt` or the planned playback state source.
- Connect real track, position, duration, waveform, format, stream telemetry, and EQ state.
- Replace the ViewModel's in-memory playback behavior with Media3/engine-backed state when those subsystems arrive.
- Connect `LyricParser` to a lyric repository or track-local LRC source and map parsed lines into `NowPlayingViewModel`.
- Connect `OfflineLyricSynchronizer` to plain-text lyric loading and the future ML bridge.
- Implement and package the native `MlJni.cpp` methods and ML runtime dependencies consumed by `MlBridge`.
- Implement and package the native `JniBridge.cpp` and `CrashHandlerJni.cpp` methods consumed by `NativeEngine`.
- Replace the initial player JNI control backend in `JniBridge.cpp` with real decoder, resampler, and audio-output integration.
- Wire `OboeStreamBuilder.h` into the native engine once the Oboe third-party source is populated and the audio engine callback is available.
- Populate the Oboe third-party dependency and compile the `AudioOutput` implementation against the real Oboe API.
- Replace the current silent `core/Engine.cpp` backend with decoder, ring-buffer, DSP, queue, and gapless playback integration as those components are delivered.
- Connect `core/LockFreeRingBuffer.h` to the decoder producer and Oboe render consumer once the render-loop and decoder components arrive.
- Implement and package the native DSP JNI methods consumed by `DspBridge` in `DspJni.cpp`.
- Implement and package the native USB Audio Class JNI methods consumed by `NativeUsbAudio` in `UsbAudioJni.cpp`.

## Remaining application screens

- Implement or add:
  - `LibraryScreen.kt`
  - `DjStudioScreen.kt`
  - `VisualizerDeckScreen.kt`
  - `CarModeScreen.kt`
  - `SleepDockScreen.kt`
  - `PrivacyVaultScreen.kt`
- Replace the temporary MainActivity route placeholders.

## Alarm and application subsystems

- Add the alarm domain, scheduler, store, coordinator, and service classes.
- Add the native engine bridge and connect audio initialization.
- Add vault, decoy-state, widget-state, shortcut, and lifecycle subsystems.
- Replace MainViewModel's no-op bootstrap with the real dependency-ordered initialization.
- Supply real alarm and audio-output data to `WakeUpOverlayScreen`.

## Native and dependency setup

- Obtain valid native submodules and required prebuilt dependencies.
- Replace the empty `gradle/wrapper/gradle-wrapper.jar` with a valid Gradle wrapper binary.
- Verify Coil and all declared dependencies resolve successfully.
- Generate or provide the deferred legacy launcher WebP assets.

## Build and quality validation

- Run Gradle sync and a debug compile once the wrapper JAR is valid.
- Fix Android resource compiler, Kotlin compiler, lint, and packaging issues revealed by the real build.
- Add unit tests for route resolution, bootstrap transitions, and seek behavior.
- Add Compose previews/tests for the delivered screens and components.
- Run the smallest relevant test set, then a complete project validation.

## Documentation and release readiness

- Continue updating `progress/progress.md` after each completed file or subsystem.
- Continue updating this remainder document as work is completed or newly discovered.
- Update README status and setup instructions when the project becomes buildable.
- Configure local release signing without committing credentials or keystores.
