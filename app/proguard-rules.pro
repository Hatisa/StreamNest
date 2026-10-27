# Keep Media3 / ExoPlayer defaults (they ship consumer rules, but be explicit).
-dontwarn androidx.media3.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.streamnest.tv.** {
    *** Companion;
}
-keepclasseswithmembers class com.streamnest.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Keep our enum / data classes used by reflection-free parsing is not needed,
# but keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
