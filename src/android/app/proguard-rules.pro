# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# Issue #182: Keep RealTimeCutVAD library classes and JNI bindings from R8 stripping
-keep class io.codeconcept.realtimecutvadlibrary.** { *; }
