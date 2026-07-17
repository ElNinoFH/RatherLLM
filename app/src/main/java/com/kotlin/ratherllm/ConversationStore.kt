package com.kotlin.ratherllm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A saved chat: stable id, auto-generated title, its turns, and last-updated time. */
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Persists all conversations to a single JSON file under filesDir. */
class ConversationStore(context: Context) {

    private val file = File(context.filesDir, "conversations.json")

    fun load(): List<Conversation> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val msgs = o.getJSONArray("messages")
            Conversation(
                id = o.getString("id"),
                title = o.optString("title", "Chat"),
                updatedAt = o.optLong("updatedAt"),
                messages = (0 until msgs.length()).map { j ->
                    val m = msgs.getJSONObject(j)
                    ChatMessage(roleOf(m.optString("role")), m.optString("text"))
                },
            )
        }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    fun save(list: List<Conversation>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { c ->
                val msgs = JSONArray()
                c.messages.forEach { m -> msgs.put(JSONObject().put("role", m.role.wire).put("text", m.text)) }
                arr.put(
                    JSONObject().put("id", c.id).put("title", c.title)
                        .put("updatedAt", c.updatedAt).put("messages", msgs)
                )
            }
            file.writeText(arr.toString())
        }
    }

    companion object {
        private fun roleOf(wire: String): Role = when (wire) {
            "system" -> Role.System
            "assistant" -> Role.Assistant
            else -> Role.User
        }

        /** A short title derived from the first user message. */
        fun titleFrom(text: String): String {
            val t = text.trim().replace(Regex("\\s+"), " ")
            return if (t.length <= 40) t.ifBlank { "New chat" } else t.take(40).trimEnd() + "…"
        }
    }
}
