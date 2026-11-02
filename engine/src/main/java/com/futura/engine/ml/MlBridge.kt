// futura-audio/app/src/main/java/com/futura/engine/ml/MlBridge.kt
//
// Phase 5 — ML runtime JNI bridge.
//
// ── Tree annotation: "✅ 20 external methods" ─────────────────────────────
// Per the project tree (futura-audio.txt), this file exposes exactly 20
// `external` functions that route into the native ML engine implemented
// in engine/src/main/cpp/jni/MlJni.cpp. The 20 methods correspond to the
// 20 ML C++ classes declared under engine/src/main/cpp/ml/ in the tree
// (TfliteRuntime, StemsSeparator, MfccExtractor, EmbeddingGenerator,
// LocalVectorDatabase, AcousticSimilarityEngine, AutoTaggingClassifier,
// SmartQueueGenerator, KeyDetector, MlVolumeNormalizer, VocalExtractor,
// SpeechMusicClassifier, RecommendationEngine, SkipPredictor,
// AudioFingerprinter, LyricSyncMatcher, AudioUpscaler,
// NoiseReductionEngine, InstrumentSoloIsolator, EnergyCurvePlotter).
//
// Three C++ headers in that directory are internal helpers and do NOT
// expose JNI entry points, which is why the tree's 23 C++ files map to
// 20 external methods:
//   • OnnxFeatureBufferBuilder.h  (internal buffer management)
//   • LocalVectorQuantizer.h      (internal quantisation pass)
//   • QuantizationExecutor.h      (offline tool, not a runtime call)
//
// ── Design contract ──────────────────────────────────────────────────────
// This file is a **safety-first bridge**, not a raw JNI stub. Every
// external method is declared `private` and wrapped by a public method
// that:
//
//   1. Checks `libraryAvailable` — the .so may not exist yet (during
//      bootstrap, or on ABI/device configurations the native module
//      doesn't support). When absent, wrappers return a documented
//      default and log once.
//   2. Catches `UnsatisfiedLinkError` — this is what the JVM throws
//      when an `external fun` has no matching native implementation.
//      That happens today because engine/src/main/cpp/jni/MlJni.cpp has
//      not been authored yet. Without this catch, the first ML call
//      would crash the app. With it, ML features degrade silently to
//      their default return values and the rest of the app continues.
//   3. Catches `Exception` — any other failure in the native layer
//      (model missing, OOM, invalid input, tensor shape mismatch) is
//      caught, logged, and degraded rather than propagated as a crash.
//   4. Coerces the native return into the Kotlin model the caller
//      expects. For the one method whose native return is a formatted
//      string array (`nativeAutoTag`), the wrapper does the parsing so
//      callers see a structured `MlTagResult` instead.
//
// The alternative pattern (public raw `external fun` that callers invoke
// directly) would force every caller to duplicate the same try/catch and
// null-check logic, and would let a single unhandled `UnsatisfiedLinkError`
// crash playback. Centralising it here keeps the bridge as the sole
// failure boundary between the Kotlin app and the native ML runtime.
//
// ── Threading contract ───────────────────────────────────────────────────
// Every method in this file is **synchronous and blocking**. Callers MUST
// invoke them from a background thread. The intended caller is
// engine/ml/InferenceThreadScheduler.kt (not yet delivered), which owns
// a dedicated ML thread and posts work onto it. Calling any of these
// methods from the main thread will cause jank at best and an
// Application Not Responding (ANR) crash at worst — model inference
// takes hundreds of milliseconds even for short clips.
//
// ── Library loading ─────────────────────────────────────────────────────
// The native library is loaded via `System.loadLibrary("futura_engine")`
// in the object's `init` block. That call is idempotent: if a future
// NativeEngine.kt (the JNI facade per the tree) also loads the same
// library name, the second call is a no-op. This file deliberately does
// NOT reference NativeEngine.kt — that file has not been delivered yet,
// and the ordering constraint (no forward references) is honoured by
// making this file fully self-contained for library loading.
//
// If `futura_engine` cannot be loaded (missing .so, wrong ABI, NDK not
// installed), the catch block records `libraryAvailable = false` and
// every wrapper in this file returns its default. The app continues
// without ML. The failure is logged at ERROR level so it is visible in
// logcat without requiring a debugger.
//
// ── Initialisation lifecycle ────────────────────────────────────────────
// Loading the .so is separate from initialising the ML runtime. The .so
// may be present but the ML models may not have been loaded into memory
// yet. Two flags track this:
//
//   • `libraryAvailable`  — set once in `init { }` when the .so loads.
//   • `mlInitialized`     — set by `init(config)` and cleared by `close()`.
//
// The two-state distinction matters because callers may want to
// distinguish "no native library at all" (feature entirely unavailable)
// from "library present but not yet initialised" (call `init()` first).
// `isAvailable()` reports the first; `isInitialized()` reports both.
//
// ── JNI naming contract (for MlJni.cpp) ─────────────────────────────────
// This object is `object MlBridge` in package `com.futura.engine.ml`.
// The JNI functions in MlJni.cpp must be declared with the exact names
// and signatures below. The `thiz` parameter is a `jobject` (the
// singleton instance) — NOT a `jclass`, because Kotlin `object` methods
// are instance methods on the singleton, not static methods on the class.
//
//   extern "C" JNIEXPORT <RetType> JNICALL
//   Java_com_futura_engine_ml_MlBridge_nativeInit(
//       JNIEnv* env, jobject thiz,
//       jstring modelsDir, jboolean useGpu, jint numThreads);
//
//   … and similarly for every other nativeXxx method below.
//
// If MlJni.cpp is instead written to use RegisterNatives in JNI_OnLoad,
// the signatures must match the Kotlin declarations exactly — either
// approach works, but the direct `Java_...` link is simpler and is what
// the KDoc above assumes.
//
// ── Forward-reference audit ──────────────────────────────────────────
// Imported symbols:
//   • android.util.Log                              (framework, all API levels)
//   • kotlin.jvm.Volatile                           (Kotlin JVM annotation, auto-imported)
//
// No project class, no Compose, no Hilt, no coroutines, no MediaSession,
// no NativeEngine, no engine/ml/MlAnalyzer (not delivered), no
// engine/ml/InferenceThreadScheduler (not delivered). This file compiles
// and functions standalone against the entire delivered codebase.

