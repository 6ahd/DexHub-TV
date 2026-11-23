@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.dexhub.usenet

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsSectionLabel
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

/** The "Usenet" row of the sources settings (DEXHUB.md): what is set up, and the setup behind it. */
@Composable
fun UsenetSettingsRow() {
    val context = LocalContext.current
    val setup by UsenetConfig.setup(context).collectAsStateWithLifecycle()
    val engine = remember { UsenetEngine.available() }
    var open by remember { mutableStateOf(false) }
    val providers = setup.providers.count { !it.off }
    val indexers = setup.indexers.count { !it.off }
    SettingsActionRow(
        title = stringResource(R.string.dexhub_usenet),
        subtitle = stringResource(R.string.dexhub_usenet_sub),
        value = when {
            !engine -> stringResource(R.string.dexhub_usenet_no_engine_short)
            providers == 0 && indexers == 0 -> stringResource(R.string.dexhub_usenet_not_set)
            else -> stringResource(R.string.dexhub_usenet_counts, providers, indexers)
        },
        onClick = { open = true }
    )
    if (open) UsenetDialog(onDismiss = { open = false })
}

@Composable
private fun UsenetDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setup by UsenetConfig.setup(context).collectAsStateWithLifecycle()
    var provider by remember { mutableStateOf<Int?>(null) }
    var indexer by remember { mutableStateOf<Int?>(null) }
    val installed = stringResource(R.string.dexhub_usenet_installed)

    fun save(next: UsenetConfig.Setup) {
        val was = setup.ready
        UsenetConfig.save(context, next)
        if (next.ready) scope.launch {
            if (UsenetAddon.install(context) && !was) Toast.makeText(context, installed, Toast.LENGTH_LONG).show()
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.dexhub_usenet),
        subtitle = stringResource(if (UsenetEngine.available()) R.string.dexhub_usenet_dialog_sub else R.string.dexhub_usenet_no_engine),
        width = 760.dp
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SettingsSectionLabel(text = stringResource(R.string.dexhub_usenet_providers)) }
            setup.providers.forEachIndexed { i, p ->
                item(key = "p$i") {
                    SettingsActionRow(
                        title = p.host,
                        subtitle = listOfNotNull(
                            "${p.port} · ${if (p.ssl) "SSL" else "—"} · ${p.connections}",
                            stringResource(R.string.dexhub_usenet_backup).takeIf { p.backup },
                            stringResource(R.string.dexhub_usenet_off).takeIf { p.off }
                        ).joinToString(" · "),
                        onClick = { provider = i },
                        jumpAnchor = false
                    )
                }
            }
            item {
                SettingsActionRow(
                    title = stringResource(R.string.dexhub_usenet_add_provider),
                    subtitle = stringResource(R.string.dexhub_usenet_add_provider_sub),
                    onClick = { provider = -1 },
                        jumpAnchor = false
                )
            }
            item { SettingsSectionLabel(text = stringResource(R.string.dexhub_usenet_indexers)) }
            setup.indexers.forEachIndexed { i, x ->
                item(key = "i$i") {
                    SettingsActionRow(
                        title = x.name.ifBlank { x.url },
                        subtitle = listOfNotNull(
                            if (x.prowlarr) "Prowlarr" else "Newznab",
                            stringResource(R.string.dexhub_usenet_off).takeIf { x.off }
                        ).joinToString(" · "),
                        onClick = { indexer = i },
                        jumpAnchor = false
                    )
                }
            }
            item {
                SettingsActionRow(
                    title = stringResource(R.string.dexhub_usenet_add_indexer),
                    subtitle = stringResource(R.string.dexhub_usenet_add_indexer_sub),
                    onClick = { indexer = -1 },
                        jumpAnchor = false
                )
            }
            item {
                SettingsToggleRow(
                    title = stringResource(R.string.dexhub_usenet_private),
                    subtitle = stringResource(R.string.dexhub_usenet_private_sub),
                    checked = setup.privateNetwork,
                    onToggle = { save(setup.copy(privateNetwork = !setup.privateNetwork)) }
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogButton(stringResource(R.string.action_close), onDismiss)
        }
    }

    provider?.let { i ->
        ProviderDialog(
            start = setup.providers.getOrNull(i) ?: UsenetConfig.Provider(host = ""),
            isNew = i < 0,
            onSave = { p ->
                save(setup.copy(providers = if (i < 0) setup.providers + p else setup.providers.toMutableList().also { it[i] = p }))
                provider = null
            },
            onDelete = { save(setup.copy(providers = setup.providers.filterIndexed { k, _ -> k != i })); provider = null },
            onDismiss = { provider = null }
        )
    }
    indexer?.let { i ->
        IndexerDialog(
            start = setup.indexers.getOrNull(i) ?: UsenetConfig.Indexer(name = "", url = "", key = ""),
            isNew = i < 0,
            onSave = { x ->
                save(setup.copy(indexers = if (i < 0) setup.indexers + x else setup.indexers.toMutableList().also { it[i] = x }))
                indexer = null
            },
            onDelete = { save(setup.copy(indexers = setup.indexers.filterIndexed { k, _ -> k != i })); indexer = null },
            onDismiss = { indexer = null }
        )
    }
}

