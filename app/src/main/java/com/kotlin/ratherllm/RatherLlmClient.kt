package com.kotlin.ratherllm

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicLong

/** Sampling / generation parameters mirrored to the native GenerateRequest payload. */
data class GenParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.8f,
    val topK: Int = 40,
)

/** A single streamed token decoded from the native engine. */
data class TokenChunk(
    val requestId: Long,
    val tokenId: Int,
    val text: String,
    val final: Boolean,
)

/**
 * Asynchronous client for the native engine's abstract-namespace Unix Domain
 * Socket ("\0poco_mllm_uds_pipe"). Each [streamTokens] call opens its own
 * connection, sends a framed GenerateRequest, and emits TokenChunks as a cold
 * [Flow] on [Dispatchers.IO]. Cancelling collection sends a Cancel frame and
 * closes the socket, which the native server treats as a stream teardown.
 *
 * Wire framing (little-endian) — must match include/uds_server.h:
 *   WireHeader (12 bytes): u32 magic | u16 type | u16 flags | u32 payload_len
 *   GenerateRequest payload: u64 id, u32 maxTokens, f32 temperature, u32 topK,
 *                            u32 promptLen, prompt, u32 imageLen, image
 *   TokenChunk payload:      u64 id, i32 tokenId, u32 textLen, text  (flags bit0=final)
 *   Cancel payload:          u64 id
 */
class RatherLlmClient(
    private val abstractName: String = ABSTRACT_NAME,
) {

    companion object {
        const val ABSTRACT_NAME = "poco_mllm_uds_pipe"

        private const val MAGIC: Int = 0x524C4C4D // "RLLM"
        private const val TYPE_GENERATE: Int = 1
        private const val TYPE_TOKEN: Int = 2
        private const val TYPE_CANCEL: Int = 3
        private const val TYPE_ERROR: Int = 4
        private const val FLAG_FINAL: Int = 0x1

        private const val HEADER_BYTES: Int = 12
        private const val MAX_PAYLOAD: Int = 1 sh 20 // 1 MiB hard cap (defensive)

        private val requestCounter = AtomicLong(0L)
    }

    /**
     * Stream tokens for [prompt]. The returned Flow is cold: a new socket
     * connection and generation request are created on each collection.
     */
    fun streamTokens(
        prompt: String,
        params: GenParams = GenParams(),
        imageUri: String = "",
    ): Flow<TokenChunk> = flow {
        val requestId = requestCounter.incrementAndGet()
        val socket = LocalSocket()
        try {
            socket.connect(
                LocalSocketAddress(abstractName, LocalSocketAddress.Namespace.ABSTRACT)
            )

            val output = socket.outputStream
            val input = socket.inputStream

            // Send the framed GenerateRequest.
            output.write(buildGenerateRequest(requestId, prompt, imageUri, params))
            output.flush()

            // Read the token stream.
            val header = ByteArray(HEADER_BYTES)
            while (true) {
                coroutineContext.ensureActive() // cooperative cancellation

                if (!input.readFully(header, HEADER_BYTES)) break // peer closed

                val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val magic = hb.int
                if (magic != MAGIC) throw IOException("protocol error: bad magic 0x${magic.toString(16)}")
                val type = hb.short.toInt() and 0xFFFF
                val flags = hb.short.toInt() and 0xFFFF
                val payloadLen = hb.int
                if (payloadLen < 0 || payloadLen > MAX_PAYLOAD) {
                    throw IOException("protocol error: payload_len=$payloadLen")
                }

                val payload = ByteArray(payloadLen)
                if (payloadLen > 0 && !input.readFully(payload, payloadLen)) break

                when (type) {
                    TYPE_TOKEN -> {
                        val chunk = parseTokenChunk(payload, flags)
                        emit(chunk)
                        if (chunk.final) return@flow
                    }
                    TYPE_ERROR -> throw IOException("engine reported a stream error")
                    else -> { /* ignore unknown server->client message types */ }
                }
            }
        } finally {
            // Best-effort cancel + close (covers normal completion and cancellation).
            runCatching {
                if (!socket.isClosed) {
                    runCatching {
                        socket.outputStream.write(buildCancel(requestId))
                        socket.outputStream.flush()
                    }
                }
            }
            runCatching { socket.close() }
        }
    }.flowOn(Dispatchers.IO)

    // ---- framing helpers ----------------------------------------------------

    private fun buildGenerateRequest(
        requestId: Long,
        prompt: String,
        imageUri: String,
        params: GenParams,
    ): ByteArray {
        val promptBytes = prompt.toByteArray(Charsets.UTF_8)
        val imageBytes = imageUri.toByteArray(Charsets.UTF_8)

        val payloadLen =
            8 + 4 + 4 + 4 +              // id, maxTokens, temperature, topK
                    4 + promptBytes.size +       // promptLen + prompt
                    4 + imageBytes.size          // imageLen + image

        val buf = ByteBuffer.allocate(HEADER_BYTES + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
        // Header.
        buf.putInt(MAGIC)
        buf.putShort(TYPE_GENERATE.toShort())
        buf.putShort(0) // flags
        buf.putInt(payloadLen)
        // Payload.
        buf.putLong(requestId)
        buf.putInt(params.maxTokens)
        buf.putFloat(params.temperature)
        buf.putInt(params.topK)
        buf.putInt(promptBytes.size)
        buf.put(promptBytes)
        buf.putInt(imageBytes.size)
        buf.put(imageBytes)
        return buf.array()
    }

    private fun buildCancel(requestId: Long): ByteArray {
        val payloadLen = 8
        val buf = ByteBuffer.allocate(HEADER_BYTES + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC)
        buf.putShort(TYPE_CANCEL.toShort())
        buf.putShort(0)
        buf.putInt(payloadLen)
        buf.putLong(requestId)
        return buf.array()
    }

    private fun parseTokenChunk(payload: ByteArray, flags: Int): TokenChunk {
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val requestId = b.long
        val tokenId = b.int
        val textLen = b.int
        val text = if (textLen > 0) {
            val t = ByteArray(textLen)
            b.get(t)
            String(t, Charsets.UTF_8)
        } else {
            ""
        }
        return TokenChunk(
            requestId = requestId,
            tokenId = tokenId,
            text = text,
            final = (flags and FLAG_FINAL) != 0,
        )
    }
}

/** Reads exactly [len] bytes into [buf]; returns false on EOF before completion. */
private fun InputStream.readFully(buf: ByteArray, len: Int): Boolean {
    var off = 0
    while (off < len) {
        val r = read(buf, off, len - off)
        if (r < 0) return false
        off += r
    }
    return true
}
