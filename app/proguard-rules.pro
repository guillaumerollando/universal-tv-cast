# Pont JavaScript du navigateur intégré
-keepclassmembers class com.rollandostudio.universaltvcast.browser.VideoSniffer$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Google Cast : la classe de réglages est lue par son nom dans le manifeste
-keep class com.rollandostudio.universaltvcast.cast.CastOptionsProvider { *; }