package com.futura.engine.ml

import android.util.Log

// ═════════════════════════════════════════════════════════════════════════
// Data models
// ═════════════════════════════════════════════════════════════════════════

/**
 * Configuration for [MlBridge.init].
 *
 * @param modelsDir Absolute path to the directory containing the
 *        TensorFlow Lite / ONNX model files. On first launch, callers
 *        should extract bundled models from assets to this directory
 *        before calling init. The native side treats this as a
 *        read-only resource path.
 * @param useGpu Whether to prefer GPU/NNAPI delegates. When false, the
 *        native runtime uses CPU inference. Default false: safer across
 *        devices, and the small models this app uses are CPU-feasible.
 *        Callers who have already verified a device supports GPU delegate
 *        can pass true.
 * @param numThreads Number of threads the native inference runtime may
 *        use. Default 2 matches the typical big.LITTLE core count
 *        available to a background thread. Higher values may contend
 *        with the audio thread; the native side clamps to a maximum
 *        defined in MlJni.cpp.
 */
data class MlInitConfig(
    val modelsDir: String,
    val useGpu: Boolean = false,
    val numThreads: Int = 2
)

/**
 * Structured result of [MlBridge.autoTag].
 *
 * Each map is label → confidence in `[0f, 1f]`. Empty maps mean the
 * corresponding classifier produced no predictions for the input (or the
 * bridge is in degraded mode).
 *
 * The three categories are independent: a track may have zero moods
 * predicted but two genres and one instrument, or vice versa.
 *
 * @param genres Classified genres (e.g. `"Pop"` → `0.92f`).
 * @param moods Classified moods (e.g. `"Energetic"` → `0.71f`).
 *        Labels are the same taxonomy as the `MoodTag` enum in
 *        `ui/components/nowplaying/RelatedPanel.kt`, but this file does
 *        not import `MoodTag` — that enum is a UI concern, and mapping
 *        from these ML strings to it is the caller's job.
 * @param instruments Classified instruments (e.g. `"Guitar"` → `0.65f`).
 */
