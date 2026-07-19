package com.kotlin.ratherllm

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The export formats offered in the "Export conversation" dialog. */
enum class ExportFormat(val ext: String, val mime: String, val fileName: String) {
    Json("JSON", "application/json", "chat.json"),
    Markdown("MD", "text/markdown", "chat.md"),
    Text("TXT", "text/plain", "chat.txt"),
    Html("HTML", "text/html", "chat.html"),
    Pdf("PDF", "application/pdf", "chat.pdf"),
}

/**
 * Serializes a [Conversation] to any [ExportFormat] and writes it into
 * `cacheDir/exports/`, returning the file so the caller can hand it to the system
 * share sheet via FileProvider. Text formats are built in memory; PDF is laid out
 * with [PdfDocument] (simple wrapped-text pagination).
 */
object ChatExporter {

    fun export(context: Context, conversation: Conversation, format: ExportFormat): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val base = safeBase(conversation.title)
        val out = File(dir, "$base.${format.ext.lowercase()}")
        when (format) {
            ExportFormat.Json -> out.writeText(buildJson(conversation))
            ExportFormat.Markdown -> out.writeText(buildMarkdown(conversation))
            ExportFormat.Text -> out.writeText(buildText(conversation))
            ExportFormat.Html -> out.writeText(buildHtml(conversation))
            ExportFormat.Pdf -> writePdf(out, conversation)
        }
        return out
    }

    private fun safeBase(title: String): String =
        title.trim().ifBlank { "chat" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)

    private fun ChatMessage.speaker(): String = if (role == Role.User) "You" else (modelName ?: "Assistant")

    private fun buildJson(c: Conversation): String {
        val arr = JSONArray()
        c.messages.forEach { m ->
            val o = JSONObject().put("role", m.role.wire).put("text", m.text)
            if (m.tps != null) o.put("tps", m.tps.toDouble())
            if (m.modelName != null) o.put("model", m.modelName)
            arr.put(o)
        }
        return JSONObject().put("title", c.title).put("updatedAt", c.updatedAt).put("messages", arr).toString(2)
    }

    private fun buildMarkdown(c: Conversation): String = buildString {
        append("# ${c.title}\n\n")
        c.messages.forEach { m ->
            val tps = m.tps?.let { " · ${"%.1f".format(it)} tok/s" } ?: ""
            append("**${m.speaker()}**$tps\n\n")
            append(m.text.trim())
            append("\n\n")
        }
    }

    private fun buildText(c: Conversation): String = buildString {
        append("${c.title}\n").append("=".repeat(c.title.length.coerceAtMost(60))).append("\n\n")
        c.messages.forEach { m ->
            append("${m.speaker()}:\n").append(m.text.trim()).append("\n\n")
        }
    }

    private fun buildHtml(c: Conversation): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>").append(escape(c.title)).append("</title><style>")
        append("body{margin:0;background:#1b1916;color:#ede7dc;font-family:system-ui,sans-serif;padding:24px;line-height:1.6}")
        append("h1{font-size:22px}.turn{margin:18px 0}.who{font-size:12px;text-transform:uppercase;letter-spacing:.5px;color:#a69c8d}")
        append(".u .bubble{background:#2e2a24;border-radius:14px;padding:10px 14px;display:inline-block;max-width:80%}")
        append("pre{white-space:pre-wrap;word-wrap:break-word;margin:6px 0}")
        append("</style></head><body>")
        append("<h1>").append(escape(c.title)).append("</h1>")
        c.messages.forEach { m ->
            val cls = if (m.role == Role.User) "turn u" else "turn a"
            append("<div class=\"$cls\"><div class=\"who\">").append(escape(m.speaker())).append("</div>")
            if (m.role == Role.User) append("<div class=\"bubble\"><pre>").append(escape(m.text)).append("</pre></div>")
            else append("<pre>").append(escape(m.text)).append("</pre>")
            append("</div>")
        }
        append("</body></html>")
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun writePdf(out: File, c: Conversation) {
        val doc = PdfDocument()
        val pageWidth = 595   // A4 @ 72dpi
        val pageHeight = 842
        val margin = 48f
        val contentWidth = pageWidth - margin * 2

        val titlePaint = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val whoPaint = Paint().apply { textSize = 10f; color = 0xFF7A7166.toInt(); isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 12f }

        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas
        var y = margin

        fun newPage() {
            doc.finishPage(page)
            pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
            canvas = page.canvas
            y = margin
        }
        fun ensure(space: Float) { if (y + space > pageHeight - margin) newPage() }

        canvas.drawText(c.title.take(80), margin, y, titlePaint)
        y += 28f

        c.messages.forEach { m ->
            ensure(20f)
            canvas.drawText(m.speaker().uppercase(), margin, y, whoPaint)
            y += 16f
            wrap(m.text, bodyPaint, contentWidth).forEach { line ->
                ensure(16f)
                canvas.drawText(line, margin, y, bodyPaint)
                y += 16f
            }
            y += 10f
        }
        doc.finishPage(page)
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    /** Greedy word-wrap that also honors explicit newlines. */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        text.split("\n").forEach { paragraph ->
            if (paragraph.isBlank()) { lines.add(""); return@forEach }
            var current = StringBuilder()
            paragraph.split(" ").forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current = StringBuilder(candidate)
                } else {
                    if (current.isNotEmpty()) lines.add(current.toString())
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())
        }
        return lines
    }
}
