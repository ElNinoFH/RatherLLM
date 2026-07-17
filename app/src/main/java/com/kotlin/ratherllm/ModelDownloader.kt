package com.kotlin.ratherllm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Progress of an in-flight download. [total] is -1 when the server omits a length. */
data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else -1f
}

/**
 * Downloads a `.gguf` from a direct URL into `models/`, with resume support
 * (HTTP Range) across app restarts via a persistent `.part` file, progress
 * reporting, cooperative cancellation, and GGUF-magic validation on completion.
 *
 * No login/API key handling — the URL must be directly fetchable (e.g. a
 * Hugging Face `resolve/main/....gguf` link).
 */
class ModelDownloader(private val repo: ModelRepository) {

    suspend fun download(
        url: String,
        fileName: String,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val safeName = fileName
            .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(repo.modelsDir, safeName)
        val part = File(repo.modelsDir, "$safeName.part")
        var existing = if (part.exists()) part.length() else 0L

        try {
            var conn = openConnection(url, existing)
            var code = conn.responseCode

            // Server ignored our Range (200 instead of 206): restart from scratch.
            if (existing > 0 && code == HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                part.delete(); existing = 0L
                conn = openConnection(url, 0L)
                code = conn.responseCode
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                conn.disconnect()
                return@withContext Result.failure(IllegalStateException("Download failed: HTTP $code"))
            }

            val contentLen = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            val total = if (contentLen > 0) existing + contentLen else -1L

            conn.inputStream.use { ins ->
                java.io.FileOutputStream(part, /* append = */ existing > 0).use { out ->
                    val buf = ByteArray(1 shl 20)
                    var downloaded = existing
                    onProgress(DownloadProgress(downloaded, total))
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(DownloadProgress(downloaded, total))
                    }
                }
            }
            conn.disconnect()

            if (!repo.isValidGguf(part)) {
                part.delete()
                return@withContext Result.failure(IllegalArgumentException("Downloaded file is not a valid GGUF model"))
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
            Result.success(dest)
        } catch (t: Throwable) {
            // Keep the .part file so the next attempt can resume; just surface the error.
            Result.failure(t)
        }
    }

    /** Deletes any partial download for [fileName]. */
    fun clearPartial(fileName: String) {
        val safeName = if (fileName.endsWith(".gguf", true)) fileName else "$fileName.gguf"
        File(repo.modelsDir, "$safeName.part").delete()
    }

    private fun openConnection(url: String, resumeFrom: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ratherllm/1.0")
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            connect()
        }

    companion object {
        /** A couple of small, beginner-friendly starting-point models (official HF links). */
        val RECOMMENDED = listOf(
            RecommendedModel(
                "Qwen2.5 1.5B Instruct (Q4_K_M)", "~1.0 GB",
                "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            ),
            RecommendedModel(
                "Gemma 3 1B Instruct (Q4_K_M)", "~0.8 GB",
                "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
                "gemma-3-1b-it-Q4_K_M.gguf",
            ),
        )
    }
}

data class RecommendedModel(val label: String, val approxSize: String, val url: String, val fileName: String)
