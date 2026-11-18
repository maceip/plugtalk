# Trap JNI entry points invoked across the Rust <-> Kotlin boundary.
-keep class dev.onionvault.OvNative { *; }
-keep class dev.onionvault.OvBridge { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

# Cefpack & Chromium JNI-Zero / CalledByNative reflection surfaces.
-keep class dev.cefpack.** { *; }
-keep class org.chromium.** { *; }
-keep class org.jni_zero.** { *; }
-dontwarn dev.cefpack.**
-dontwarn org.chromium.**
-dontwarn org.jni_zero.**
