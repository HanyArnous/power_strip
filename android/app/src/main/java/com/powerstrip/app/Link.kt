package com.powerstrip.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val SETUP_PORT = 30300

private fun errorText(context: android.content.Context, code: String?): String = when (code) {
    "ssid_required" -> context.getString(R.string.link_err_ssid)
    "bad_ip" -> context.getString(R.string.link_err_ip)
    "bad_chars" -> context.getString(R.string.link_err_chars)
    "no_wifi" -> context.getString(R.string.link_err_no_wifi)
    "unreachable" -> context.getString(R.string.link_err_unreachable)
    "bad_answer" -> context.getString(R.string.link_err_answer)
    else -> code ?: context.getString(R.string.command_failed)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LinkScreen(onLinked: () -> Unit) {
    var serverIp by remember { mutableStateOf(Server.host) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("192.168.1.1") }
    var busy by remember { mutableStateOf(false) }
    var reachable by remember { mutableStateOf<Boolean?>(null) }
    var result by remember { mutableStateOf<ProvisionResult?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.link_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.link_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.setup_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SetupStep(1, stringResource(R.string.link_step_1))
                SetupStep(2, stringResource(R.string.link_step_2))
                SetupStep(3, stringResource(R.string.link_step_3))
                SetupStep(4, stringResource(R.string.link_step_4))
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = serverIp,
                    onValueChange = { serverIp = it; result = null },
                    label = { Text(stringResource(R.string.field_server_ip)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it; result = null },
                    label = { Text(stringResource(R.string.field_ssid)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; result = null },
                    label = { Text(stringResource(R.string.field_wifi_pw)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; reachable = null },
                    label = { Text(stringResource(R.string.field_setup_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            reachable = null
                            result = null
                            scope.launch {
                                // Pin sockets to Wi-Fi: plain sockets would leave
                                // over mobile data since TONLY_TAP_* has no internet.
                                val factory = Api.wifiSocketFactory(context)
                                reachable = if (factory == null) false
                                else Api.probeSetup(host.trim(), SETUP_PORT, factory)
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.link_test)) }
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            reachable = null
                            result = null
                            scope.launch {
                                val factory = Api.wifiSocketFactory(context)
                                val r = if (factory == null) {
                                    ProvisionResult(false, emptyList(), "no_wifi")
                                } else {
                                    Api.provisionLink(
                                        host.trim(), SETUP_PORT,
                                        serverIp, ssid, password, factory,
                                    )
                                }
                                if (r.ok) {
                                    // Wire the Plugs tab automatically: the IP just
                                    // written into the strip becomes the app server.
                                    Server.save(serverIp, Server.port, Server.token)
                                }
                                result = r
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.link_go)) }
                }
                if (busy) CircularWavyProgressIndicator(Modifier.fillMaxWidth())
                reachable?.let {
                    Text(
                        stringResource(
                            if (it) R.string.link_reachable else R.string.link_unreachable_hint
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
                result?.let { r ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (r.ok) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
                        contentColor = if (r.ok) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                if (r.ok) stringResource(R.string.link_ok)
                                else errorText(context, r.error),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (r.ok) {
                                Text(
                                    stringResource(R.string.link_saved),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                // What each raw answer from the strip means.
                                r.steps.forEach { step ->
                                    Text(
                                        when {
                                            step.contains("up:ip:ip_ok") ->
                                                stringResource(R.string.link_got_ip)

                                            step.contains("up:connect:connect_ok") ->
                                                stringResource(R.string.link_got_wifi)

                                            else -> step
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            } else if (r.steps.isNotEmpty()) {
                                Text(
                                    r.steps.joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    if (r.ok) {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    stringResource(R.string.link_watch),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    stringResource(R.string.link_verify_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                SetupStep(1, stringResource(R.string.link_verify_1))
                                SetupStep(2, stringResource(R.string.link_verify_2))
                                SetupStep(3, stringResource(R.string.link_verify_3))
                            }
                        }
                        OutlinedButton(
                            onClick = onLinked,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.link_back)) }
                    }
                }
            }
        }
    }
}
