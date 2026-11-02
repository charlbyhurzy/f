# Futura Audio Preview

## Current state

The project tree has been received, but no implementation files have been added yet. The preview is therefore structural only.

The Gradle settings file is now present and declares the `app` and `engine` modules. Dependency resolution is restricted to the configured repositories.

The root build script now declares the Android, Kotlin, Compose, Hilt, and KSP plugins for module use and provides the root `clean` task.

Project-wide Gradle performance, AndroidX, Kotlin, KSP, Compose, and native-build settings are now present.

The version catalog now centralizes build plugins and application dependencies for the planned `app` and `engine` modules.

The application module is now configured for Compose, Hilt, Room, Media3, networking, KSP, Java/Kotlin 17, release signing, and the native `:engine` dependency.

The engine module is now configured as an Android library with C++17/CMake, NDK ABI filtering, JNI packaging, Kotlin wrappers, and native audio dependencies.

Engine shrinker rules now preserve JNI bridge classes, reflective callbacks, native methods, exception types, metadata, and diagnostic line information.

Application shrinker rules now cover app entry points, Hilt, Room, Moshi, Retrofit, Compose, Media3, Coil, WorkManager, Wear, biometric, coroutines, Parcelable, and JNI surfaces.

The app signing-properties template is present with placeholders only. Release signing remains inactive until a local keystore and real credentials are supplied. The binary keystore remains local and is not copied into the workspace by this workflow.

The root `.gitignore` now excludes signing secrets, build output, native artifacts, third-party downloads, IDE state, logs, and test artifacts.

The root `.editorconfig` now defines formatting for Kotlin, Gradle, C++, CMake, XML, TOML, ProGuard, YAML, JSON, Markdown, scripts, and binary assets.

The POSIX Gradle wrapper is now present, allowing Unix-like environments to invoke the pinned Gradle distribution through the wrapper JAR.

The Windows Gradle wrapper is now present for Windows development.

The wrapper properties now pin Gradle 8.9 and configure its distribution cache and URL validation.

An empty wrapper JAR placeholder is present by request. The Gradle wrapper cannot run until it is replaced with the valid binary.

The native dependency submodule declarations are now present for Oboe, FLAC, libsamplerate, SoundTouch, and libusb. Prebuilt FFmpeg, TensorFlow Lite, and ONNX Runtime archives remain setup-script inputs.

The README now documents requirements, setup, building, signing, testing, native prerequisites, roadmap, and technology choices. Its status claims are qualified because the workspace is still being populated.

The Android manifest is now present with permissions, application aliases, activities, services, receivers, provider configuration, and widget metadata. Invalid lock-screen and missing-shortcuts references were removed.

The first alarm resource is now present: a 24dp emerald-tinted vector drawable for alarm-armed indicators.

A muted slate disarmed alarm indicator with a diagonal strike-through is now also present.

The alarm-state resource set is complete with an amber snoozed indicator using clock bells and snooze glyphs.

A neutral slate battery vector is now present; callers can tint it according to device charge state.

The playback transport set now includes a neutral white skip-next vector for player, notification, widget, car, and headset surfaces.

A monochrome white notification play icon is now present for notification actions and RemoteViews surfaces.

The basic playback transport set now includes a neutral white pause icon for in-app, widget, car-mode, ambient, and notification surfaces.

The previous-track vector completes the basic previous/next transport pair.

A full-colour 108dp launcher shortcut alarm icon is now present using the Futura emerald, cyan, and slate palette.

A matching full-colour favorites shortcut icon is now present with a heart glyph, cyan halo, and highlight accent.

A full-colour playlist shortcut icon is now present with track lines, a cyan play overlay, and launcher-safe geometry.

A monochrome notification icon is now present with a hollow ring and bold play triangle for status-bar and foreground-service use.

The adaptive launcher foreground layer is now present with safe-zone-aware emerald, cyan, and white vector geometry.

The Now Playing widget layout is now present with alarm status, battery, album art, track metadata, and previous/play-next controls. Its play icon and accessibility strings remain pending resources.

The canonical play transport drawable is now present, resolving the widget's play-control icon reference.

The central strings catalog is now present with 226 resources covering app identity, alarm, widget, playback, library, DJ, visualizer, car, sleep, privacy, permissions, errors, and loading surfaces.

