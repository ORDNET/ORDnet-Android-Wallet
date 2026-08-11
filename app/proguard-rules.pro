# Keep the JS bridge interfaces used via addJavascriptInterface
-keepclassmembers class io.ordnet.wallet.ui.browser.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
