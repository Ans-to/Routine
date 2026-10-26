# Preserve generic type signatures and annotations for R8 full mode (Room, Hilt, kotlinx.serialization)
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# Keep SQLCipher JNI classes and native callback descriptors
-keep,includedescriptorclasses class net.zetetic.** { *; }
-dontwarn net.zetetic.database.**

# Keep Room database, DAOs, entities, and type converters
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class app.routine.core.data.local.** { *; }

# Keep internal app_process Binder privilege server entrypoint (invoked by class name via CLASSPATH)
-keep class app.routine.privilege.adb.binder.** { *; }

# Keep Hilt WorkManager CoroutineWorker classes
-keep class * extends androidx.work.ListenableWorker { *; }

# Keep all Routine enums (used in kotlinx.serialization, Room columns, and Compose rememberSaveable)
-keep enum app.routine.** { *; }

# Keep kotlinx.serialization @Serializable domain models and generated serializers in R8 full mode
-dontnote kotlinx.serialization.AnnotationsKt
-keep @kotlinx.serialization.Serializable class app.routine.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.routine.**$$serializer { *; }
-keepclassmembers class app.routine.** {
    *** Companion;
}
-keepclasseswithmembers class app.routine.** {
    kotlinx.serialization.KSerializer serializer(...);
}
