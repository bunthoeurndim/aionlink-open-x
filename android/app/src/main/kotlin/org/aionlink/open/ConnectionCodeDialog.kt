package org.aionlink.open

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

enum class CodeReadiness { IDLE, PREPARING, READY, ANONYMIZED_EXPERIMENT, BLOCKED }
const val ANONYMIZED_EXPERIMENT_DETAIL = "Android keeps the local address hidden. This test is optional and its compatibility with the AION is not verified."

/** Visible even when preflight is blocked. Entering a code never means it was transmitted. */
@Composable
fun ConnectionCodeDialog(readiness: CodeReadiness, detail: String, onSubmit: (String, Boolean, Boolean) -> Unit, onClose: () -> Unit, autoAuthDefault: Boolean = false) {
    var code by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var autoAuth by remember { mutableStateOf(autoAuthDefault) }
    val experimental = readiness == CodeReadiness.ANONYMIZED_EXPERIMENT
    fun close() { code = ""; consent = false; autoAuth = false; onClose() }
    AlertDialog(onDismissRequest = ::close,
        title = { Text("Connect to my ICCOA") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(detail)
            Text("Only your own parked vehicle.", style = MaterialTheme.typography.bodySmall)
            if (readiness == CodeReadiness.PREPARING) LinearProgressIndicator()
            if (experimental) {
                Text("Android's anonymized data will be used. It may be rejected; it does not reveal your real address.")
                Row {
                    Checkbox(checked = consent, onCheckedChange = { consent = it }, modifier = Modifier.testTag("anonymous-consent"))
                    Text("I authorize this test with my vehicle")
                }
            }
            Row {
                Checkbox(checked = autoAuth, onCheckedChange = { autoAuth = it }, modifier = Modifier.testTag("auto-auth-consent"))
                Text("Authorize automatic experimental AUTH (request and, if the response is valid, confirmation) when Wi-Fi Direct connects. The code is kept temporarily in memory; it may fail or disconnect. No projection yet.")
            }
            OutlinedTextField(value = code,
                onValueChange = { code = it.filter { c -> c in '0'..'9' }.take(6) },
                label = { Text("Six-digit code") }, singleLine = true,
                modifier = Modifier.testTag("connection-code"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation())
            Text(if (code.isEmpty()) "The code is not saved or exported."
                else "Code entered in memory only; not sent yet.")
            if (readiness == CodeReadiness.BLOCKED) Text("Sending blocked. Entering the code does not resolve the pending requirement. Closing discards it.")
            Text("ICCOA authentication and projection are still pending implementation.")
        } },
        confirmButton = { TextButton(enabled = (readiness == CodeReadiness.READY || experimental && consent) && code.length == 6,
            modifier = Modifier.testTag("submit-code"), onClick = {
                val submitted = code; val optedIn = experimental && consent; val automatic = autoAuth
                code = ""; consent = false; autoAuth = false; onSubmit(submitted, optedIn, automatic)
            }) { Text(if (experimental) "Try experimental connection" else "Send to my receiver") } },
        dismissButton = { TextButton(onClick = ::close, modifier = Modifier.testTag("close-code")) { Text("Close") } })
}
