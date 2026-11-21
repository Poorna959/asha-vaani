# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ashavaani.app.model.**$$serializer { *; }
-keepclassmembers class com.ashavaani.app.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.ashavaani.app.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
