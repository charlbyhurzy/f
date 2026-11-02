# FUTURA AUDIO

> A high-performance offline Android music player, disguised as a low-cognitive-load system alarm.

Futura Audio is a multi-module Android project built around a hand-written C++17 audio engine, a Jetpack Compose Material 3 UI, and a Phase‑1 alarm/security gateway that doubles as the app's camouflage surface. Everything runs on-device: indexing, DSP, machine-learning inference, and playback all happen offline, with no network dependency for the core experience.

---

## Table of contents

1. [Repository layout](#repository-layout)
2. [Requirements](#requirements)
3. [Getting started](#getting-started)
4. [Building](#building)
5. [Native engine prerequisites](#native-engine-prerequisites)
6. [Release signing](#release-signing)
7. [Testing](#testing)
8. [Feature roadmap](#feature-roadmap)
9. [Tech stack](#tech-stack)
10. [License](#license)

---

## Repository layout
```

futura-audio/
├── app/ Kotlin / Compose application module
│ └── src/main/java/com/futura/
│ ├── ui/ Compose UI, screens, components, viewmodels
│ ├── di/ Hilt modules
│ ├── alarm/ Phase 1 — Alarm engine + camouflage security
│ ├── data/ Phase 2 — Media indexer, Room, remote sync
│ ├── engine/ Kotlin bridge to the native module
│ ├── dsp/ Kotlin-side DSP helpers
│ ├── nowplaying/ Now Playing support services
│ └── system/ Phase 6 — System integration (Bluetooth, Wear, USB…)
│
├── engine/ Native C++17 / Oboe audio engine module
│ └── src/main/cpp/
│ ├── core/ Render loop, ring buffer, state machine
│ ├── oboe/ AAudio / OpenSL ES stream builders
│ ├── decode/ FFmpeg + libFLAC decoder wrappers
│ ├── dsp/ 10-band EQ, crossfeed, reverb, dynamics
│ ├── ml/ TFLite / ONNX inference bridges
│ ├── analysis/ BPM, key, pitch-lock
│ ├── recovery/ FLAC frame repair, ID3 sync recovery
│ ├── bitperfect/ Bit-perfect switcher
│ ├── usb/ USB Audio Class passthrough
│ ├── jni/ JNI bridges into Kotlin
│ └── third_party/ Submodules + prebuilt binaries (see .gitmodules)
│
├── gradle/
│ ├── libs.versions.toml Version catalog (all dependency pins live here)
│ └── wrapper/ Gradle wrapper (8.9)
│
└── .github/workflows/ CI: android-build, native-build, web-build, tests

text

```
---

## Requirements

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 | Required by AGP 8.5+ |
| Android Studio | Ladybug (2024.2.1) or newer | For Compose 2.0 + AGP 8.5 |
| Android SDK | compileSdk 34 / targetSdk 34 / minSdk 26 | API 34 platform + build-tools |
| NDK | 26.3.11579264 | Pinned in `engine/build.gradle.kts` |
| CMake | 3.22.1 | Installed via Android SDK Manager |
| Gradle | 8.9 | Wrapper handles this automatically |
| Python | 3.10+ | Only for `scripts/setup.sh` prebuilt downloads |
| Git | 2.40+ | Needed for `--recurse-submodules` |

---

## Getting started

### 1. Clone with submodules

```bash
git clone --recurse-submodules https://github.com/your-org/futura-audio.git
cd futura-audio
```

If you already cloned without `--recurse-submodules`:

bash

```
git submodule update --init --recursive
```

### 2. Fetch prebuilt native dependencies
The three prebuilt archives (FFmpeg, TensorFlow Lite, ONNX Runtime) are **not** submodules — they are versioned release downloads handled by the setup script:

bash

```
chmod +x scripts/setup.sh
./scripts/setup.sh
```

This populates:

- `engine/src/main/cpp/third_party/ffmpeg/`
- `engine/src/main/cpp/third_party/tensorflow-lite/`
- `engine/src/main/cpp/third_party/onnxruntime/` (optional — set `WITH_ONNX=0` to skip)

### 3. Point Gradle at your Android SDK
Create `local.properties` at the repository root:

properties

```
sdk.dir=/Users/you/Library/Android/sdk
# Windows example:
# sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

`local.properties` is gitignored.

### 4. Verify the environment
bash

```
./gradlew --version
# → Gradle 8.9, Kotlin 2.0.21, JVM 17
---

## Building

### Debug APK (no release signing required)
bash

```
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

### Release APK / AAB (requires signing — see below)
bash

```
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

### Native-only build (`:engine` module)
bash

```
./gradlew :engine:assembleRelease
# → engine/build/intermediates/cmake/release/obj/<abi>/libfutura_engine.so
```

### Full clean
bash

```
./gradlew clean
```

### Build matrix (as tested by CI)

TaskJobs`assembleDebug``android-build.yml``assembleRelease``android-build.yml` (when keystore secrets present)`engine:assembleRelease``native-build.yml``testDebugUnitTest``tests.yml``connectedAndroidTest``tests.yml` (emulator matrix)

---

## Native engine prerequisites
The `:engine` module cross-compiles C++17 for `arm64-v8a`, `armeabi-v7a`, and `x86_64`. CMake needs:

- **Android NDK r26** — installed via SDK Manager
- **The five submodules** — see [`.gitmodules`](./.gitmodules):

- `oboe` (Google low-latency audio)
- `libFLAC` (Xiph reference decoder)
- `libsamplerate` (Sinc resampler)
- `soundtouch` (pitch/time scaling)
- `libusb` (USB DAC passthrough)
- **The three prebuilt archives** — see `scripts/setup.sh`:

- `ffmpeg` (avcodec, avformat, avutil, swresample)
- `tensorflow-lite` (libtensorflowlite_c)
- `onnxruntime` (libonnxruntime, optional)
If any prerequisite is missing, `./gradlew :engine:assembleDebug` fails with a descriptive message pointing at the missing path. It does not fail silently.

To rebuild the native module from scratch after modifying C++ sources:

bash

```
./gradlew :engine:clean :engine:assembleDebug
```

The wrapper jars under `~/.gradle/caches/` make subsequent builds fast.

---

## Release signing
`app/keystore.properties` and `app/keystore.jks` are both gitignored. On a clean checkout, `assembleRelease` succeeds but produces an **unsigned** APK/AAB — the `hasReleaseKeystore` guard in `app/build.gradle.kts` is designed for that.

To sign locally:

1. Generate a keystore (once):

bash

```
keytool -genkeypair -v \
  -keystore app/keystore.jks \
  -alias futura-release \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype JKS
```
2. Fill in `app/keystore.properties` using the template comments in the file.
3. `./gradlew :app:assembleRelease` — the release build now applies the `signingConfig`.
For CI, the `android-build.yml` workflow reconstructs both files from repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_STORE_PASSWORD`, `KEYSTORE_KEY_ALIAS`, `KEYSTORE_KEY_PASSWORD`). Never commit the `.jks` or the properties file.

---

## Testing

### Unit tests (JVM, no emulator)
bash

```
./gradlew testDebugUnitTest
```

Runs the 15 handoff tests under `app/src/test/java/com/futura/handoff/`, which cover:

TestWhat it verifies`Handoff01SnoozeBackoffTest`Exponential snooze backoff calculator`Handoff02TeardownTest`Service teardown ordering`Handoff03DecoyCleanupTest`Multi-pass decoy-vault wipe`Handoff04AndroidAutoBrowseTest`MediaBrowserServiceCompat tree`Handoff05IntegrityValidatorTest`Storage hash verification`Handoff06OutputRouterTest`Explicit audio output routing`Handoff07TapReadNonConsumingTest`Lock-free ring buffer peek`Handoff08EqPresetRoundtripTest`Binary EQ preset serialization`Handoff09BitPerfectBypassTest`DSP bypass state toggling`Handoff10AgcNoAllocTest`Zero-allocation AGC render loop`Handoff11MoodEngineLatencyTest`Context mood engine latency budget`Handoff12BassLevelRangeTest`Bass-reactive glow value bounds`Handoff13SessionFanoutTest`MediaSession event fan-out`Handoff14BatteryFpsPropagationTest`Thermal → framerate propagation`Handoff15KnockFalsePositiveTest`Accelerometer knock rejection

### Instrumented tests (emulator or device)
bash

```
./gradlew connectedAndroidTest
```

### Native unit tests (C++)
bash

```
./gradlew :engine:externalNativeBuildDebug
# CTest binaries under engine/build/intermediates/cmake/debug/obj/<abi>/
#   test_ring_buffer, test_dsp_filters, test_resampler
```

---

## Feature roadmap
The full 150-feature catalogue is split across six phases. Features are implemented phase-by-phase; screens, native modules, and system integrations map directly to the list.

| Phase | Focus | Representative features |
|---|---|---|
| 1 | Alarm engine, camouflage security, system handshake | `FuturaAlarmService`, `AlarmCoordinator`, decoy PIN store, biometric wake lock, missed-alarm watchdog |
| 2 | Offline media indexer, storage, metadata | Room DB, FFmpeg/TagLib metadata extraction, cover-art cache, FTS5 search, `.cue` parser, M3U export |
| 3 | Native C++17 audio engine | Oboe streams, AAudio MMAP, FFmpeg + libFLAC decoders, resampler, gapless playback, pitch/time engines |
| 4 | DSP, analysis, equalizer backend | 10-band EQ, ReplayGain 2.0, FFT analyzer, crossfeed, compressor, BPM counter, A/B loop |
| 5 | On-device ML, smart curation, DSP inference | Stems separator, MFCC feature extractor, acoustic similarity, auto-tagging, vocal extractor, upscaler |
| 6 | System integration, hardware control, routing | MediaSession, Android Auto, USB DAC, bit-perfect, Wear OS, widgets, Car Mode, Sleep Dock, Privacy Vault |

The supplied tree describes Phases 1–3 as implemented and Phases 4–6 as scaffolded or stubbed. The current checkout is still being assembled, so build readiness depends on the remaining files.

---

## Tech stack
**Android**

- Kotlin 2.0.21, Jetpack Compose (BOM 2024.10.00), Material 3
- Hilt 2.52 (KSP), Room 2.6.1 (KSP), Moshi 1.15.1, Retrofit 2.11
- Media3 1.4.1, WorkManager 2.9.1, Biometric 1.2.0, Play Services Wearable 19.0.0
- minSdk 26 / targetSdk 34 / compileSdk 34, Java 17 toolchain with core-library desugaring
**Native (`:engine`)**

- C++17, CMake 3.22.1, Android NDK 26.3.11579264
- Oboe (AAudio + OpenSL ES), FFmpeg (libavcodec / libavformat / libavutil / libswresample)
- libFLAC, libsamplerate, SoundTouch, libusb
- TensorFlow Lite C API, ONNX Runtime (optional)
**Build**

- Gradle 8.9 (Kotlin DSL), AGP 8.5.2, KSP 2.0.21-1.0.28
- Version catalog at `gradle/libs.versions.toml` — every dependency pin lives there, nowhere else

---

## License
Copyright © 2025 Futura Audio.

Licensed under the Apache License, Version 2.0. You may obtain a copy at:

> [https://www.apache.org/licenses/LICENSE-2.0](https://www.apache.org/licenses/LICENSE-2.0)
Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.