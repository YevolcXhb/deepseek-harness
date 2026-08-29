# DSH Mobile ProGuard rules

# Keep all DSH classes
-keep class com.dsh.mobile.** { *; }

# Keep WebView JS interface (required for @JavascriptInterface)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Process and Runtime related classes (proot spawning)
-keep class java.lang.Process { *; }
-keep class java.lang.Runtime { *; }

# Don't warn about missing optional classes
-dontwarn javax.annotation.**
