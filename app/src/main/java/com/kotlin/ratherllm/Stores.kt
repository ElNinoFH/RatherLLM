package com.kotlin.ratherllm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists bookmarked assistant replies to `saved_replies.json` under filesDir. */
class SavedReplyStore(context: Context) {

    private val file = File(context.filesDir, "saved_replies.json")

    fun load(): List<SavedReply> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedReply(
                messageId = o.getString("messageId"),
                text = o.optString("text"),
                conversationId = o.optString("conversationId"),
                conversationTitle = o.optString("conversationTitle", "Conversation"),
                savedAt = o.optLong("savedAt"),
            )
        }.sortedByDescending { it.savedAt }
    }.getOrDefault(emptyList())

    fun save(list: List<SavedReply>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("messageId", s.messageId).put("text", s.text)
                        .put("conversationId", s.conversationId)
                        .put("conversationTitle", s.conversationTitle)
                        .put("savedAt", s.savedAt),
                )
            }
            file.writeText(arr.toString())
        }
    }
}

/**
 * Persists per-model [ModelMeta] (description, capabilities, mmproj) to
 * `model_meta.json`, keyed by the model's file name so it survives listing.
 */
class ModelMetaStore(context: Context) {

    private val file = File(context.filesDir, "model_meta.json")
    private val cache: MutableMap<String, ModelMeta> by lazy { load().toMutableMap() }

    private fun load(): Map<String, ModelMeta> = runCatching {
        if (!file.exists()) return emptyMap()
        val root = JSONObject(file.readText())
        root.keys().asSequence().associateWith { key ->
            val o = root.getJSONObject(key)
            val caps = o.optJSONArray("caps")?.let { a ->
                (0 until a.length()).map { a.getString(it) }.toSet()
            } ?: setOf("text")
            ModelMeta(
                description = o.optString("desc"),
                capabilities = ModelCapability.fromKeys(caps).ifEmpty { setOf(ModelCapability.Text) },
                mmproj = o.optString("mmproj").ifBlank { null },
            )
        }
    }.getOrDefault(emptyMap())

    fun get(fileName: String): ModelMeta? = cache[fileName]

    fun put(fileName: String, meta: ModelMeta) {
        cache[fileName] = meta
        persist()
    }

    fun remove(fileName: String) {
        if (cache.remove(fileName) != null) persist()
    }

    private fun persist() {
        runCatching {
            val root = JSONObject()
            cache.forEach { (key, m) ->
                val caps = JSONArray().apply { m.capabilities.forEach { put(it.key) } }
                root.put(
                    key,
                    JSONObject().put("desc", m.description).put("caps", caps)
                        .apply { if (m.mmproj != null) put("mmproj", m.mmproj) },
                )
            }
            file.writeText(root.toString())
        }
    }
}

/**
 * Small key/value store for app-level preferences that outlive a process:
 * the experimental master switch and its two sub-features, plus device-analytics
 * visibility. Backed by SharedPreferences (cheap, synchronous reads at startup).
 */
class AppSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("ratherllm_settings", Context.MODE_PRIVATE)

    var experimentalEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXP, false)
        set(v) = prefs.edit().putBoolean(KEY_EXP, v).apply()

    var maximizeMemory: Boolean
        get() = prefs.getBoolean(KEY_MAXMEM, false)
        set(v) = prefs.edit().putBoolean(KEY_MAXMEM, v).apply()

    var multiResponse: Boolean
        get() = prefs.getBoolean(KEY_MULTI, false)
        set(v) = prefs.edit().putBoolean(KEY_MULTI, v).apply()

    var analyticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANALYTICS, false)
        set(v) = prefs.edit().putBoolean(KEY_ANALYTICS, v).apply()

    var systemPrompt: String
        get() = prefs.getString(KEY_SYS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SYS, v).apply()

    fun loadGenParams(): GenParams = GenParams(
        maxTokens = prefs.getInt(KEY_MAXTOK, GenParams().maxTokens),
        temperature = prefs.getFloat(KEY_TEMP, GenParams().temperature),
        topK = prefs.getInt(KEY_TOPK, GenParams().topK),
        topP = prefs.getFloat(KEY_TOPP, GenParams().topP),
        minP = prefs.getFloat(KEY_MINP, GenParams().minP),
        repeatPenalty = prefs.getFloat(KEY_REPEAT, GenParams().repeatPenalty),
    )

    fun saveGenParams(p: GenParams) {
        prefs.edit()
            .putInt(KEY_MAXTOK, p.maxTokens).putFloat(KEY_TEMP, p.temperature)
            .putInt(KEY_TOPK, p.topK).putFloat(KEY_TOPP, p.topP)
            .putFloat(KEY_MINP, p.minP).putFloat(KEY_REPEAT, p.repeatPenalty)
            .apply()
    }

    private companion object {
        const val KEY_EXP = "experimental_enabled"
        const val KEY_MAXMEM = "maximize_memory"
        const val KEY_MULTI = "multi_response"
        const val KEY_ANALYTICS = "analytics_enabled"
        const val KEY_SYS = "system_prompt"
        const val KEY_MAXTOK = "gp_max_tokens"
        const val KEY_TEMP = "gp_temperature"
        const val KEY_TOPK = "gp_top_k"
        const val KEY_TOPP = "gp_top_p"
        const val KEY_MINP = "gp_min_p"
        const val KEY_REPEAT = "gp_repeat_penalty"
    }
}
