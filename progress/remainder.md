# Futura Audio Remaining Work

This document is maintained alongside `progress.md` as the project continues.

## Playback path (blocks audio)

- Wire `DecoderThread`, `ZeroAllocRenderLoop`, `StreamStateMachine`, `FFmpegDecoderContext` and `ResamplerEngine` into `Engine.cpp`, replacing the silent backend.
- Add the FFmpeg headers and libraries to the build. `FFmpegDecoderContext.h` requires them.
- Add the decode headers referenced in comments: `decode/MemoryMappedFile.h`, `FlacNativeDecoder.h`, `GaplessPlaybackQueue.h`.
- Add libsamplerate and the other native libraries to `CMakeLists.txt`. Libraries still missing: libFLAC, soundtouch, libusb.

## Native headers

- Add the `dsp/`, `ml/` and `usb/` headers the JNI files depend on.

## Resources and startup

- Create the legacy launcher WebP icons.
- Replace the no-op bootstrap in `MainViewModel` with real subsystem initialization.

## Application screens and subsystems

- Library, DJ Studio, Visualizer, Car Mode, Sleep Dock and Privacy Vault screens. Only placeholders exist in `MainActivity`.
- Alarm domain, scheduler, store, coordinator and service.
- Vault, decoy-state, widget-state and shortcut subsystems.
- Connect Now Playing to real track, lyrics, queue and related-track data.
- Lyric loading from LRC files or a repository.

## Build and release

- Re-enable release minification once the R8 `ConcurrentModificationException` is resolved.
- Run the unit tests and Compose previews listed in the plan.
