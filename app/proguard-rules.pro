# ============================================================================
# Unmuffle ProGuard/R8 Rules
# ============================================================================
# R8 mode: code shrinking ON, obfuscation OFF, resource shrinking ON
# Readable class names for crash reports (open-source F-Droid app)
# ============================================================================

# --- Global flags -----------------------------------------------------------
-dontobfuscate

# --- JNI bridge -------------------------------------------------------------
# WhisperLib: C++ resolves methods by fully-qualified JNI name
# (Java_com_simonpena_unmuffle_WhisperLib_00024Companion_*)
# Both the class and Companion must survive with original names and members
-keep class com.simonpena.unmuffle.WhisperLib { *; }
-keep class com.simonpena.unmuffle.WhisperLib$Companion { *; }

# --- Sealed class hierarchies -----------------------------------------------
# R8 can trace these via when() expressions, but we keep them defensively.
# Cost: negligible. Risk without: potential ClassNotFoundException at runtime.

# DownloadViewModel.State and subclasses
-keep class com.simonpena.unmuffle.DownloadViewModel$State { *; }
-keep class com.simonpena.unmuffle.DownloadViewModel$State$* { *; }

# UiState and subclasses (top-level sealed class in TranscriptionViewModel.kt)
-keep class com.simonpena.unmuffle.UiState { *; }
-keep class com.simonpena.unmuffle.UiState$* { *; }

# AudioCopier.CopyResult and subclasses
-keep class com.simonpena.unmuffle.AudioCopier$CopyResult { *; }
-keep class com.simonpena.unmuffle.AudioCopier$CopyResult$* { *; }

# --- Phase 5: Progress callback (uncomment when interface is added) ----------
# -keep class com.simonpena.unmuffle.ProgressCallback { *; }

# --- Notes -------------------------------------------------------------------
# ViewModels: Kept automatically by lifecycle-viewmodel library consumer rules
#   (-keep class * extends AndroidViewModel { <init>(Application); })
# Activities: Kept automatically by R8 via AndroidManifest.xml entry points
# Native methods: Kept by proguard-android-optimize.txt default rules
#   (-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; })
