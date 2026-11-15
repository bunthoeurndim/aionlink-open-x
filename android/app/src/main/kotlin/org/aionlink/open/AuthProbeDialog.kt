package org.aionlink.open

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun AuthProbeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check vehicle channel") },
        text = { Text("With your vehicle parked, a brief TCP connection will open to the AUTH channel observed on Samsung and Xiaomi. No key or message is sent. The result does not confirm authentication or projection, and the receiver may close the network.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Check my vehicle") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
