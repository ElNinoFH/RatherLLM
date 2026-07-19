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
                    ChatMessage(
                        role = roleOf(m.optString("role")),
                        text = m.optString("text"),
                        id = m.optString("id").ifBlank { UUID.randomUUID().toString() },
                        tps = if (m.has("tps")) m.optDouble("tps").toFloat() else null,
                        modelName = m.optString("modelName").ifBlank { null },
                        attachments = m.optJSONArray("attachments")?.let { a ->
                            (0 until a.length()).map { k ->
                                val at = a.getJSONObject(k)
                                Attachment(
                                    kind = AttachmentKind.fromWire(at.optString("kind", "file")),
                                    path = at.optString("path"),
                                    name = at.optString("name"),
                                    mime = at.optString("mime"),
                                )
                            }
                        } ?: emptyList(),
                    )
                },
            )
        }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    fun save(list: List<Conversation>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { c ->
                val msgs = JSONArray()
                c.messages.forEach { m ->
                    val o = JSONObject().put("role", m.role.wire).put("text", m.text).put("id", m.id)
                    if (m.tps != null) o.put("tps", m.tps.toDouble())
                    if (m.modelName != null) o.put("modelName", m.modelName)
                    if (m.attachments.isNotEmpty()) {
                        val atts = JSONArray()
                        m.attachments.forEach { a ->
                            atts.put(
                                JSONObject().put("kind", a.kind.wire).put("path", a.path)
                                    .put("name", a.name).put("mime", a.mime)
                            )
                        }
                        o.put("attachments", atts)
                    }
                    msgs.put(o)
                }
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