data class MlTagResult(
    val genres: Map<String, Float>,
    val moods: Map<String, Float>,
    val instruments: Map<String, Float>
)

/**
 * Result of [MlBridge.separateStems] — a 4-stem separation of a mixed
 * audio sample.
 *
 * All four arrays are the same length as the input `pcmData` passed to
 * [MlBridge.separateStems], on the same sample rate. Callers who want
 * to mix them back at unity gain will get the original mixed signal.
 *
 * Not a `data class` because `FloatArray` uses reference equality for
 * `equals` / `hashCode` — a data class would produce misleading
 * equality semantics. This is a plain class with a `toString` that
 * reports only the sizes, which is what a caller actually wants when
 * debugging.
 */
class StemSet(
    val vocals: FloatArray,
    val drums: FloatArray,
    val bass: FloatArray,
    val other: FloatArray
) {
    override fun toString(): String =
        "StemSet(vocals=${vocals.size}, drums=${drums.size}, " +
            "bass=${bass.size}, other=${other.size})"
}

// ═════════════════════════════════════════════════════════════════════════
// Bridge
// ═════════════════════════════════════════════════════════════════════════

/**
 * The 20-method JNI bridge to the native ML runtime.
 *
 * See the file header for the full design contract (safety-first
 * wrappers, threading, library loading lifecycle, JNI naming). All
 * public methods are synchronous and blocking — see the file header's
 * "Threading contract" section before calling any of them.
 */
object MlBridge {

    private const val TAG = "MlBridge"
    private const val LIBRARY_NAME = "futura_engine"

    @Volatile
    private var libraryAvailable: Boolean = false

    @Volatile
    private var mlInitialized: Boolean = false

    init {
        libraryAvailable = try {
            System.loadLibrary(LIBRARY_NAME)
            Log.i(TAG, "Native library '$LIBRARY_NAME' loaded")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(
                TAG,
                "Native library '$LIBRARY_NAME' unavailable — ML features disabled. " +
                    "Build :engine or check ABI filters if this is unexpected.",
                e
            )
            false
        }
    }

    // ── Availability introspection ────────────────────────────────────

    /**
     * True if the native library (`libfutura_engine.so`) loaded
     * successfully. False means ML features are entirely unavailable —
     * callers should skip ML work rather than calling methods that will
     * return defaults.
     */
    fun isAvailable(): Boolean = libraryAvailable

    /**
     * True if the native library loaded AND [init] has completed
     * successfully. False means the caller either hasn't called [init]
     * yet, or a previous [init] failed, or [close] was called.
     */
    fun isInitialized(): Boolean = libraryAvailable && mlInitialized

    // ── Lifecycle ─────────────────────────────────────────────────────

    /**
     * Initialise the ML runtime with the given configuration.
     *
     * Idempotent in the "safe to call again" sense: if the runtime is
     * already initialised, the native side tears down the previous
     * runtime before loading the new one. Callers who need to change
     * configuration can call [close] first and then [init] with the new
     * config; calling [init] twice in a row is also safe.
     *
     * @return true on success, false on any failure (library missing,
     *         models not found, runtime OOM, native exception). The
     *         caller is expected to log and degrade gracefully.
     */
    fun init(config: MlInitConfig): Boolean {
        val success = safeCall(false) {
            nativeInit(config.modelsDir, config.useGpu, config.numThreads)
        }
        mlInitialized = success
        if (success) {
            Log.i(TAG, "ML runtime initialised (useGpu=${config.useGpu}, " +
                "numThreads=${config.numThreads})")
        } else {
            Log.w(TAG, "ML runtime init failed")
        }
        return success
    }