@Composable
private fun ProviderDialog(
    start: UsenetConfig.Provider,
    isNew: Boolean,
    onSave: (UsenetConfig.Provider) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var host by remember { mutableStateOf(start.host) }
    var port by remember { mutableStateOf(start.port.toString()) }
    var user by remember { mutableStateOf(start.user) }
    var password by remember { mutableStateOf(start.password) }
    var connections by remember { mutableStateOf(start.connections.toString()) }
    var ssl by remember { mutableStateOf(start.ssl) }
    var backup by remember { mutableStateOf(start.backup) }
    var off by remember { mutableStateOf(start.off) }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(if (isNew) R.string.dexhub_usenet_add_provider else R.string.dexhub_usenet_provider),
        subtitle = stringResource(R.string.dexhub_usenet_provider_hint),
        width = 760.dp
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Field(stringResource(R.string.dexhub_usenet_host), host, { host = it.trim() }, placeholder = "news.example.com") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { Field(stringResource(R.string.dexhub_usenet_port), port, { port = it.filter(Char::isDigit) }, number = true) }
                    Box(Modifier.weight(1f)) { Field(stringResource(R.string.dexhub_usenet_connections), connections, { connections = it.filter(Char::isDigit) }, number = true) }
                }
            }
            item { Field(stringResource(R.string.dexhub_usenet_user), user, { user = it.trim() }) }
            item { Field(stringResource(R.string.dexhub_usenet_password_field), password, { password = it }, secret = true) }
            item {
                SettingsToggleRow(title = "SSL", subtitle = null, checked = ssl, onToggle = {
                    ssl = !ssl
                    if (port == "563" && !ssl) port = "119" else if (port == "119" && ssl) port = "563"
                })
            }
            item {
                SettingsToggleRow(title = stringResource(R.string.dexhub_usenet_backup), subtitle = stringResource(R.string.dexhub_usenet_backup_sub),
                    checked = backup, onToggle = { backup = !backup })
            }
            item { SettingsToggleRow(title = stringResource(R.string.dexhub_usenet_off), subtitle = null, checked = off, onToggle = { off = !off }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!isNew) { DialogButton(stringResource(R.string.action_delete), onDelete); Spacer(Modifier.width(NuvioTheme.spacing.sm)) }
            DialogButton(stringResource(R.string.action_cancel), onDismiss)
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            DialogButton(stringResource(R.string.action_save), primary = true, onClick = {
                if (host.isNotBlank()) onSave(UsenetConfig.Provider(
                    host = host.removePrefix("nntps://").removePrefix("nntp://").substringBefore('/'),
                    port = port.toIntOrNull() ?: if (ssl) 563 else 119,
                    ssl = ssl, user = user, password = password,
                    connections = (connections.toIntOrNull() ?: 20).coerceIn(1, 100),
                    backup = backup, off = off
                ))
            })
        }
    }
}

@Composable
private fun IndexerDialog(
    start: UsenetConfig.Indexer,
    isNew: Boolean,
    onSave: (UsenetConfig.Indexer) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(start.name) }
    var url by remember { mutableStateOf(start.url) }
    var key by remember { mutableStateOf(start.key) }
    var prowlarr by remember { mutableStateOf(start.prowlarr) }
    var off by remember { mutableStateOf(start.off) }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(if (isNew) R.string.dexhub_usenet_add_indexer else R.string.dexhub_usenet_indexer),
        subtitle = stringResource(R.string.dexhub_usenet_indexer_hint),
        width = 760.dp
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Field(stringResource(R.string.dexhub_usenet_indexer_url), url, { url = it.trim() }, placeholder = "https://api.nzbgeek.info") }
            item { Field(stringResource(R.string.dexhub_usenet_api_key), key, { key = it.trim() }, secret = true) }
            item { Field(stringResource(R.string.dexhub_usenet_indexer_name), name, { name = it }) }
            item {
                SettingsToggleRow(title = "Prowlarr", subtitle = stringResource(R.string.dexhub_usenet_prowlarr_sub),
                    checked = prowlarr, onToggle = { prowlarr = !prowlarr })
            }
            item { SettingsToggleRow(title = stringResource(R.string.dexhub_usenet_off), subtitle = null, checked = off, onToggle = { off = !off }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!isNew) { DialogButton(stringResource(R.string.action_delete), onDelete); Spacer(Modifier.width(NuvioTheme.spacing.sm)) }
            DialogButton(stringResource(R.string.action_cancel), onDismiss)
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            DialogButton(stringResource(R.string.action_save), primary = true, onClick = {
                val address = url.let { if (it.startsWith("http")) it else "https://$it" }.trimEnd('/')
                if (url.isNotBlank()) onSave(UsenetConfig.Indexer(
                    name = name.trim().ifEmpty { address.removePrefix("https://").removePrefix("http://").removePrefix("api.").substringBefore('/').substringBefore('.') },
                    url = address, key = key, prowlarr = prowlarr, off = off
                ))
            })
        }
    }
}

@Composable
private fun DialogButton(text: String, onClick: () -> Unit, primary: Boolean = false) {
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
private fun Field(
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
