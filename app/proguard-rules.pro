# ProGuard / R8 rules for release builds.
#
# The default rules from proguard-android-optimize.txt handle Android framework
# classes. Below we add app-specific rules for libraries we use that need
# specific keep/treatment.

# ----- Kotlin Coroutines -----
# Coroutines uses reflection to look up internal continuation classes.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ----- Gson (used for JSON parsing of provider responses) -----
# Gson uses reflection to deserialize into the model classes. Keep their
# names and constructors so the deserialized objects don't become {nulls}.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep all data classes in our model package — Gson reads their fields by
# name and we don't want R8 renaming the fields.
-keep class com.qtone.app.model.** { *; }
-keep class com.qtone.app.network.** { *; }

# ----- OkHttp -----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# ----- Coil (image loading) -----
-dontwarn coil.**

# ----- Compose -----
# Compose Compiler generates code that R8 sometimes mis-optimizes. Keep
# composable lambdas safe.
-keep class androidx.compose.runtime.** { *; }

# ----- Media3 / ExoPlayer -----
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ----- Generic safety -----
# Keep enums — they're often used in switch/when statements that R8 can't
# always prove safe to obfuscate.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep ViewModels — Android instantiates them by class name via reflection.
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

# Keep Activities and Application class.
-keep class com.qtone.app.MainActivity
-keep class com.qtone.app.QtoneApp
-keep class com.qtone.app.player.PlayerActivity
