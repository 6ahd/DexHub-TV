@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.dexhub.ui

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

/** The text field and buttons of Dex Hub's setup dialogs (Usenet, the AI service), drawn as Arc's own key dialogs. */

@Composable
internal fun DexHubDialogButton(text: String, onClick: () -> Unit, primary: Boolean = false) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = if (primary) NuvioTheme.colors.BackgroundCard else NuvioTheme.colors.BackgroundElevated,
            contentColor = NuvioTheme.colors.TextPrimary
        )
    ) { Text(text) }
}

/** A labelled text field the remote can reach, as Arc's own key dialogs draw it. */
@Composable
internal fun DexHubField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
    number: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val input = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary)
        Card(
            onClick = { input.requestFocus() },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape = shape),
                focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
            ),
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(input)
                        .onKeyEvent { e -> e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && e.nativeKeyEvent.action == KeyEvent.ACTION_DOWN },
                    singleLine = true,
                    visualTransformation = if (secret && !focused) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        keyboardType = when { number -> KeyboardType.Number; secret -> KeyboardType.Password; else -> KeyboardType.Uri }
                    ),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary, textDirection = TextDirection.Ltr),
                    cursorBrush = SolidColor(if (focused) NuvioTheme.colors.Primary else Color.Transparent),
                    decorationBox = { inner ->
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextTertiary)
                        }
                        inner()
                    }
                )
            }
        }
    }
}
