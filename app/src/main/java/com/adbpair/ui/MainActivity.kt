package com.adbpair.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adbpair.R
import com.adbpair.core.AdbPairService
import com.adbpair.core.PairingState
import com.adbpair.core.PairingStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(AdbPairService.resumeIntent(this))
        setContent {
            MaterialTheme {
                AdbPairScreen(activity = this)
            }
        }
    }
}

@Composable
private fun AdbPairScreen(activity: MainActivity) {
    val state by PairingStore.state.collectAsState()
    var showForgetConfirm by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val exportScope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri != null) {
            exportScope.launch(Dispatchers.IO) {
                val logDir = File(activity.filesDir, "logs")
                val logFile = File(logDir, "adbpair-log.jsonl")
                if (logFile.isFile) {
                    activity.contentResolver.openOutputStream(uri)?.use { out ->
                        logFile.inputStream().copyTo(out)
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = activity.getString(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stateName(activity, state.state), style = MaterialTheme.typography.titleLarge)
                Text(text = state.detail.ifBlank { stateName(activity, state.state) })
                state.fingerprint?.let { Text(text = activity.getString(R.string.label_fingerprint, it)) }
                val host = state.host
                val port = state.port
                if (host != null && port != null) {
                    Text(text = activity.getString(R.string.label_connection, host, port))
                }
            }
        }

        if (state.state == PairingState.PAIRING || state.state == PairingState.PAIRING_WAIT_KEY) {
            PairingPanel(
                pairingPort = state.port,
                waitingForKey = state.state == PairingState.PAIRING_WAIT_KEY,
                onSubmit = { code, port ->
                    val intent = Intent(activity, AdbPairService::class.java).apply {
                        action = AdbPairService.ACTION_SUBMIT_CODE
                        putExtra(AdbPairService.EXTRA_CODE, code)
                        putExtra(AdbPairService.EXTRA_PORT, port)
                    }
                    activity.startService(intent)
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                SettingsNavigator.startPairing(activity) {
                    activity.startService(AdbPairService.pairIntent(activity))
                }
            }) { Text(text = activity.getString(R.string.title_pair)) }
            OutlinedButton(onClick = {
                activity.startService(AdbPairService.resumeIntent(activity))
            }) { Text(text = activity.getString(R.string.title_resume)) }
            OutlinedButton(onClick = { showForgetConfirm = true }) {
                Text(text = activity.getString(R.string.title_forget))
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = activity.getString(R.string.grant_toggle_label))
                    Text(
                        text = if (state.grantHeld) activity.getString(R.string.grant_held)
                        else activity.getString(R.string.grant_not_held),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                var grantOn by remember(state.grantHeld) {
                    mutableStateOf(PairingStore.isGrantToggleOn(activity))
                }
                Switch(
                    checked = grantOn,
                    onCheckedChange = { on ->
                        grantOn = on
                        PairingStore.setGrantToggle(activity, on)
                        val intent = Intent(activity, AdbPairService::class.java).apply {
                            action = AdbPairService.ACTION_SET_GRANT_TOGGLE
                            putExtra(AdbPairService.EXTRA_GRANT_ON, on)
                        }
                        activity.startService(intent)
                    },
                )
            }
        }

        OutlinedButton(onClick = { exportLauncher.launch("adbpair-log.jsonl") }) {
            Text(text = activity.getString(R.string.btn_export_log))
        }

        if (!state.checklistDone) {
            ChecklistCard(activity = activity)
        }
    }

    if (showForgetConfirm) {
        AlertDialog(
            onDismissRequest = { showForgetConfirm = false },
            title = { Text(text = activity.getString(R.string.forget_confirm_title)) },
            text = { Text(text = activity.getString(R.string.forget_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showForgetConfirm = false
                    activity.startService(AdbPairService.forgetIntent(activity))
                }) { Text(text = activity.getString(R.string.forget_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showForgetConfirm = false }) {
                    Text(text = activity.getString(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ChecklistCard(activity: MainActivity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = activity.getString(R.string.checklist_title), style = MaterialTheme.typography.titleMedium)
            ChecklistRow(activity, activity.getString(R.string.checklist_battery)) {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            ChecklistRow(activity, activity.getString(R.string.checklist_app_details)) {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    },
                )
            }
            ChecklistRow(activity, activity.getString(R.string.checklist_dev_options)) {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
            OutlinedButton(onClick = {
                PairingStore.setChecklistDone(activity, true)
            }) { Text(text = activity.getString(R.string.checklist_done)) }
        }
    }
}

@Composable
private fun ChecklistRow(activity: MainActivity, text: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, modifier = Modifier.weight(1f))
        TextButton(onClick = onOpen) { Text(text = activity.getString(R.string.checklist_open)) }
    }
}

private fun stateName(activity: MainActivity, state: PairingState): String = when (state) {
    PairingState.IDLE -> activity.getString(R.string.state_idle)
    PairingState.RESUMING -> activity.getString(R.string.state_resuming)
    PairingState.CONNECTED -> activity.getString(R.string.state_connected)
    PairingState.STALE -> activity.getString(R.string.state_stale)
    PairingState.PARKED_AUTH -> activity.getString(R.string.state_waiting_key)
    PairingState.PAIRING -> activity.getString(R.string.state_pairing)
    PairingState.PAIRING_WAIT_KEY -> activity.getString(R.string.state_waiting_key)
}
