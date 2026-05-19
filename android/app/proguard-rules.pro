# Keep youtubedl-android JNI surface
-keep class com.yausername.** { *; }
-keep class io.github.junkfood02.** { *; }
-dontwarn com.yausername.**

# commons-compress — youtubedl-android pulls this in for extracting yt-dlp.zip;
# AsiExtraField is loaded reflectively by ExtraFieldUtils.<clinit>. Stripping it
# crashed release builds at launch on Android 14 (see PRIVATE_NOTES.local.md).
-keep class org.apache.commons.compress.archivers.zip.AsiExtraField { *; }
-keep class org.apache.commons.compress.archivers.zip.ExtraFieldUtils { *; }
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# Compose runtime / Coil 3 / OkHttp / coroutines — keep their reflection surfaces.
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlin.coroutines.** { *; }
-keep class io.coil_kt.coil3.** { *; }
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class kotlin.Metadata { *; }

# Keep all of SnagLite's own classes (small surface, avoids stripping public API the
# Compose UI calls into reflectively via @Composable inspection).
-keep class com.patron.snaglite.** { *; }
