# Keep WebView JS interface methods if we add one later (e.g. push notification bridge).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
