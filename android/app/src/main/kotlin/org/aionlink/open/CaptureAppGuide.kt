package org.aionlink.open

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** The system owns the actual capture choice and asks for consent every session. */
@Composable
fun CaptureAppGuide(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share an app") },
        text = { Text("In the Android picker, choose an app and select the window you want to show. Capturing that app, its sound, and touch are not yet verified in the car. Locking the phone may stop the image. If you use another app, turn off AionLink's automatic launcher.") },
        confirmButton = { TextButton(onClick = onContinue, modifier = Modifier.testTag("capture-app-continue")) { Text("Choose in Android") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("capture-app-cancel")) { Text("Cancel") } }
    )
}
