package org.aionlink.open

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
fun AuthExchangeDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    fun cancel() { pin = ""; onDismiss() }
    AlertDialog(
        onDismissRequest = ::cancel,
        title = { Text("Experimental AUTH request") },
        text = { Column {
            Text("With your vehicle parked, enter the current code again. An experimental request will be sent, the response checked, and, if valid, the observed AUTH confirmation sent. It may fail or disconnect the receiver. No projection yet.")
            OutlinedTextField(value = pin, onValueChange = { pin = it.filter { c -> c in '0'..'9' }.take(6) },
                label = { Text("Vehicle code") }, singleLine = true, modifier = Modifier.testTag("auth-exchange-pin"),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        } },
        confirmButton = { TextButton(enabled = pin.length == 6, modifier = Modifier.testTag("auth-exchange-send"), onClick = {
            val submitted = pin; pin = ""; onSubmit(submitted)
        }) { Text("Send experimental request") } },
        dismissButton = { TextButton(onClick = ::cancel) { Text("Cancel") } }
    )
}
