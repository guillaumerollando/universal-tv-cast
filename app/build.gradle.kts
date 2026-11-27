plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rollandostudio.universaltvcast"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rollandostudio.universaltvcast"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    androidResources {
        // Choix de la langue de l'appli dans les réglages d'Android (13 et plus).
        generateLocaleConfig = true
        // Langues de l'appli (les traductions des bibliothèques dans d'autres langues sont écartées : APK plus léger).
        localeFilters += listOf(
            "en", "fr", "es", "de", "it", "nl", "pt-rBR",
            "ja", "ko", "zh-rCN", "zh-rTW", "ru", "uk", "pl", "cs", "ar", "tr", "hi", "in", "sv", "ro", "vi", "th",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signature de debug en attendant la clé du studio (appli de test perso).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // Deux versions tirées du même code :
    //  - « play » : la version publiée, avec Google Cast (bibliothèque de Google, non libre) en plus du DLNA ;
    //  - « libre » : sans aucune bibliothèque non libre (pour F-Droid) ; DLNA seul, Google Cast absent.
    flavorDimensions += "store"
    productFlavors {
        // Lien envoyé par « Partager l'appli » : la fiche du store d'où vient cette version.
        create("play") {
            dimension = "store"
            buildConfigField("String", "SHARE_URL", "\"https://play.google.com/store/apps/details?id=com.rollandostudio.universaltvcast\"")
        }
        create("libre") {
            dimension = "store"
            versionNameSuffix = "-libre"
            buildConfigField("String", "SHARE_URL", "\"https://github.com/guillaumerollando/universal-tv-cast\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("dev.chrisbanes.haze:haze:1.6.4") // flou derrière la barre d’onglets
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.webkit:webkit:1.13.0")
    // Google Cast : version Play seulement.
    "playImplementation"("com.google.android.gms:play-services-cast-framework:22.1.0")
    "playImplementation"("androidx.mediarouter:mediarouter:1.7.0")
    // Avis sur le Play Store, sans quitter l'appli (version Play).
    "playImplementation"("com.google.android.play:review-ktx:2.0.2")
    // Pack soutien (achat unique, Google Play Billing ; même version que les jeux du studio).
    "playImplementation"("com.android.billingclient:billing-ktx:9.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    // Lecture sur le téléphone (vidéos du web, HLS, sous-titres) : le lecteur standard d'Android.
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    // RTSP et RTMP (listes de chaînes) : lus par le lecteur du téléphone.
    implementation("androidx.media3:media3-exoplayer-rtsp:1.6.1")
    implementation("androidx.media3:media3-datasource-rtmp:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    // Dossiers partagés du réseau (Windows, Mac, NAS : SMB 1 à 3).
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
