package com.kotlin.ratherllm

/** Conversation roles, mapped to chat-template roles in native code. */
enum class Role(val wire: String) { System("system"), User("user"), Assistant("assistant") }

/** A single chat turn. */
data class ChatMessage(val role: Role, val text: String)

/** Sampling / generation parameters passed to the native engine. */
data class GenParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.8f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
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
