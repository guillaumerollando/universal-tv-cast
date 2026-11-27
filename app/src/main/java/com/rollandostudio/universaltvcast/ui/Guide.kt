package com.rollandostudio.universaltvcast.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarViewDay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.rollandostudio.universaltvcast.R
import com.rollandostudio.universaltvcast.data.Epg
import com.rollandostudio.universaltvcast.data.Iptv
import java.util.Calendar
import java.util.Locale

/** Ce qui désigne une chaîne dans les guides des programmes. */
data class GuideKey(val name: String, val country: String?, val tvgId: String?)

private val here: String get() = Locale.getDefault().country.lowercase()

private val timeFormat get() = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)

/** Progression du programme en cours, en fine barre ambrée (la même partout). */
@Composable
internal fun ProgBar(p: Epg.Prog, now: Long, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val done = ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { done }, modifier = modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
        color = c.primary, trackColor = c.surfaceContainerHighest, drawStopIndicator = {},
    )
}

/** L'heure qui avance, une fois par minute (barres de progression, programme en cours). */
@Composable
internal fun rememberMinute(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); now = System.currentTimeMillis() } }
    return now
}

/**
 * « En ce moment » : chaque chaîne avec son programme en cours, sa progression et ce qui suit. Un appui lance la
 * chaîne ; l'icône à droite ouvre la grille de la chaîne. Seules les chaînes dont un guide connaît le programme.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NowOnList(
    channels: List<Iptv.Channel>,
    state: LazyListState,
    contentPadding: PaddingValues,
    onPlay: (Iptv.Channel) -> Unit,
    onHide: (Iptv.Channel) -> Unit,
) {
    val now = rememberMinute()
    // Gardés d'une recherche à l'autre : pas de clignotement quand on tape.
    val found = remember { mutableStateMapOf<String, Pair<Epg.Prog, Epg.Prog?>>() }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(channels, now) {
        // Au plus trois pays (un guide par pays, quelques Mo chacun) : ceux des premières chaînes.
        val countries = LinkedHashSet<String>()
        for (ch in channels) {
            val cc = ch.country ?: here
            if (cc !in countries) { if (countries.size >= 3) continue; countries += cc }
            val nn = runCatching { Epg.nowNext(ch.name, cc, ch.tvgId) }.getOrNull()
            if (nn != null) found[ch.url] = nn else found.remove(ch.url)
            checked[ch.url] = true
        }
    }
    val shown = channels.filter { it.url in found }.distinctBy { Epg.key(it.name) }
    val waiting = channels.any { checked[it.url] != true }
    var guideFor by remember { mutableStateOf<Iptv.Channel?>(null) }
    val c = MaterialTheme.colorScheme
    val fmt = remember { timeFormat }
    when {
        shown.isEmpty() && waiting -> Box(Modifier.fillMaxWidth().padding(contentPadding).padding(top = 32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        shown.isEmpty() -> Text(stringResource(R.string.epg_none), color = c.onSurfaceVariant,
            modifier = Modifier.padding(contentPadding).padding(16.dp))
        else -> LazyColumn(state = state, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(shown, key = { it.url }) { ch ->
                val (cur, next) = found[ch.url] ?: return@items
                var menu by remember { mutableStateOf(false) }
                val hideLabel = stringResource(R.string.iptv_hide)
                val guideLabel = stringResource(R.string.epg_schedule)
                Box(Modifier.animateItem()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .combinedClickable(role = Role.Button, onClick = { onPlay(ch) }, onLongClick = { menu = true })
                            .semantics {
                                contentDescription = ch.name + ", " + cur.title
                                customActions = listOf(
                                    CustomAccessibilityAction(guideLabel) { guideFor = ch; true },
                                    CustomAccessibilityAction(hideLabel) { onHide(ch); true },
                                )
                            }
                            .padding(start = ChannelTiles.edge, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChannelArt(
                            name = ch.name, logo = ch.logo, streamUrl = null, pad = ChannelTiles.logoPad,
                            textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                            modifier = Modifier.width(ChannelTiles.width).aspectRatio(16f / 10f).clip(RoundedCornerShape(ChannelTiles.corner)),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(cur.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ProgBar(cur, now)
                            next?.let {
                                Text(fmt.format(java.util.Date(it.start)) + "  " + it.title, style = MaterialTheme.typography.bodySmall,
                                    color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = { guideFor = ch }) {
                            Icon(Icons.Rounded.CalendarViewDay, guideLabel, tint = c.onSurfaceVariant)
                        }
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
                        DropdownMenuItem(text = { Text(guideLabel) }, onClick = { menu = false; guideFor = ch })
                        DropdownMenuItem(text = { Text(hideLabel) }, onClick = { menu = false; onHide(ch) })
                    }
                }
            }
        }
    }
    guideFor?.let { ch ->
        ScheduleSheet(GuideKey(ch.name, ch.country ?: here, ch.tvgId), ch.logo, onDismiss = { guideFor = null })
    }
}

private class DayStart(val at: Long)

private fun dayOffset(t: Long, now: Long): Int {
    fun day(x: Long) = Calendar.getInstance().apply {
        timeInMillis = x; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return Math.round((day(t) - day(now)) / 86_400_000.0).toInt()
}

@Composable
private fun dayLabel(t: Long, now: Long): String = when (dayOffset(t, now)) {
    0 -> stringResource(R.string.epg_today)
    1 -> stringResource(R.string.epg_tomorrow)
    -1 -> stringResource(R.string.epg_yesterday)
    else -> java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"))
        .format(java.util.Date(t)).replaceFirstChar { it.titlecase() }
}

/**
 * Grille des programmes d'une chaîne, d'hier soir à demain, ouverte sur le programme en cours. Les programmes
 * passés sont estompés, celui en cours mis en avant avec sa progression.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ScheduleSheet(key: GuideKey, logo: String?, onDismiss: () -> Unit) {
    val progs by produceState<List<Epg.Prog>?>(null, key) {
        value = runCatching { Epg.schedule(key.name, key.country, key.tvgId) }.getOrNull().orEmpty()
    }
    val now = rememberMinute()
    val c = MaterialTheme.colorScheme
    val fmt = remember { timeFormat }
    val list = rememberLazyListState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelArt(
                name = key.name, logo = logo, streamUrl = null, pad = 6.dp,
                textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                modifier = Modifier.width(72.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(key.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.epg_schedule), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        val all = progs
        when {
            all == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            all.isEmpty() -> Text(stringResource(R.string.epg_none_channel), color = c.onSurfaceVariant,
                modifier = Modifier.padding(24.dp).navigationBarsPadding())
            else -> {
                // Lignes : titre du jour à chaque changement de jour, puis ses programmes.
                val rows = remember(all) {
                    val out = ArrayList<Any>()
                    var lastDay: Int? = null
                    all.forEach { p ->
                        val d = dayOffset(p.start, System.currentTimeMillis())
                        if (d != lastDay) { out += DayStart(p.start); lastDay = d }
                        out += p
                    }
                    out
                }
                // Ouverte sur le programme en cours, avec le précédent au-dessus.
                LaunchedEffect(rows) {
                    val i = rows.indexOfFirst { it is Epg.Prog && now >= it.start && now < it.stop }
                    if (i <= 0) return@LaunchedEffect
                    // Le programme précédent en haut (le titre du jour reste collé au-dessus).
                    list.scrollToItem(i - 1)
                }
                LazyColumn(state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    modifier = Modifier.navigationBarsPadding()) {
                    rows.forEach { r ->
                        // Le jour reste collé en haut tant qu'on fait défiler ses programmes.
                        if (r is DayStart) stickyHeader {
                            Text(
                                dayLabel(r.at, now),
                                style = MaterialTheme.typography.titleSmall, color = c.primary,
                                modifier = Modifier.fillMaxWidth().background(androidx.compose.material3.BottomSheetDefaults.ContainerColor)
                                    .padding(start = 12.dp, top = 10.dp, bottom = 6.dp).semantics { heading() },
                            )
                        } else if (r is Epg.Prog) item {
                            run {
                                val live = now >= r.start && now < r.stop
                                val past = r.stop <= now
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                        .background(if (live) c.surfaceContainerHigh else Color.Transparent)
                                        .padding(horizontal = 12.dp, vertical = if (live) 12.dp else 9.dp)
                                        .alpha(if (past) 0.45f else 1f),
                                ) {
                                    Text(fmt.format(java.util.Date(r.start)), style = MaterialTheme.typography.labelLarge,
                                        color = if (live) c.primary else c.onSurfaceVariant, modifier = Modifier.width(60.dp).padding(top = 2.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (live) Text(stringResource(R.string.epg_now).uppercase(), style = MaterialTheme.typography.labelSmall, color = c.primary)
                                        Text(r.title, style = if (live) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (live) ProgBar(r, now, Modifier.padding(top = 2.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