    /**
     * Tear down the ML runtime and release all native resources.
     *
     * Safe to call multiple times. After this returns, [isInitialized]
     * is false until [init] succeeds again. Any in-flight native call
     * on another thread is NOT synchronised against close — callers
     * should ensure no ML work is running before calling.
     */
    fun close() {
        safeCallUnit { nativeClose() }
        mlInitialized = false
    }

    // ── Audio analysis ────────────────────────────────────────────────

    /**
     * Separate a mono or stereo PCM sample into four stems.
     *
     * @param pcmData Interleaved or mono float samples in `[-1f, 1f]`.
     *        For stereo, pass interleaved `[L0, R0, L1, R1, …]`.
     * @param sampleRate Sample rate in Hz. Must match the native
     *        runtime's expected rate; see `MlJni.cpp` for the currently
     *        enforced rate (typically 44100 or 48000).
     * @return [StemSet] on success, `null` on any failure. Callers who
     *         need raw arrays should call [nativeSeparateStems] directly
     *         (not currently exposed) — the data class is the boundary.
     */
    fun separateStems(pcmData: FloatArray, sampleRate: Int): StemSet? {
        val arrays = safeCall(null) { nativeSeparateStems(pcmData, sampleRate) }
            ?: return null
        if (arrays.size != 4) {
            Log.w(TAG, "nativeSeparateStems returned ${arrays.size} arrays, expected 4")
            return null
        }
        return StemSet(
            vocals = arrays[0],
            drums = arrays[1],
            bass = arrays[2],
            other = arrays[3]
        )
    }

    /**
     * Compute Mel-Frequency Cepstral Coefficients for the sample.
     *
     * @param nMfcc Number of coefficients to extract per frame. Typical
     *        values: 13 (baseline), 20 (richer), 40 (research-grade).
     *        The native side clamps to its supported range.
     * @return A flat `FloatArray` of `[nFrames × nMfcc]` coefficients,
     *         or null on failure.
     */
    fun extractMfcc(
        pcmData: FloatArray,
        sampleRate: Int,
        nMfcc: Int
    ): FloatArray? = safeCall(null) {
        nativeExtractMfcc(pcmData, sampleRate, nMfcc)
    }

    /**
     * Compute a high-dimensional acoustic embedding for the sample.
     *
     * The embedding is what feeds [vectorSearch] and [acousticSimilarity].
     * Typical dimensions: 128 or 256 floats.
     *
     * @return Embedding vector, or null on failure.
     */
    fun generateEmbedding(pcmData: FloatArray, sampleRate: Int): FloatArray? =
        safeCall(null) { nativeGenerateEmbedding(pcmData, sampleRate) }

    /**
     * Rank [database] entries by cosine similarity to [query] and
     * return the top-K indices.
     *
     * Indices are returned in descending similarity order (index 0 is
     * the best match). If `topK` exceeds the database size, the full
     * database is returned in ranked order.
     *
     * @return `IntArray` of indices into [database], or null on failure.
     */
    fun vectorSearch(
        query: FloatArray,
        database: Array<FloatArray>,
        topK: Int
    ): IntArray? = safeCall(null) { nativeVectorSearch(query, database, topK) }

    /**
     * Cosine similarity between two embeddings.
     *
     * @return Similarity in `[-1f, 1f]`, or `Float.NaN` if the
     *         computation is unavailable (library not loaded, ML not
     *         initialised, or any native error).
     */
    fun acousticSimilarity(a: FloatArray, b: FloatArray): Float =
        safeCall(Float.NaN) { nativeAcousticSimilarity(a, b) }

