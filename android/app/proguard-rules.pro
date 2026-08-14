# The blob-download bridge is only ever called from injected JavaScript, so R8
# cannot see the call sites and would otherwise strip the annotated methods.
-keepclassmembers class de.tankradar.app.DownloadBridge {
    @android.webkit.JavascriptInterface <methods>;
}
