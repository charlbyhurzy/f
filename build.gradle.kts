//
// Root Gradle build script for the Futura Audio multi-module project.
// Declares every Gradle plugin used across the build once, with `apply false`,
// so individual modules (:app, :engine) can apply them without version clashes.
//
// Module-level dependencies (Compose, Hilt, Room, Oboe, Retrofit, etc.) are
// declared in the corresponding sub-module build scripts.

plugins {
    // ── Android Gradle Plugin ────────────────────────────────────────────
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false

    // ── Kotlin ───────────────────────────────────────────────────────────
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false

    // ── Dependency Injection ─────────────────────────────────────────────
    id("com.google.dagger.hilt.android") version "2.52" apply false

    // ── Annotation / Symbol Processing (Room, Hilt, Moshi) ───────────────
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

// Wipes every module's build output in one shot.
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
