package com.nuvio.tv.dexhub.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The viewer's own AI service (DEXHUB.md), as Dex Hub for Kodi offers them (resources/lib/ai_search.py): each speaks
 * OpenAI's chat API at its own address, with the viewer's key and the model they choose. Kept on this device only.
 */
object AiService {
    data class Provider(val id: String, val name: String, val base: String, val keysAt: String, val models: List<String>)

    val providers = listOf(
        Provider("groq", "Groq", "https://api.groq.com/openai/v1", "console.groq.com/keys",
            listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "meta-llama/llama-4-maverick-17b-128e-instruct", "llama-3.1-8b-instant")),
        Provider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "aistudio.google.com/apikey",
            listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-2.0-flash")),
        Provider("openai", "OpenAI", "https://api.openai.com/v1", "platform.openai.com/api-keys",
            listOf("gpt-5-mini", "gpt-4.1-mini", "gpt-4o-mini")),
        Provider("anthropic", "Claude", "https://api.anthropic.com/v1", "console.anthropic.com/settings/keys",
            listOf("claude-haiku-5-5", "claude-sonnet-5-5")),
        Provider("grok", "Grok (xAI)", "https://api.x.ai/v1", "console.x.ai",
            listOf("grok-4-1-fast-non-reasoning", "grok-4-fast-non-reasoning", "grok-3-mini")),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter.ai/keys",
            listOf("google/gemini-2.5-flash", "x-ai/grok-4-fast", "openai/gpt-4o-mini")),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "platform.deepseek.com/api_keys", listOf("deepseek-chat")),
        Provider("mistral", "Mistral", "https://api.mistral.ai/v1", "console.mistral.ai/api-keys", listOf("mistral-small-latest")),
    )

    /** What subtitles are translated with: "google" (free, no key) or one of [providers] with its key and model. */
    data class Choice(val service: String = "google", val key: String = "", val model: String = "") {
        val provider: Provider? get() = providers.firstOrNull { it.id == service }
        val usable: Boolean get() = provider != null && key.isNotBlank() && model.isNotBlank()
        val label: String get() = provider?.let { "${it.name} · $model" } ?: "Google Translate"
    }

    class AiException(val status: Int, message: String) : Exception(message)

    private const val Prefs = "dexhub_ai"
    private val state = MutableStateFlow<Choice?>(null)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

    fun choice(context: Context): StateFlow<Choice> {
        if (state.value == null) {
            val p = context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
            state.value = Choice(p.getString("service", "google") ?: "google", p.getString("key", "") ?: "", p.getString("model", "") ?: "")
        }
        @Suppress("UNCHECKED_CAST")
        return state.asStateFlow() as StateFlow<Choice>
    }

    fun current(context: Context): Choice = choice(context).value

    fun save(context: Context, choice: Choice) {
        state.value = choice
        context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit()
            .putString("service", choice.service).putString("key", choice.key).putString("model", choice.model)
            .apply { if (choice.provider != null && choice.key.isNotBlank()) putString("key_${choice.service}", choice.key) }
            .apply()
    }

    /** The key saved for a service, kept when another service (or Google) translates: Whisper reads Groq's or OpenAI's. */
    fun keyFor(context: Context, service: String): String {
        val p = context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        return p.getString("key_$service", null)?.takeIf { it.isNotBlank() }
            ?: current(context).takeIf { it.service == service }?.key.orEmpty()
    }

    private val whisperState = MutableStateFlow<Boolean?>(null)

    /** Whether AutoSync may ask Whisper (with the viewer's Groq or OpenAI key) when nothing else gives it a clock. */
    fun whisperFlow(context: Context): StateFlow<Boolean> {
        if (whisperState.value == null) {
            whisperState.value = context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE).getBoolean("whisper", true)
        }
        @Suppress("UNCHECKED_CAST")
        return whisperState.asStateFlow() as StateFlow<Boolean>
    }

    fun whisper(context: Context): Boolean = whisperFlow(context).value

    fun setWhisper(context: Context, on: Boolean) {
        whisperState.value = on
        context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().putBoolean("whisper", on).apply()
    }

    /** The service's answer to [system] and [user] (blocking). Throws [AiException] with the HTTP status. */
    fun chat(choice: Choice, system: String, user: String): String {
        val provider = choice.provider ?: throw AiException(0, "no AI service")
        val body = JSONObject()
            .put("model", choice.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val request = Request.Builder().url(provider.base.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${choice.key.trim()}")
            .header("User-Agent", "DexHub")
            .apply { if (provider.id == "openrouter") header("X-Title", "Dex Hub") }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val (status, text) = http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (status != 200 || json == null) {
            val error = json?.opt("error")
            val message = (error as? JSONObject)?.optString("message") ?: error?.toString() ?: text.take(160)
            throw AiException(status, message.take(160))
        }
        val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        return when (content) {
            is JSONArray -> (0 until content.length()).joinToString("") { content.optJSONObject(it)?.optString("text").orEmpty() }
            null -> throw AiException(0, "empty answer")
            else -> content.toString()
        }
    }
}
