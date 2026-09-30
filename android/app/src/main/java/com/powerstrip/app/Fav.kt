package com.powerstrip.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray

data class FavRef(val mac: String, val n: Int) {
    override fun toString(): String = "$mac:$n"
}

/** Phone-local favorite outlets (ordered). Pure helpers stay JVM-testable. */
object FavStore {
    private const val FILE = "power-strip"
    private const val KEY = "favs"

    fun parse(raw: String): List<FavRef> {
        val out = mutableListOf<FavRef>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val parts = array.optString(i).split(":")
                val n = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size == 2 && parts[0].length == 12 && n != null && n in 1..4) {
                    out.add(FavRef(parts[0].uppercase(), n))
                }
            }
        } catch (e: Exception) {
        }
        return out.distinct()
    }

    fun serialize(favs: List<FavRef>): String {
        val array = JSONArray()
        favs.distinct().forEach { array.put(it.toString()) }
        return array.toString()
    }

    fun move(favs: List<FavRef>, from: Int, dir: Int): List<FavRef> {
        val to = from + dir
        if (from !in favs.indices || to !in favs.indices) return favs
        val mut = favs.toMutableList()
        val item = mut.removeAt(from)
        mut.add(to, item)
        return mut
    }

    fun load(context: Context): List<FavRef> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty()
        return parse(raw)
    }

    fun save(context: Context, favs: List<FavRef>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, serialize(favs)).apply()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FavoritesSection(
    favs: List<FavRef>,
    snapshot: Snapshot?,
    pending: Map<String, Boolean>,
    onCommand: (String, Int, Boolean) -> Unit,
    onRename: (String, Int?) -> Unit,
    onFavsChange: (List<FavRef>) -> Unit,
) {
    var managing by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    val strips = snapshot?.strips.orEmpty()

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.fav_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { managing = !managing }) {
                    Text(stringResource(if (managing) R.string.fav_done else R.string.fav_edit))
                }
            }

            if (favs.isEmpty() && !managing) {
                Text(
                    stringResource(R.string.fav_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            favs.forEachIndexed { i, ref ->
                val outlet = strips.firstOrNull { it.mac == ref.mac }?.outlet(ref.n)
                if (outlet != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            OutletTile(
                                outlet = outlet,
                                on = pending["${ref.mac}:${ref.n}"] ?: outlet.on,
                                pending = pending.containsKey("${ref.mac}:${ref.n}"),
                                enabled = strips.firstOrNull { it.mac == ref.mac }?.online == true,
                                onToggle = { onCommand(ref.mac, ref.n, it) },
                                onRename = { onRename(ref.mac, ref.n) },
                            )
                        }
                        if (managing) {
                            Column {
                                IconButton(onClick = { onFavsChange(FavStore.move(favs, i, -1)) }) {
                                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                                }
                                IconButton(onClick = { onFavsChange(FavStore.move(favs, i, 1)) }) {
                                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                                }
                                IconButton(onClick = {
                                    onFavsChange(favs.filterIndexed { j, _ -> j != i })
                                }) { Icon(Icons.Filled.Delete, contentDescription = null) }
                            }
                        }
                    }
                }
            }

            if (managing) {
                OutlinedButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.fav_add)) }
            }
        }
    }

    if (showAdd) {
        AddFavDialog(
            strips = strips,
            current = favs,
            onDismiss = { showAdd = false },
            onSave = {
                onFavsChange(it)
                showAdd = false
            },
        )
    }
}

@Composable
private fun AddFavDialog(
    strips: List<Strip>,
    current: List<FavRef>,
    onDismiss: () -> Unit,
    onSave: (List<FavRef>) -> Unit,
) {
    var picked by remember(current) { mutableStateOf(current.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fav_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                strips.forEach { strip ->
                    Text(
                        strip.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    (1..4).forEach { n ->
                        val ref = FavRef(strip.mac, n)
                        val label = strip.outlet(n)?.name?.ifBlank { null } ?: "$n"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = ref in picked,
                                onCheckedChange = { v ->
                                    picked = if (v) picked + ref else picked - ref
                                },
                            )
                            Text("$label", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // keep existing order, append newly picked in dialog order
                val kept = current.filter { it in picked }
                val fresh = strips.flatMap { s -> (1..4).map { FavRef(s.mac, it) } }
                    .filter { it in picked && it !in kept }
                onSave(kept + fresh)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
