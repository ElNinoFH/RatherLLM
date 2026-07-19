package com.kotlin.ratherllm

import java.util.UUID

/** Conversation roles, mapped to chat-template roles in native code. */
enum class Role(val wire: String) { System("system"), User("user"), Assistant("assistant") }

/**
 * A single chat turn. [id] is stable so replies can be saved/unsaved and matched
 * across recompositions; [tps] and [modelName] annotate assistant turns (the model
 * that produced them and its decode rate) for the design's per-message metadata.
 */
data class ChatMessage(
    val role: Role,
    val text: String,
    val id: String = UUID.randomUUID().toString(),
    val tps: Float? = null,
    val modelName: String? = null,
    val attachments: List<Attachment> = emptyList(),
)

/** Whether an [Attachment] is a picture (shown as a thumbnail) or a generic file. */
enum class AttachmentKind(val wire: String) {
    Image("image"), File("file");

    companion object {
        fun fromWire(w: String): AttachmentKind = entries.firstOrNull { it.wire == w } ?: File
    }
}

/**
 * A file or photo the user attached to a message. [path] is an absolute path to a
 * copy the app owns (under cacheDir/attachments), so it survives the transient
 * content:// permission of the picker/camera that produced it.
 */
data class Attachment(
    val kind: AttachmentKind,
    val path: String,
    val name: String,
    val mime: String = "",
)

/** Multimodal capabilities a model may advertise (mirrors the design's Text/Image/Audio/Video). */
enum class ModelCapability(val key: String, val label: String) {
    Text("text", "Text"), Image("image", "Image"), Audio("audio", "Audio"), Video("video", "Video");

    companion object {
        fun fromKeys(keys: Set<String>): Set<ModelCapability> = entries.filter { it.key in keys }.toSet()
    }
}

/**
 * User-editable, per-model metadata not derivable from the GGUF header: a short
 * description shown in the picker, declared capabilities, and an optional paired
 * mmproj (vision/multimodal projector) file name. Keyed by model file name.
 */
data class ModelMeta(
    val description: String = "",
    val capabilities: Set<ModelCapability> = setOf(ModelCapability.Text),
    val mmproj: String? = null,
)

/** A bookmarked assistant reply, shown in the drawer's "Saved replies" section. */
data class SavedReply(
    val messageId: String,
    val text: String,
    val conversationId: String,
    val conversationTitle: String,
    val savedAt: Long = System.currentTimeMillis(),
)

/** A live device-analytics sample (only produced while analytics is enabled). */
data class DeviceStats(val cpuPercent: Int, val ramPercent: Int, val tempCelsius: Int)

/**
 * Sampling / generation parameters passed to the native engine. Defaults follow
 * Google's Gemma 3 recommendation (temp 1.0, top-k 64, top-p 0.95, min-p 0) plus
 * a mild repetition penalty; higher temperature also strongly reduces the
 * degenerate loops seen with low-temperature greedy-ish decoding.
 */
data class GenParams(
    val maxTokens: Int = 512,
    val temperature: Float = 1.0f,
    val topK: Int = 64,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,   // filters the low-prob garbage tail of lossy quants (e.g. Q4_0)
    val repeatPenalty: Float = 1.1f,
    val seed: Int = -1,
)

/** A streamed decode result: an incremental [text] piece and a [final] marker. */
data class TokenChunk(val text: String, val final: Boolean)

/**
 * Native result codes. Values in -1001..-1006 are pre-flight model-validation
 * domain codes (not errno) so the UI can render precise, human messages.
 */
object Rc {
    const val NOT_IMPLEMENTED = -1000

    const val MODEL_MISSING    = -1001
    const val MODEL_UNREADABLE = -1002
    const val NOT_REGULAR      = -1003
    const val MODEL_EMPTY      = -1004
    const val MODEL_TOO_SMALL  = -1005
    const val BAD_MAGIC        = -1006

    const val LOAD_FAILED  = -2001
    const val BAD_PARAMS   = -2002
    const val UNSUPPORTED  = -2003
    const val OOM          = -2004

    /** Maps a negative code to a human-friendly, actionable message. */
    fun message(rc: Int): String = when (rc) {
        NOT_IMPLEMENTED -> "Engine not implemented yet (Stage 1 skeleton)"
        MODEL_MISSING    -> "Model file not found"
        MODEL_UNREADABLE -> "Model file can't be read (check permissions)"
        NOT_REGULAR      -> "Model path is not a regular file"
        MODEL_EMPTY      -> "Model file is empty (0 bytes)"
        MODEL_TOO_SMALL  -> "Model file is too small to be a valid GGUF"
        BAD_MAGIC        -> "Not a GGUF model (bad magic header)"
        LOAD_FAILED      -> "Failed to load the model"
        BAD_PARAMS       -> "Invalid generation parameters"
        UNSUPPORTED      -> "This model architecture isn't supported"
        OOM              -> "Not enough memory to load this model"
        else             -> "Engine error (rc=$rc)"
    }
}