    /**
     * Classify genres, moods, and instruments for the sample.
     *
     * @return Structured tags with per-label confidence, or null on
     *         failure. Empty maps are a valid result for audio that
     *         doesn't match any of the model's categories.
     */
    fun autoTag(pcmData: FloatArray, sampleRate: Int): MlTagResult? {
        val raw = safeCall(null) { nativeAutoTag(pcmData, sampleRate) } ?: return null
        val genres = mutableMapOf<String, Float>()
        val moods = mutableMapOf<String, Float>()
        val instruments = mutableMapOf<String, Float>()
        for (entry in raw) {
            // Native format: "G|Pop|0.92" / "M|Energetic|0.71" / "I|Guitar|0.65"
            val parts = entry.split('|', limit = 3)
            if (parts.size != 3) continue
            val score = parts[2].toFloatOrNull() ?: continue
            when (parts[0]) {
                "G" -> genres[parts[1]] = score
                "M" -> moods[parts[1]] = score
                "I" -> instruments[parts[1]] = score
            }
        }
        return MlTagResult(
            genres = genres,
            moods = moods,
            instruments = instruments
        )
    }

    /**
     * Generate an ordered queue from a set of candidate tracks.
     *
     * @param seedEmbeddings Embeddings of the currently-playing track(s)
     *        that define the "direction" of the queue.
     * @param candidateEmbeddings Embeddings of tracks eligible for the
     *        queue. Must be the same length as [candidateIds].
     * @param candidateIds Stable string IDs matching
     *        [candidateEmbeddings] by index. Returned IDs are a subset
     *        of this list, ordered as the queue.
     * @param maxSize Maximum queue size. The returned array length is
     *        `min(maxSize, candidateIds.size)`.
     * @return Ordered candidate IDs, or null on failure.
     */
    fun generateQueue(
        seedEmbeddings: Array<FloatArray>,
        candidateEmbeddings: Array<FloatArray>,
        candidateIds: Array<String>,
        maxSize: Int
    ): Array<String>? = safeCall(null) {
        nativeGenerateQueue(
            seedEmbeddings,
            candidateEmbeddings,
            candidateIds,
            maxSize
        )
    }

    /**
     * Detect the musical key of the sample.
     *
     * @return MIDI key number (0 = C, 11 = B, mode encoded in the upper
     *         bits — see `MlJni.cpp` for the exact encoding, which
     *         matches the Camelot wheel used in the DJ Studio), or `-1`
     *         if unavailable.
     */
    fun detectKey(pcmData: FloatArray, sampleRate: Int): Int =
        safeCall(-1) { nativeDetectKey(pcmData, sampleRate) }

    /**
     * Predict the loudness (LUFS) of the sample for normalisation
     * purposes.
     *
     * Unlike the deterministic ReplayGain 2.0 pipeline (Phase 4),
     * this is the ML-based predictor (feature #110) used when no
     * replaygain tag exists. Negative values are expected — LUFS
     * targets are around -14 to -23.
     *
     * @return Predicted LUFS, or `Float.NaN` if unavailable.
     */
    fun predictLoudness(pcmData: FloatArray, sampleRate: Int): Float =
        safeCall(Float.NaN) { nativePredictLoudness(pcmData, sampleRate) }

    // ── Audio transformation ─────────────────────────────────────────

    /**
     * Extract the vocal component for karaoke mode (feature #111).
     *
     * For clean separation, use [separateStems] and take the `vocals`
     * stem. This method exists for the low-latency single-call path
     * used in real-time karaoke mode, where allocating a 4-stem result
     * would waste memory and CPU.
     *
     * @return Vocal-only audio at the input sample rate, or null on
     *         failure.
     */
    fun extractVocals(pcmData: FloatArray, sampleRate: Int): FloatArray? =
        safeCall(null) { nativeExtractVocals(pcmData, sampleRate) }

    /**
     * Classify the sample as speech or music (feature #112).
     *
     * @return Probability that the sample is speech, in `[0f, 1f]`, or
     *         `Float.NaN` if unavailable.
     */
    fun classifySpeechMusic(pcmData: FloatArray, sampleRate: Int): Float =
        safeCall(Float.NaN) { nativeClassifySpeechMusic(pcmData, sampleRate) }

