# The installer's R8 rules (build.gradle: minifyEnabled true).
# Our own code: small, and the web server, receivers and JSON handlers are easier to keep whole than to list.
-keep class com.gathin.kit.** { *; }
# libadb-android: native methods (SPAKE2 pairing) and classes made from native code.
-keep class io.github.muntashirakon.** { *; }
# Conscrypt (TLS for adb pairing): its native library calls back into these classes by name.
-keep class org.conscrypt.** { *; }
# The certificate classes the adb key is made with.
-keep class android.sun.** { *; }
-dontwarn **
-dontobfuscate
