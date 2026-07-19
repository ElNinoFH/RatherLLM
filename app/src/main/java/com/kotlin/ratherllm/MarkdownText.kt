package com.kotlin.ratherllm

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface MdBlock {
    data class Body(val text: String) : MdBlock
    data class Code(val code: String, val lang: String) : MdBlock
}

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.split("\n")
    val body = StringBuilder()
    var i = 0
    fun flush() { if (body.isNotBlank()) out.add(MdBlock.Body(body.toString().trim('\n'))); body.clear() }
    while (i < lines.size) {
        if (lines[i].trimStart().startsWith("```")) {
            flush()
            val lang = lines[i].trimStart().removePrefix("```").trim()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.append(lines[i]).append('\n'); i++ }
            i++ // closing fence
            out.add(MdBlock.Code(code.toString().trimEnd('\n'), lang))
        } else {
            body.append(lines[i]).append('\n'); i++
        }
    }
    flush()
    return out
}

/** Renders a subset of Markdown: fenced code blocks (with copy), inline code, bold, and dash/star bullet lists. */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Code -> CodeBlock(b.code, b.lang)
                is MdBlock.Body -> Text(buildBody(b.text), color = color, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, lang: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Surface(
        color = AppColors.CodeBg,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.cream(0.09f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 2.dp, end = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    lang.ifBlank { "code" }.uppercase(),
                    style = AppText.MetaTiny,
                    color = AppColors.TextFaint,
                )
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = "Copy code",
                        tint = AppColors.TextMuted,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = AppColors.CodeText,
                )
            }
        }
    }
}

/** Bullet normalization + inline **bold** / `code`. */
private fun buildBody(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.split("\n")
    lines.forEachIndexed { idx, raw ->
        val trimmed = raw.trimStart()
        val content = when {
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> { append("  •  "); trimmed.substring(2) }
            else -> raw
        }
        appendInline(content)
        if (idx < lines.lastIndex) append("\n")
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(text: String) {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end >= 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append("**"); i += 2 }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end >= 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append('`'); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}
