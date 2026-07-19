package com.kotlin.ratherllm

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** GGUF metadata for a model file, parsed from [NativeBridge.getModelInfo]. */
data class GgufModelInfo(
    val arch: String,
    val quant: String,
    val nParams: Long,
    val sizeBytes: Long,
    val nCtxTrain: Int,
    val hasTemplate: Boolean,
    val desc: String,
) {
    companion object {
        fun fromJson(json: String?): GgufModelInfo? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                GgufModelInfo(
                    arch = o.optString("arch"),
                    quant = o.optString("quant"),
                    nParams = o.optLong("nParams"),
                    sizeBytes = o.optLong("sizeBytes"),
                    nCtxTrain = o.optInt("nCtxTrain"),
                    hasTemplate = o.optBoolean("hasTemplate"),
                    desc = o.optString("desc"),
                )
            }.getOrNull()
        }
    }
}

/** A model file on disk plus (lazily) its GGUF metadata and a RAM estimate. */
data class ModelEntry(
    val file: File,
    val info: GgufModelInfo?,
    val estimatedRamBytes: Long,
) {
    val name: String get() = file.name
    val path: String get() = file.absolutePath
    val sizeBytes: Long get() = file.length()
}

/**
 * Manages GGUF models under `filesDir/models/`: listing, GGUF-magic validation,
 * Storage-Access-Framework import (with progress), deletion, and migration of
 * the legacy `filesDir/model.gguf`. Pure file/IO logic — no UI, no engine calls
 * beyond the cheap [NativeBridge.getModelInfo] metadata read.
 */
class ModelRepository(private val context: Context) {

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    /** Vision/multimodal projector files live apart so they're never listed as models. */
    val mmprojDir: File = File(context.filesDir, "mmproj").apply { mkdirs() }
    private val legacyModel: File = File(context.filesDir, "model.gguf")

    /** Total physical RAM of the device in bytes. */
    val totalRamBytes: Long by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    fun listModels(): List<ModelEntry> =
        (modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { f ->
                val info = GgufModelInfo.fromJson(runCatching { NativeBridge.getModelInfo(f.absolutePath) }.getOrNull())
                ModelEntry(f, info, estimateRamBytes(f.length()))
            }

    /** Rough resident-RAM estimate: quantized weights + KV/compute overhead. */
    fun estimateRamBytes(fileSizeBytes: Long): Long =
        fileSizeBytes + maxOf(512L * 1024 * 1024, (fileSizeBytes * 0.30).toLong())

    /** True if [fileSize] + KV would exceed a safe fraction of device RAM. */
    fun isRamRisky(fileSizeBytes: Long): Boolean =
        estimateRamBytes(fileSizeBytes) > (totalRamBytes * 0.60).toLong()

    /** Reads the first 4 bytes and checks the ASCII "GGUF" magic. */
    fun isValidGguf(file: File): Boolean = runCatching {
        file.inputStream().use { s ->
            val hdr = ByteArray(4)
            s.read(hdr) == 4 && hdr[0] == 'G'.code.toByte() && hdr[1] == 'G'.code.toByte() &&
                hdr[2] == 'U'.code.toByte() && hdr[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    /** Query a SAF document's display name and size. */
    fun queryDocument(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (ni >= 0) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return name to size
    }

    /**
     * Copies a SAF [uri] into `models/`, verifying the GGUF magic before
     * accepting it. Reports progress in [0,1] (or -1 when total size unknown).
     * Cooperative-cancellable; a cancelled/failed import leaves no partial file.
     */
    suspend fun importFromUri(uri: Uri, onProgress: (Float) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        val (queriedName, total) = queryDocument(uri)
        val baseName = (queriedName ?: "model_${System.currentTimeMillis()}.gguf")
            .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = uniqueFile(baseName)
        val tmp = File(dest.parentFile, "${dest.name}.part")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Can't open the selected file"))
            input.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20) // 1 MiB
                    var copied = 0L
                    // Validate the magic on the very first chunk before committing to a big copy.
                    var validated = false
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (!validated) {
                            if (n < 4 || buf[0] != 'G'.code.toByte() || buf[1] != 'G'.code.toByte() ||
                                buf[2] != 'U'.code.toByte() || buf[3] != 'F'.code.toByte()
                            ) {
                                return@withContext Result.failure(
                                    IllegalArgumentException("Not a GGUF model file (bad magic header)")
                                )
                            }
                            validated = true
                        }
                        out.write(buf, 0, n)
                        copied += n
                        onProgress(if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true); tmp.delete()
            }
            Result.success(dest)
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            Result.failure(t)
        }
    }

    /**
     * Copies a SAF [uri] holding a vision projector (mmproj) GGUF into `mmproj/`,
     * validating the GGUF magic. Returns the stored file so its name can be paired
     * with a model in [ModelMeta.mmproj].
     */
    suspend fun importMmproj(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        val (queriedName, _) = queryDocument(uri)
        val baseName = (queriedName ?: "mmproj_${System.currentTimeMillis()}.gguf")
            .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = uniqueIn(mmprojDir, baseName)
        val tmp = File(dest.parentFile, "${dest.name}.part")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Can't open the selected file"))
            input.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var validated = false
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (!validated) {
                            if (n < 4 || buf[0] != 'G'.code.toByte() || buf[1] != 'G'.code.toByte() ||
                                buf[2] != 'U'.code.toByte() || buf[3] != 'F'.code.toByte()
                            ) {
                                return@withContext Result.failure(
                                    IllegalArgumentException("Not a GGUF projector file (bad magic header)")
                                )
                            }
                            validated = true
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            Result.success(dest)
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            Result.failure(t)
        }
    }

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    /** Moves the legacy `filesDir/model.gguf` into `models/` if present. Returns the new file or null. */
    fun migrateLegacyModel(): File? {
        if (!legacyModel.exists() || legacyModel.length() < 4) return null
        val dest = uniqueFile("model.gguf")
        return if (legacyModel.renameTo(dest)) dest
        else runCatching { legacyModel.copyTo(dest, overwrite = true).also { legacyModel.delete() } }.getOrNull()
    }

    private fun uniqueFile(name: String): File = uniqueIn(modelsDir, name)

    private fun uniqueIn(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "gguf")
        var i = 1
        while (candidate.exists()) { candidate = File(dir, "${stem}_$i.$ext"); i++ }
        return candidate
    }
}
