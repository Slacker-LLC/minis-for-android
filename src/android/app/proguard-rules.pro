# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# Issue #182: Keep RealTimeCutVAD library classes and JNI bindings from R8 stripping
-keep class io.codeconcept.realtimecutvadlibrary.** { *; }

# Shizuku resolves the UserService by ComponentName and the generated AIDL
# descriptor is part of the Binder contract; keep both names and methods stable.
-keep class com.openminis.app.tools.android.vscreen.service.VirtualScreenUserService { *; }
-keep interface com.openminis.app.tools.android.vscreen.IVirtualScreenService { *; }
-keep class com.openminis.app.tools.android.vscreen.IVirtualScreenService$Stub { *; }
