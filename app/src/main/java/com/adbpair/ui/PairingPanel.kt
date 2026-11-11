package com.adbpair.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.adbpair.R

/**
 * In-app code panel. ONE code field (6 digits) + ONE manual port field.
 * The port field is pre-filled with the discovered pairing port; it is
 * editable because mDNS discovery can fail. Validation rejects the trap
 * DroidDeck hit in the 2026-11-09 video: a 6-digit code typed into the
 * port field (and vice versa) is called out, not silently submitted.
 */
@Composable
fun PairingPanel(
    pairingPort: Int?,
    waitingForKey: Boolean,
    onSubmit: (code: String, port: Int) -> Unit,
) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var portText by remember(pairingPort) { mutableStateOf(pairingPort?.toString().orEmpty()) }

    val codeValid = code.length == 6 && code.all { it.isDigit() }
    val port = portText.toIntOrNull()
    val portValid = port != null && port in 1..65535
    // The swap trap: a 6-digit value in the port field is almost certainly the code.
    val swapSuspected = portText.length == 6 && portText.all { it.isDigit() } && code.isEmpty()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (waitingForKey) {
                Text(
                    text = context.getString(R.string.panel_waiting_key),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            OutlinedTextField(
                value = code,
                onValueChange = { value -> code = value.filter { it.isDigit() }.take(6) },
                label = { Text(text = context.getString(R.string.panel_code_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { value -> portText = value.filter { it.isDigit() }.take(5) },
                label = { Text(text = context.getString(R.string.panel_port_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (swapSuspected) {
                Text(
                    text = context.getString(R.string.panel_code_swap_warning),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = { onSubmit(code, port ?: -1) },
                enabled = codeValid && portValid && !waitingForKey,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = context.getString(R.string.panel_submit))
            }
        }
    }
}
