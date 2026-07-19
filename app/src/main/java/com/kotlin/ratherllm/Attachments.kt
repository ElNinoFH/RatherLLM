package com.kotlin.ratherllm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * Owns the copies of user-attached files/photos under `cacheDir/attachments/`.
 *
 * The pickers and the camera hand back a transient `content://` URI whose read
 * grant evaporates once the picker Activity is gone, so we eagerly copy the bytes
 * into a file the app owns and reference that from then on. Images are additionally
 * decoded (downsampled) for the in-bubble thumbnail; text-like files can be read
 * back into the prompt so a text model can actually use their contents.
 */
class AttachmentStore(private val context: Context) {

    private val dir: File = File(context.cacheDir, "attachments").apply { mkdirs() }

    /** Copies [uri] into the cache and returns an [Attachment], or null on failure. */
    fun importFromUri(uri: Uri, kind: AttachmentKind): Attachment? = runCatching {
        val (displayName, _) = query(uri)
        val mime = context.contentResolver.getType(uri) ?: ""
        val safeName = (displayName ?: "attachment_${System.currentTimeMillis()}")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = unique(safeName)
        context.contentResolver.openInputStream(uri)?.use { ins ->
            dest.outputStream().use { out -> ins.copyTo(out, 1 shl 16) }
        } ?: return null
        Attachment(kind, dest.absolutePath, displayName ?: dest.name, mime)
    }.getOrNull()

    /**
     * Prepares an empty destination file for a camera capture and returns it with
     * a FileProvider URI the camera app can write to. Turn the result into an
     * [Attachment] with [attachmentForCameraOutput] once capture succeeds.
     */
    fun newCameraTarget(): Pair<File, Uri> {
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    fun attachmentForCameraOutput(file: File): Attachment =
        Attachment(AttachmentKind.Image, file.absolutePath, file.name, "image/jpeg")

    private fun query(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        return name to size
    }

    private fun unique(name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isBlank()) "${stem}_$i" else "${stem}_$i.$ext"); i++
        }
        return candidate
    }
}

/** Image decode + prompt helpers, independent of any store instance. */
object AttachmentUtil {

    /** File extensions we can safely inline into a text prompt. */
    private val TEXT_EXTS = setOf(
        "txt", "md", "markdown", "json", "csv", "tsv", "log", "xml", "yaml", "yml",
        "kt", "java", "py", "js", "ts", "tsx", "c", "cpp", "h", "hpp", "rs", "go",
        "sh", "html", "css", "sql", "toml", "ini", "cfg", "properties", "gradle",
    )

    private const val MAX_TEXT_CHARS = 12_000

    fun isImage(a: Attachment): Boolean =
        a.kind == AttachmentKind.Image || a.mime.startsWith("image/")

    fun isTextLike(a: Attachment): Boolean {
        if (a.mime.startsWith("text/")) return true
        val ext = a.name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTS
    }

    /** Reads a text-like attachment's contents (truncated) for prompt embedding. */
    fun readTextForPrompt(a: Attachment): String? {
        if (!isTextLike(a)) return null
        return runCatching {
            val f = File(a.path)
            if (!f.exists()) return null
            val raw = f.readText()
            if (raw.length > MAX_TEXT_CHARS) raw.take(MAX_TEXT_CHARS) + "\n…[truncated]" else raw
        }.getOrNull()
    }

    /**
     * Decodes a downsampled [Bitmap] from an image attachment for the thumbnail,
     * bounded to [maxPx] on the long edge to keep memory tiny.
     */
    fun decodeThumbnail(path: String, maxPx: Int = 512): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / sample > maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, opts)
    }.getOrNull()
}
