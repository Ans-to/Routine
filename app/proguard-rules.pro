# Keep SQLCipher JNI classes and Room entities
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
-dontwarn net.zetetic.database.**

# Keep internal app_process Binder privilege server entrypoint (invoked by class name via CLASSPATH)
-keep class app.routine.privilege.adb.server.** { *; }

# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
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
