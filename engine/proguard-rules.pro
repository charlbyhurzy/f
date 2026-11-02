#
# R8 / ProGuard rules for the :engine native module.
#
# The C++ JNI layer (JniBridge.cpp, DspJni.cpp, MlJni.cpp, WaveformJni.cpp,
# StreamInspectorJni.cpp, CrashHandlerJni.cpp, UsbAudioJni.cpp,
# BitPerfectJni.cpp) looks up Kotlin classes and methods by their exact
# fully-qualified names and signatures via GetStaticMethodID /
# GetMethodID / FindClass. Any renaming or removal by R8 will crash the
# native code at runtime, so every JNI-facing type, field and method must be
# preserved.

-keep class com.futura.engine.NativeEngine { *; }
-keep class com.futura.engine.NativePlayerBridge { *; }
-keep class com.futura.engine.NativeUsbAudio { *; }
-keep class com.futura.engine.DspBridge { *; }
-keep class com.futura.engine.DspChainController { *; }
-keep class com.futura.engine.AudioTap { *; }
-keep class com.futura.engine.LockFreeRingBufferTap { *; }
-keep class com.futura.engine.AudioTapSubscription { *; }
-keep class com.futura.engine.NativeFlacScanner { *; }
-keep class com.futura.engine.NativeWaveform { *; }

-keep class com.futura.engine.MemoryProfileEntry { *; }
-keep class com.futura.engine.SimilarHit { *; }
-keep class com.futura.engine.KeyResult { *; }
-keep class com.futura.engine.VolumePrediction { *; }
-keep class com.futura.engine.TagResult { *; }
-keep class com.futura.engine.LyricLineK { *; }
-keep class com.futura.engine.NativeScanResult { *; }
-keep class com.futura.engine.EngineStats { *; }
-keep class com.futura.engine.BitPerfectState { *; }

-keep class com.futura.engine.ml.MlBridge { *; }
-keep class com.futura.engine.ml.MlAnalyzer { *; }
-keep class com.futura.engine.ml.InferenceThreadScheduler { *; }
-keep class com.futura.engine.ml.SmartAlarmWakeOptimizer { *; }
-keep class com.futura.engine.ml.ContextMoodEngine { *; }
-keep class com.futura.engine.ml.NlPlaylistGenerator { *; }
-keep class com.futura.engine.ml.OfflineLyricSynchronizer { *; }

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepclassmembers class * {
    @com.futura.engine.KeepForJni <methods>;
}
-keep @com.futura.engine.KeepForJni class * { *; }

-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

-keep class com.futura.engine.EngineException { *; }
-keep class com.futura.engine.DspException { *; }
-keep class com.futura.engine.MlInferenceException { *; }

-keepclassmembers class kotlinx.coroutines.flow.** { *; }
-dontwarn kotlinx.coroutines.**

-dontwarn org.tensorflow.lite.**
-dontwarn ai.onnxruntime.**

-keepclassmembers class com.futura.engine.NativeEngine {
    static native <methods>;
    static <clinit>;
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
