package com.rollandostudio.universaltvcast.ui

import android.app.Activity

/** Version libre (F-Droid, GitHub) : pas de Play Store, donc pas de demande d'avis ni de ligne « Noter ». */
object StoreReview {
    const val AVAILABLE = false
    fun ask(activity: Activity) = Unit
    fun openStore(activity: Activity) = Unit
}