The central color catalog is now present with 75 named colors covering brand anchors, surfaces, state feedback, alarm, playback, library, DJ, visualizer, car, sleep, privacy, icon tints, and launcher background.

Base and alarm XML themes are now present with OLED-black window backgrounds, transparent system bars, white system-bar icons, AppCompat inheritance, and alarm-specific error accents.

The night theme override now restates the primary theme's required window, system-bar, force-dark, contrast, and accent settings.

Widget provider metadata is now present with 4x2 default sizing, 2x1 minimum resize sizing, manual refresh policy, home-screen placement, and the existing widget layout.

Legacy Auto Backup rules are now present with all app storage domains excluded; the manifest's `allowBackup="false"` remains the active runtime policy.

Android 12+ extraction rules are now present with matching cloud-backup and device-transfer exclusions, including direct-boot domains.

Static launcher shortcuts are now present for Alarm, Favorites, and Playlist, with their supplied icons, labels, and MainActivity actions. The manifest metadata reference is active again.

The API 26+ adaptive launcher icon is now present, combining the slate background color with the supplied emerald/cyan foreground vector.

The round adaptive launcher companion now uses the same background and foreground layers, allowing the launcher to apply its selected mask.

The legacy launcher WebP assets remain pending and will be generated together later. No invalid placeholders were created.

The application entry point is now present with Hilt registration, manual WorkManager configuration, foreground lifecycle state, and transparent uncaught-exception delegation.

The Compose activity host is now present with route registration for the planned destinations, shortcut and deep-link resolution, single-task intent handling, and temporary placeholder screens. The supplied unresolved `finish()` call was corrected to use an explicit activity callback.

The shared Compose theme is now present with the Futura palette, OLED-dark Material 3 color scheme, monospace display typography, and glassmorphic corner shapes. The activity now uses this shared theme rather than a local temporary duplicate.

The UI bootstrap ViewModel is now present with Loading, Ready, and Failed phases, retry support, and an `isReady` StateFlow. The loading placeholder observes this state through Hilt instead of advancing on a fixed delay.

The implemented loading screen is now present with an animated emerald ring, bootstrap status states, failure details, and retry action. MainActivity routes cold starts through it before entering the main flow.

The Phase 1 Wake-Up Overlay is now active in the navigation graph with a live wall-clock display, alarm/audio status badges, snooze transition, and swipe-to-dismiss gesture. Its unavailable alarm and audio providers remain represented by nullable inputs until those subsystems arrive.

The Phase 2 Now Playing route is now active with a self-contained stub skeleton: album-art placeholder, static waveform, track metadata, tabbed empty states, playback controls, and navigation chips. Future component files can replace each marked seam without changing the route contract.

The reusable Now Playing top bar is now present and wired into that screen, providing the Alarm Deck chip, format badge, and EQ toggle with callback-based seams for the upcoming Stream Inspector and DSP flows.

The Stream Inspector modal is now present and opened by the Now Playing format badge. It provides an animated top drawer, scrim dismissal, diagnostic rows, and bit-perfect status using placeholder telemetry until the stream provider is delivered.

The reusable HeroPlayer component is now active in the Now Playing layout, providing Coil-backed artwork loading, an accessible music-note fallback, and a reactive emerald bass-glow aura. Waveform and track metadata remain temporary seams for their upcoming components.

The KineticWaveform component is now active with 96-bar rendering, played/unplayed highlighting, tap/drag seeking, and a drag-time preview pill. It currently uses local placeholder playback progress and duration.

The TrackInfo component is now active with bold title text, cyan-glowing artist text, ellipsis handling, and an optional synced-lyrics chip. It currently receives placeholder track values from the Now Playing screen.

The MultiPanelDeck component is now active in the weighted Now Playing content area with accessible Up Next, Lyrics, and Related tabs, animated selection, recessed empty-state panels, and future panel content slots.

The UpNextPanel is now wired into the Up Next tab with queue item modeling, empty-state rendering, drag-handle reorder support, and similar-track suggestion slots. The current queue is empty until playback state is connected.

The LyricsPanel is now wired into the Synced Lyrics tab with timed line models, duet voice alignment, translations, word-level highlighting, auto-scroll, and tap-to-seek support. The current lyric list is empty until playback lyrics state is connected.

