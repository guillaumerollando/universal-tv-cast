package com.rollandostudio.universaltvcast.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log

/** Avis sur le Play Store : la petite fenêtre officielle de Google (version Play). */
object StoreReview {
    const val AVAILABLE = true

    /**
     * Fenêtre d'avis de Google, sans quitter l'appli. Google décide lui-même s'il l'affiche (il en limite le nombre) :
     * on ne sait jamais si elle est apparue ni ce que la personne a fait, et c'est voulu.
     */
    fun ask(activity: Activity) {
        val m = com.google.android.play.core.review.ReviewManagerFactory.create(activity)
        m.requestReviewFlow().addOnCompleteListener { t ->
            if (t.isSuccessful) m.launchReviewFlow(activity, t.result)
            else Log.i("TvCastReview", "fenêtre d'avis indisponible : ${t.exception?.message}")
        }
    }

    /** « Noter l'appli » des réglages : la fiche du Play Store. */
    fun openStore(activity: Activity) {
        val id = activity.packageName
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).setPackage("com.android.vending")) }
            .onFailure { runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))) } }
    }
}
