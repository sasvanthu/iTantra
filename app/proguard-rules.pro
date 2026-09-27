# ProGuard / R8 Rules for iTantra Offline Voice Mesh

# Vosk Offline STT & JNA JNI Keep Rules
-keep class org.vosk.** { *; }
-keepclassmembers class org.vosk.** { *; }
-dontwarn org.vosk.**

-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# iTantra Protocol Data Classes and Enums
-keep class com.example.itantra.protocol.** { *; }
-keepclassmembers class com.example.itantra.protocol.** { *; }

# Speech Codec and Formant Synthesis
-keep class com.example.itantra.speech.** { *; }
-keepclassmembers class com.example.itantra.speech.** { *; }

# Transport & Mesh
-keep class com.example.itantra.transport.** { *; }
-keepclassmembers class com.example.itantra.transport.** { *; }
