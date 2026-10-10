# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# Issue #182: Keep RealTimeCutVAD library classes and JNI bindings from R8 stripping
-keep class io.codeconcept.realtimecutvadlibrary.** { *; }

# The virtual-screen service runs in a separate root process (libsu RootService; libsu's own
# consumer rules keep RootService subclasses). The generated AIDL descriptor is part of the Binder
# contract between the two processes; keep the service, both interfaces and their stubs stable.
-keep class com.openminis.app.tools.android.vscreen.service.VirtualScreenUserService { *; }
-keep interface com.openminis.app.tools.android.vscreen.IVirtualScreenService { *; }
-keep class com.openminis.app.tools.android.vscreen.IVirtualScreenService$Stub { *; }
-keep interface com.openminis.app.tools.android.vscreen.IVirtualScreenFrameSink { *; }
-keep class com.openminis.app.tools.android.vscreen.IVirtualScreenFrameSink$Stub { *; }