    /**
     * Score how strongly a track matches a user's affinity model
     * (feature #113).
     *
     * @param userEmbedding The user's aggregated preference vector.
     * @param trackEmbedding The candidate track's embedding.
     * @return Affinity score in `[0f, 1f]`, or `Float.NaN` if
     *         unavailable.
     */
    fun scoreRecommendation(
        userEmbedding: FloatArray,
        trackEmbedding: FloatArray
    ): Float = safeCall(Float.NaN) {
        nativeScoreRecommendation(userEmbedding, trackEmbedding)
    }

    /**
     * Predict the probability that the user will skip this track given
     * their current context (feature #114).
     *
     * @param contextEmbedding Vector encoding time-of-day, recent skips,
     *        and current activity.
     * @param trackEmbedding The candidate track's embedding.
     * @return Skip probability in `[0f, 1f]`, or `Float.NaN` if
     *         unavailable.
     */
    fun predictSkip(
        contextEmbedding: FloatArray,
        trackEmbedding: FloatArray
    ): Float = safeCall(Float.NaN) {
        nativePredictSkip(contextEmbedding, trackEmbedding)
    }

    /**
     * Compute a compact acoustic fingerprint for the sample
     * (feature #115).
     *
     * Fingerprints are designed for cross-filename duplicate detection:
     * two encodings of the same recording should produce identical or
     * near-identical fingerprints.
     *
     * @return Fingerprint bytes, or null on failure.
     */
    fun fingerprint(pcmData: FloatArray, sampleRate: Int): ByteArray? =
        safeCall(null) { nativeFingerprint(pcmData, sampleRate) }

    /**
     * Align plain-text lyric lines to a waveform (feature #118).
     *
     * Provides the native implementation of the ML alignment strategy
     * that `OfflineLyricSynchronizer.LineAligner` calls into. The
     * Kotlin-side `OfflineLyricSynchronizer` (data/nowplaying/) supplies
     * a proportional-duration heuristic when this returns null.
     *
     * @param plainText The lyric text, one line per newline.
     * @param pcmData The mixed audio waveform the alignment is performed
     *        against.
     * @param sampleRate Sample rate in Hz, matching [pcmData].
     * @return One start time in milliseconds per lyric line, in input
     *         order, or null on failure.
     */
    fun alignLyrics(
        plainText: String,
        pcmData: FloatArray,
        sampleRate: Int
    ): LongArray? = safeCall(null) {
        nativeAlignLyrics(plainText, pcmData, sampleRate)
    }

    /**
     * Upscale compressed audio to synthesize missing high frequencies
     * (feature #121).
     *
     * @return Upscaled audio at the input sample rate, or null on
     *         failure.
     */
    fun upscale(pcmData: FloatArray, sampleRate: Int): FloatArray? =
        safeCall(null) { nativeUpscale(pcmData, sampleRate) }

    /**
     * Remove background noise (hiss, hum, vinyl crackle) from the
     * sample (feature #122).
     *
     * @return Denoised audio at the input sample rate, or null on
     *         failure.
     */
    fun denoise(pcmData: FloatArray, sampleRate: Int): FloatArray? =
        safeCall(null) { nativeDenoise(pcmData, sampleRate) }

    /**
     * Isolate a specific instrument from the mix (feature #123).
     *
     * @param instrumentId Instrument identifier, matching the taxonomy
     *        used by the native `InstrumentSoloIsolator` (see
     *        `MlJni.cpp` for the currently-defined ID constants).
     * @return Instrument-isolated audio at the input sample rate, or
     *         null on failure.
     */
    fun isolateInstrument(
        pcmData: FloatArray,
        sampleRate: Int,
        instrumentId: Int
    ): FloatArray? = safeCall(null) {
        nativeIsolateInstrument(pcmData, sampleRate, instrumentId)
    }

    // ═════════════════════════════════════════════════════════════════
    // Safe-call helpers
    // ═════════════════════════════════════════════════════════════════

