package com.rollandostudio.universaltvcast.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Le pack soutien : un achat unique et volontaire qui ajoute des touches de style (couleurs, fond OLED, écran
 * d'attente de la télé, icônes). Rien d'utile n'en dépend : l'appli reste entière et sans pub pour tout le monde.
 * L'achat lui-même passe par la boutique de chaque version (`SupportStore`) ; la version libre a tout d'office.
 */
object Supporter {
    internal val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked
    /** Prix affiché par la boutique (« 2,99 € »), dès qu'il est connu. */
    internal val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price
}
