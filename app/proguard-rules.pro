# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*
-dontobfuscate

# Keep Coil
-keep class coil.** { *; }

# Kotlin serialization looks up the generated serializer classes through a function on companion
# objects. The companions are looked up reflectively so we need to explicitly keep these functions.
-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# If a companion has the serializer function, keep the companion field on the original type so that
# the reflective lookup succeeds.
-if class **.*$Companion {
  kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class <1>.<2> {
  <1>.<2>$Companion Companion;
}

# TODO: Waiting for new retrofit release to remove these rules
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Don't warn
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.conscrypt.**

# libmpv JNI 绑定类与回调接口必须保留方法签名及类名
-keep class dev.jdtech.mpv.MPVLib { *; }
-keep interface dev.jdtech.mpv.MPVLib$EventObserver { *; }
-keep interface dev.jdtech.mpv.MPVLib$LogObserver { *; }
-keep class dev.jdtech.mpv.MPVLib$* { *; }