    /**
     * Execute [block] with uniform error handling.
     *
     * Three failure modes are caught and converted to `default`:
     *   • Native library not loaded          (libraryAvailable == false)
     *   • Native method not linked           (UnsatisfiedLinkError)
     *   • Native method threw at runtime     (Exception)
     *
     * Everything else — including `OutOfMemoryError`, `StackOverflowError`,
     * and `CancellationException` — propagates. Those represent a broken
     * process, not a recoverable ML failure, and swallowing them would
     * hide bugs.
     */
    private fun <T> safeCall(default: T, block: () -> T): T {
        if (!libraryAvailable) return default
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native method not linked — MlJni.cpp likely not compiled", e)
            default
        } catch (e: Exception) {
            Log.e(TAG, "Native ML call failed", e)
            default
        }
    }

    /**
     * Same as [safeCall] but for `Unit`-returning methods.
     */
    private fun safeCallUnit(block: () -> Unit) {
        if (!libraryAvailable) return
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native method not linked — MlJni.cpp likely not compiled", e)
        } catch (e: Exception) {
            Log.e(TAG, "Native ML call failed", e)
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Native declarations
    // ═════════════════════════════════════════════════════════════════
    //
    // Private so Kotlin callers cannot bypass the safe wrappers above.
    // JNI ignores Kotlin visibility — MlJni.cpp resolves these by their
    // `Java_com_futura_engine_ml_MlBridge_nativeXxx` names regardless.
    //
    // The `thiz` parameter on the native side is a `jobject` (singleton
    // instance), not a `jclass` — see the file header's JNI naming
    // contract.

    private external fun nativeInit(
        modelsDir: String,
        useGpu: Boolean,
        numThreads: Int
    ): Boolean

    private external fun nativeClose()

    private external fun nativeSeparateStems(
        pcmData: FloatArray,
        sampleRate: Int
    ): Array<FloatArray>?

    private external fun nativeExtractMfcc(
        pcmData: FloatArray,
        sampleRate: Int,
        nMfcc: Int
    ): FloatArray?

    private external fun nativeGenerateEmbedding(
        pcmData: FloatArray,
        sampleRate: Int
    ): FloatArray?

    private external fun nativeVectorSearch(
        query: FloatArray,
        database: Array<FloatArray>,
        topK: Int
    ): IntArray?

    private external fun nativeAcousticSimilarity(
        a: FloatArray,
        b: FloatArray
    ): Float

    private external fun nativeAutoTag(
        pcmData: FloatArray,
        sampleRate: Int
    ): Array<String>?

    private external fun nativeGenerateQueue(
        seedEmbeddings: Array<FloatArray>,
        candidateEmbeddings: Array<FloatArray>,
        candidateIds: Array<String>,
        maxSize: Int
    ): Array<String>?

    private external fun nativeDetectKey(
        pcmData: FloatArray,
        sampleRate: Int
    ): Int

    private external fun nativePredictLoudness(
        pcmData: FloatArray,
        sampleRate: Int
    ): Float

    private external fun nativeExtractVocals(
        pcmData: FloatArray,
        sampleRate: Int
    ): FloatArray?

    private external fun nativeClassifySpeechMusic(
        pcmData: FloatArray,
        sampleRate: Int
    ): Float

    private external fun nativeScoreRecommendation(
        userEmbedding: FloatArray,
        trackEmbedding: FloatArray
    ): Float

    private external fun nativePredictSkip(
        contextEmbedding: FloatArray,
        trackEmbedding: FloatArray
    ): Float

    private external fun nativeFingerprint(
        pcmData: FloatArray,
        sampleRate: Int
    ): ByteArray?

    private external fun nativeAlignLyrics(
        plainText: String,
        pcmData: FloatArray,
        sampleRate: Int
    ): LongArray?

    private external fun nativeUpscale(
        pcmData: FloatArray,
        sampleRate: Int
    ): FloatArray?

    private external fun nativeDenoise(
        pcmData: FloatArray,
        sampleRate: Int
    ): FloatArray?

    private external fun nativeIsolateInstrument(
        pcmData: FloatArray,
        sampleRate: Int,
        instrumentId: Int
    ): FloatArray?
}