The RelatedPanel is now wired into the Related & Mood tab with mood-chip rendering, related-track rows, scrolling, and an empty state. Recommendation and mood inputs remain empty until the playback and ML state sources are delivered.

The PlaybackDock is now rendered at the bottom of Now Playing with shuffle, previous, animated play/pause glow, next, loop controls, and accessibility semantics. Its callbacks remain no-op seams until the playback state source is delivered.

Now Playing is now connected to `NowPlayingViewModel`: stable track, queue, lyrics, related, mood, stream, and toggle state plus position, bass, and waveform flows are collected by the screen. The current ViewModel uses an in-memory queue/ticker until Media3 and the native engine are delivered.

The pure `LyricParser` data utility is now present. It accepts standard and enhanced LRC text, resolves offsets, duet voice markers, translations, word timings, and chronological ordering; it is ready to be connected once lyric storage is available.

The `OfflineLyricSynchronizer` utility now supports plain-text lyric cleanup, optional asynchronous alignment, validated ML timings, and a proportional-duration fallback for offline use.

The engine module now contains the `MlBridge` JNI-facing Kotlin boundary. It loads the native library defensively, exposes lifecycle and ML operations through guarded wrappers, and degrades safely until the native JNI implementation and model assets are available.

The engine module now also contains the `NativeEngine` facade for audio-engine lifecycle, crash diagnostics, device configuration, memory/XRun metrics, audio-thread priority, and native logging. Its wrappers remain safe while the native JNI sources are still pending.

The engine module now contains the `NativePlayerBridge` with transport, playback state, queueing, source metadata, resampler, silence-trim, error, and diagnostic APIs. The bridge remains ready for the native `JniBridge.cpp` implementation.

The engine module now contains the `DspBridge` with guarded DSP lifecycle/configuration, EQ and preset controls, enhancement/reverb/compressor parameters, spectrum and ReplayGain analysis, and peak metering. Its 30-float config interchange layout is documented for the pending native implementation.

The engine module now contains `NativeUsbAudio`, a guarded three-method USB Audio Class bridge. It accepts a caller-owned USB file descriptor, negotiates the requested format during attach, exposes negotiated state and stable error codes, and releases native streaming resources without closing the caller's descriptor. Its pending native implementation is `UsbAudioJni.cpp`.

The native Oboe layer now includes `OboeStreamBuilder.h`. It centralizes stream construction with low-latency float output defaults, exclusive-mode preference, shared-mode fallback, explicit API selection, callback registration, and diagnostic result/error state. It is ready to be consumed by the pending native engine orchestrator once the Oboe dependency is populated.

The native audio layer now includes `AudioOutput.h` and `AudioOutput.cpp`. Together they own an Oboe stream, expose lifecycle and transport controls, forward render/error callbacks, and report stream telemetry. Their result state is synchronized for control-thread inspection; compilation still requires the Oboe third-party source.

The native core now includes `core/Engine.h`, a pimpl-based orchestration interface that keeps decoder, DSP, queue, and render-loop implementation details out of the public header. Its implementation remains pending until those component headers are delivered.

The native core now includes `core/Engine.cpp`. It opens the Oboe output, advances playback position, handles transport and queue state, emits silence through the live callback, and exposes real stream telemetry. Decoder, DSP, and gapless audio data remain future integration work; it does not yet play source files.

The native core now includes `core/LockFreeRingBuffer.h`, an SPSC interleaved-PCM FIFO designed for decoder-to-audio-thread transfer. It uses atomic publication and bounded bulk copies without locks or steady-state allocations, with constructor overflow validation.

## Confirmed foundation

- Kotlin Android application
- Jetpack Compose with Material 3
- Gradle Kotlin DSL
- `minSdk 26`
- `targetSdk 34`
- `compileSdk 34`
- Planned modules and packages are tracked by the supplied tree

## Planned first visible experience

The tree identifies `WakeUpOverlayScreen` as the first fully implemented screen. The current activity reaches that route through a temporary alarm placeholder; the real screen will replace it when its dependencies are supplied.

## Preview limitations

- No build can run until the Gradle files and source files are present.
- The current activity renders temporary placeholders until the planned screen implementations and their dependencies are supplied.
- Files marked as stubs or missing in the supplied tree remain placeholders.

## Next file

Binary launcher assets are being skipped for now. Please provide the next dependency-order source file from the supplied tree.
