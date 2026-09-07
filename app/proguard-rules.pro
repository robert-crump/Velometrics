# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- Enabling R8 minification on release builds ---

# Garmin FIT SDK dispatches to Mesg/Field classes by reflection based on the message type read
# from the .fit file, not from static references R8 can see — without this the importer silently
# fails to parse most message types in a release build.
-keep class com.garmin.fit.** { *; }
-dontwarn com.garmin.fit.**

# Dropbox SDKs (core + Android) — core-sdk is primarily a server-side Java SDK repurposed here for
# OAuth + file listing; it and its JSON (de)serialization rely on reflection over its own model
# classes, and pulls in some javax.* references that don't exist on Android.
-keep class com.dropbox.core.** { *; }
-dontwarn com.dropbox.core.**
-dontwarn javax.annotation.**

# Gson: keep annotated fields' names/structure so reflection-based (de)serialization of our own
# model classes (e.g. NominatimResult, MapEdgeEntity) still round-trips correctly once obfuscated.
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken