# Pont JavaScript du navigateur intégré
-keepclassmembers class com.rollandostudio.universaltvcast.browser.VideoSniffer$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Google Cast : la classe de réglages est lue par son nom dans le manifeste
-keep class com.rollandostudio.universaltvcast.cast.CastOptionsProvider { *; }

# Dossiers partagés (jcifs-ng) : classes chargées par leur nom, et dépendances facultatives absentes d'Android
-keep class jcifs.internal.** { <init>(...); }
# Chiffrement chargé par son nom par BouncyCastle : MD4 (mot de passe NTLM) et AES-CMAC (signature SMB 3)
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.digest.MD4** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.AES** { *; }
-dontwarn jcifs.**
-dontwarn javax.servlet.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
