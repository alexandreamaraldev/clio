# UniFFI bindings call into the Rust library through JNA, which reflects on these classes.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { public *; }
-dontwarn java.awt.**
-keep class uniffi.clio_ffi.** { *; }
