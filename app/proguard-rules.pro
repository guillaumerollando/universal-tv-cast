# Pont JavaScript du navigateur intégré
-keepclassmembers class studio.rollando.castor.browser.VideoSniffer$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Google Cast : la classe de réglages est lue par son nom dans le manifeste
-keep class studio.rollando.castor.cast.CastOptionsProvider { *; }
