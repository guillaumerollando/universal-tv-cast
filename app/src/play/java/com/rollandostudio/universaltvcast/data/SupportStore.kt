package com.rollandostudio.universaltvcast.data

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Le pack soutien acheté sur Google Play (achat unique, produit « pack_soutien » à créer dans la Play Console).
 * L'achat est retenu sur le téléphone (marche hors ligne) et revérifié auprès de Google à chaque ouverture ;
 * un remboursement le retire. Google garde 15 % et gère la TVA ; l'appli ne voit jamais la carte.
 */
object SupportStore {
    private const val PRODUCT = "pack_soutien"
    private const val TAG = "TvCastSupport"
    private lateinit var app: Context
    private var client: BillingClient? = null
    private var details: ProductDetails? = null

    private val prefs get() = app.getSharedPreferences("support", Context.MODE_PRIVATE)

    fun prepare(ctx: Context) {
        app = ctx.applicationContext
        // Version de test (débogage) : le pack est ouvert, pour pouvoir le voir sans boutique.
        Supporter._unlocked.value = prefs.getBoolean("owned", false) || com.rollandostudio.universaltvcast.BuildConfig.DEBUG
        if (client != null) return
        client = BillingClient.newBuilder(app)
            .setListener { r, purchases -> if (r.responseCode == BillingClient.BillingResponseCode.OK) purchases?.let(::handle) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        connect()
    }

    private fun connect() {
        client?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode != BillingClient.BillingResponseCode.OK) { Log.i(TAG, "boutique indisponible : ${r.debugMessage}"); return }
                loadPrice()
                restore()
            }
            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun loadPrice() {
        val q = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT).setProductType(BillingClient.ProductType.INAPP).build(),
        )).build()
        client?.queryProductDetailsAsync(q) { r, res ->
            if (r.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            details = res.productDetailsList.firstOrNull()
            Supporter._price.value = details?.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    /** Revérifie l'achat auprès de Google (remboursé : le pack se referme, sans rien effacer des choix). */
    fun restore() {
        val c = client ?: return
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, list ->
            if (r.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val mine = list.filter { PRODUCT in it.products }
            if (mine.isEmpty()) setOwned(false) else handle(mine)
        }
    }

    private fun handle(purchases: List<Purchase>) {
        for (p in purchases.filter { PRODUCT in it.products }) {
            if (p.purchaseState != Purchase.PurchaseState.PURCHASED) continue // paiement en attente : rien encore
            setOwned(true)
            if (!p.isAcknowledged) client?.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build(),
            ) { r -> Log.i(TAG, "achat confirmé : ${r.responseCode}") }
        }
    }

    private fun setOwned(on: Boolean) {
        prefs.edit().putBoolean("owned", on).apply()
        Supporter._unlocked.value = on || com.rollandostudio.universaltvcast.BuildConfig.DEBUG
    }

    /** Ouvre la fenêtre d'achat de Google. Faux si la boutique n'est pas prête (pas de réseau, produit absent). */
    fun buy(activity: Activity): Boolean {
        val d = details ?: run { connect(); return false }
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build(),
        )).build()
        return client?.launchBillingFlow(activity, params)?.responseCode == BillingClient.BillingResponseCode.OK
    }
}
