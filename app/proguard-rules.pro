# Keep all JNI-facing native methods — R8 must not rename or strip these
-keepclassmembers class * {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the entire transchat package to preserve JNI name mangling
-keep class com.simonpena.transchat.** { *; }

# Keep WhisperLib companion object specifically
-keep class com.simonpena.transchat.WhisperLib { *; }
-keep class com.simonpena.transchat.WhisperLib$Companion { *; }
