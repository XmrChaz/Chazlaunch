# Keep JNI entry points — native code looks these up by name.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Compositor callback surface is invoked from native via JNI.
-keep class com.chazlaunch.compositor.Compositor { *; }
-keep class com.chazlaunch.compositor.ToplevelInfo { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
