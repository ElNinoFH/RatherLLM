# ── JNI boundary ────────────────────────────────────────────────────────────
# libratherllm.so resolves these by their fully-qualified name
# (Java_com_kotlin_ratherllm_NativeBridge_*), so the class and its external
# (native) methods must not be renamed or removed by R8.
-keep class com.kotlin.ratherllm.NativeBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.kotlin.ratherllm.NativeBridge {
    native <methods>;
}

# Callback interfaces invoked from native via GetMethodID(name, signature).
# Keep the interfaces and the exact method names/signatures.
-keep class com.kotlin.ratherllm.TokenCallback { *; }
-keep class com.kotlin.ratherllm.LoadProgressCallback { *; }
-keepclassmembers class * implements com.kotlin.ratherllm.TokenCallback {
    boolean onToken(java.lang.String);
}
-keepclassmembers class * implements com.kotlin.ratherllm.LoadProgressCallback {
    void onProgress(float);
}

# Honour androidx.annotation.Keep (already applied to the callbacks).
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
