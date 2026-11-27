package com.rollandostudio.universaltvcast.data

import android.app.Activity
import android.content.Context

/**
 * Version libre (F-Droid, GitHub) : pas de boutique ni de bibliothèque de paiement (non libres). Tout est ouvert,
 * pack soutien compris : chacun peut de toute façon recompiler l'appli (GPL).
 */
object SupportStore {
    fun prepare(ctx: Context) { Supporter._unlocked.value = true }
    fun restore() = Unit
    fun buy(activity: Activity): Boolean = false
}
