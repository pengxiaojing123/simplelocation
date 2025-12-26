# Consumer ProGuard rules for location module

# Keep location SDK classes
-keep class com.iki.location.** { *; }
-keepclassmembers class com.iki.location.** { *; }

# Keep GMS location classes
-keep class com.google.android.gms.location.** { *; }



