# kotlinx.serialization: keep generated serializers for our API models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class app.calorietracker.**$$serializer { *; }
-keepclassmembers class app.calorietracker.** {
    *** Companion;
}
-keepclasseswithmembers class app.calorietracker.** {
    kotlinx.serialization.KSerializer serializer(...);
}
