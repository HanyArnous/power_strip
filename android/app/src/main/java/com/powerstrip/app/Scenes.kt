package com.powerstrip.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun dayName(i: Int): Int = when (i) {
    0 -> R.string.day_0
    1 -> R.string.day_1
    2 -> R.string.day_2
    3 -> R.string.day_3
    4 -> R.string.day_4
    5 -> R.string.day_5
    else -> R.string.day_6
}

private fun outletLabel(strip: Strip?, outlet: Int, allText: String): String {
    if (outlet == 0) return allText
    return strip?.outlet(outlet)?.name?.ifBlank { null } ?: "$outlet"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScenesScreen(snapshot: Snapshot?) {
    val strips = snapshot?.strips.orEmpty()
    var scenes by remember { mutableStateOf<List<Scene>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Scene?>(null) }
    var creating by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun reload() {
        if (!Server.configured) return
        loading = true
        try {
            scenes = Api.scenesList(Server.base, Server.token)
            error = null
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    LaunchedEffect(Server.host, Server.port, Server.token) { reload() }

    fun stripName(mac: String): String =
        strips.firstOrNull { it.mac == mac }?.name ?: mac.takeLast(7)

    fun summary(s: Scene): String {
        val acts = s.actions.joinToString(" + ") { a ->
            val st = strips.firstOrNull { it.mac == a.mac }
            val on = if (a.on) "ON" else "OFF"
            "${stripName(a.mac)}:${outletLabel(st, a.outlet, "All")} $on"
        }
        return when (s.trigger.type) {
            "threshold" -> {
                val st = strips.firstOrNull { it.mac == s.trigger.mac }
                val op = if (s.trigger.direction == "above") ">" else "<"
                "${outletLabel(st, s.trigger.outlet, "All")} $op ${s.trigger.watts}W ${s.trigger.forS}s → $acts"
            }
            "schedule" -> "${s.trigger.time} ${s.trigger.days.sorted().joinToString(",")} → $acts"
            else -> "Manual → $acts"
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.scenes_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = strips.isNotEmpty() && !loading,
                        onClick = { creating = true },
                    ) { Text(stringResource(R.string.scene_new)) }
                }
            }
            if (error != null) {
                item { Text(error!!, color = MaterialTheme.colorScheme.error) }
            }
            if (scenes.isEmpty() && !loading) {
                item {
                    Text(
                        stringResource(R.string.scene_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(scenes, key = { it.id }) { s ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    summary(s),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                s.lastFired?.let {
                                    Text(
                                        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(it * 1000)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = s.enabled,
                                onCheckedChange = { v ->
                                    scope.launch {
                                        try {
                                            Api.saveScene(Server.base, Server.token, s.copy(enabled = v))
                                            reload()
                                        } catch (e: Exception) {
                                            snackbar.showSnackbar(e.message ?: "command failed")
                                        }
                                    }
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        Api.runScene(Server.base, Server.token, s.id)
                                        snackbar.showSnackbar(context.getString(R.string.scene_fired))
                                        reload()
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar(e.message ?: "command failed")
                                    }
                                }
                            }) { Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.scene_run)) }
                            IconButton(onClick = { editing = s }) {
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.scene_edit))
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        Api.deleteScene(Server.base, Server.token, s.id)
                                        reload()
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar(e.message ?: "command failed")
                                    }
                                }
                            }) { Icon(Icons.Filled.Delete, contentDescription = null) }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar)
    }

    if (creating || editing != null) {
        SceneEditor(
            initial = editing,
            strips = strips,
            onDismiss = { creating = false; editing = null },
            onSave = { draft ->
                scope.launch {
                    try {
                        Api.saveScene(Server.base, Server.token, draft)
                        creating = false
                        editing = null
                        reload()
                    } catch (e: Exception) {
                        snackbar.showSnackbar(e.message ?: "command failed")
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SceneEditor(
    initial: Scene?,
    strips: List<Strip>,
    onDismiss: () -> Unit,
    onSave: (Scene) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var trigType by remember { mutableStateOf(initial?.trigger?.type ?: "threshold") }
    var tMac by remember { mutableStateOf(initial?.trigger?.mac ?: strips.firstOrNull()?.mac.orEmpty()) }
    var tOutlet by remember { mutableStateOf(initial?.trigger?.outlet ?: 1) }
    var tDir by remember { mutableStateOf(initial?.trigger?.direction ?: "above") }
    var tWatts by remember { mutableStateOf(initial?.trigger?.watts?.toString() ?: "") }
    var tForS by remember { mutableStateOf(initial?.trigger?.forS?.toString() ?: "10") }
    var tTime by remember { mutableStateOf(initial?.trigger?.time ?: "07:00") }
    var days by remember { mutableStateOf(initial?.trigger?.days?.toSet() ?: (0..6).toSet()) }
    val actions = remember {
        mutableStateListOf<SceneAction>().apply { initial?.actions?.let { addAll(it) } }
    }
    var aOutlet by remember { mutableStateOf(0) }
    var aOn by remember { mutableStateOf(false) }
    var aMac by remember { mutableStateOf(initial?.actions?.firstOrNull()?.mac ?: strips.firstOrNull()?.mac.orEmpty()) }

    ElevatedCard(Modifier.fillMaxSize().padding(12.dp)) {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (initial == null) R.string.scene_new else R.string.scene_edit),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.scene_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = trigType == "threshold", onClick = { trigType = "threshold" },
                    label = { Text(stringResource(R.string.trig_threshold)) })
                FilterChip(selected = trigType == "schedule", onClick = { trigType = "schedule" },
                    label = { Text(stringResource(R.string.trig_schedule)) })
                FilterChip(selected = trigType == "manual", onClick = { trigType = "manual" },
                    label = { Text(stringResource(R.string.trig_manual)) })
            }

            if (trigType == "threshold") {
                if (strips.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        strips.forEach { s ->
                            FilterChip(selected = tMac == s.mac, onClick = { tMac = s.mac }, label = { Text(s.name) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..4).forEach { n ->
                        FilterChip(selected = tOutlet == n, onClick = { tOutlet = n }, label = { Text("$n") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tDir == "above", onClick = { tDir = "above" },
                        label = { Text(stringResource(R.string.scene_above)) })
                    FilterChip(selected = tDir == "below", onClick = { tDir = "below" },
                        label = { Text(stringResource(R.string.scene_below)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = tWatts,
                        onValueChange = { tWatts = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text(stringResource(R.string.scene_watts)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = tForS,
                        onValueChange = { tForS = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.scene_for_s)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (trigType == "schedule") {
                OutlinedTextField(
                    value = tTime,
                    onValueChange = { tTime = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
                    label = { Text(stringResource(R.string.scene_time)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..6).forEach { d ->
                        FilterChip(
                            selected = d in days,
                            onClick = {
                                days = if (d in days) days - d else days + d
                            },
                            label = { Text(stringResource(dayName(d))) },
                        )
                    }
                }
            }

            Text(stringResource(R.string.scene_actions), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            actions.forEachIndexed { i, a ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${strips.firstOrNull { it.mac == a.mac }?.name ?: a.mac.takeLast(7)}:${if (a.outlet == 0) stringResource(R.string.scene_all) else "${a.outlet}"} ${if (a.on) stringResource(R.string.scene_on) else stringResource(R.string.scene_off)}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = { actions.removeAt(i) }) { Icon(Icons.Filled.Delete, contentDescription = null) }
                }
            }
            if (strips.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    strips.forEach { s ->
                        FilterChip(selected = aMac == s.mac, onClick = { aMac = s.mac }, label = { Text(s.name) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..4).forEach { n ->
                    FilterChip(selected = aOutlet == n, onClick = { aOutlet = n },
                        label = { Text(if (n == 0) stringResource(R.string.scene_all) else "$n") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = aOn, onClick = { aOn = true }, label = { Text(stringResource(R.string.scene_on)) })
                FilterChip(selected = !aOn, onClick = { aOn = false }, label = { Text(stringResource(R.string.scene_off)) })
                Button(
                    enabled = aMac.isNotBlank(),
                    onClick = {
                        actions.add(SceneAction(aMac, aOutlet, aOn))
                    },
                ) { Text(stringResource(R.string.scene_action_add)) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        val draft = Scene(
                            id = initial?.id.orEmpty(),
                            name = name.trim(),
                            enabled = initial?.enabled ?: true,
                            trigger = SceneTrigger(
                                type = trigType, mac = tMac, outlet = tOutlet,
                                direction = tDir,
                                watts = tWatts.toDoubleOrNull() ?: -1.0,
                                forS = tForS.toDoubleOrNull() ?: -1.0,
                                time = tTime.trim(), days = days.sorted(),
                            ),
                            actions = actions.toList(),
                            lastFired = initial?.lastFired,
                        )
                        onSave(draft)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.save)) }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}
