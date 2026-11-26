@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.scout.ask.AskConfig
import com.nuvio.tv.core.scout.ask.AskKeyTest
import com.nuvio.tv.core.scout.ask.AskKeyVault
import com.nuvio.tv.core.scout.ask.AskPhoneKeyStatus
import com.nuvio.tv.core.scout.ask.AskPhonePage
import com.nuvio.tv.core.scout.ask.AskPhoneServer
import com.nuvio.tv.core.scout.ask.AskPhoneState
import com.nuvio.tv.core.scout.ask.AskProvider
import com.nuvio.tv.core.scout.ask.AskService
import com.nuvio.tv.core.scout.ask.newAskPhoneToken
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.util.readAppLogoBytes
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DexAiUiState(
    val active: AskProvider? = null,
    val qr: Bitmap? = null,
    val url: String? = null,
    val serverError: String? = null,
    val testing: Boolean = false,
    val testGood: Boolean? = null
)

/** Dex Hub's AI key: typed on the phone through a QR page, kept encrypted on this box, never synced. */
@HiltViewModel
class DexAiSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: AskKeyVault,
    private val service: AskService
) : ViewModel() {
    private val token = newAskPhoneToken()
    private var server: AskPhoneServer? = null
    private var testMessage: String? = null

    private val _state = MutableStateFlow(DexAiUiState(active = vault.current().active))
    val state: StateFlow<DexAiUiState> = _state.asStateFlow()

    init {
        start()
    }

    private fun start() {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _state.update { it.copy(serverError = context.getString(R.string.error_network_required)) }
            return
        }
        val started = AskPhoneServer.startOnAvailablePort(
            token = token,
            page = { AskPhonePage.html(context, token, startOnKey = true) },
            state = ::phoneState,
            onKey = { config -> onKey(config); true },
            onQuestion = {},
            logo = { context.readAppLogoBytes() }
        )
        if (started == null) {
            _state.update { it.copy(serverError = context.getString(R.string.error_server_ports_unavailable)) }
            return
        }
        server = started
        val url = "http://$ip:${started.listeningPort}/?t=$token"
        _state.update { it.copy(qr = QrCodeGenerator.generate(url, 512), url = "http://$ip:${started.listeningPort}") }
    }

    private fun phoneState(): AskPhoneState {
        val keys = vault.current()
        return AskPhoneState(
            selected = keys.active ?: AskProvider.GEMINI,
            active = keys.active,
            providers = AskProvider.entries.map { provider ->
                AskPhoneKeyStatus(provider, keys.configs.containsKey(provider), keys.configs[provider]?.model.orEmpty())
            },
            testMessage = testMessage,
            testGood = _state.value.testGood == true
        )
    }

    private fun onKey(config: AskConfig) {
        _state.update { it.copy(testing = true, testGood = null) }
        testMessage = context.getString(R.string.dexhub_ai_testing)
        viewModelScope.launch {
            val good = service.testKey(config) is AskKeyTest.Good
            if (good) vault.save(config)
            testMessage = context.getString(if (good) R.string.dexhub_ai_key_good else R.string.dexhub_ai_key_bad)
            _state.update { it.copy(testing = false, testGood = good, active = vault.current().active) }
        }
    }

    fun removeKey() {
        vault.current().active?.let(vault::remove)
        testMessage = null
        _state.update { it.copy(active = vault.current().active, testGood = null) }
    }

    override fun onCleared() {
        runCatching { server?.stop() }
        server = null
    }
}

internal fun askProviderName(provider: AskProvider): String = when (provider) {
    AskProvider.GEMINI -> "Gemini"
    AskProvider.GROQ -> "Groq"
    AskProvider.CHATGPT -> "ChatGPT"
    AskProvider.CLAUDE -> "Claude"
    AskProvider.OTHER -> "OpenAI-compatible"
}

@Composable
fun DexAiSettingsContent(
    viewModel: DexAiSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.dexhub_ai_title),
            subtitle = stringResource(R.string.dexhub_ai_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            SettingsActionRow(
                title = state.active?.let { stringResource(R.string.dexhub_ai_active, askProviderName(it)) }
                    ?: stringResource(R.string.dexhub_ai_none),
                subtitle = when {
                    state.testing -> stringResource(R.string.dexhub_ai_testing)
                    state.testGood == true -> stringResource(R.string.dexhub_ai_key_good)
                    state.testGood == false -> stringResource(R.string.dexhub_ai_key_bad)
                    state.active != null -> stringResource(R.string.dexhub_ai_remove)
                    else -> null
                },
                onClick = { if (state.active != null) viewModel.removeKey() },
                modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.qr?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(220.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = state.serverError ?: stringResource(R.string.dexhub_ai_scan),
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    state.url?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                    }
                    Text(
                        text = stringResource(R.string.dexhub_ai_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }
    }
}
