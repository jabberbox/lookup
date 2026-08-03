# WebView: no @JavascriptInterface is used, so no special keep rules are needed.
# Keep our single activity so the manifest reference survives shrinking.
-keep class com.lookup.browser.MainActivity { *; }
