package com.hassanazawi.send.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.OutputStream

/** A file being written into Downloads/Send. It only becomes visible to other apps on commit(). */
class Pending(private val cr: ContentResolver, val uri: Uri, pfd: ParcelFileDescriptor) {
    val out: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)

    fun commit() {
        out.close()
        cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun abort() {
        runCatching { out.close() }
        runCatching { cr.delete(uri, null, null) }
    }
}

object Files {
    private val illegal = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    fun cleanName(n: String): String {
        var s = illegal.replace(n, "_").trim().trim('.').trim()
        if (s.isEmpty()) s = "file"
        return if (s.length > 180) s.take(180) else s
    }

    /** Folder part of a received path: relative, no "..", no empty segments. */
    fun cleanRel(r: String?): String? {
        if (r.isNullOrBlank()) return null
        val parts = r.split('/', '\\')
            .map { illegal.replace(it, "_").trim().trim('.').trim() }
            .filter { it.isNotEmpty() }
            .take(12)
        return if (parts.isEmpty()) null else parts.joinToString("/")
    }

    /** Creates Download/Send[/rel]/name. MediaStore picks "name (1).ext" itself if it already exists. */
    fun create(ctx: Context, name: String, rel: String?): Pending {
        val cr = ctx.contentResolver
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val path = "Download/Send" + (if (rel != null) "/$rel" else "") + "/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("cannot create $name")
        val pfd = cr.openFileDescriptor(uri, "w")
        if (pfd == null) {
            cr.delete(uri, null, null)
            throw java.io.IOException("cannot open $name")
        }
        return Pending(cr, uri, pfd)
    }

    /** Name and size of something the user picked or shared. */
    fun describe(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        }
        if (size < 0) runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize } }
        return cleanName(name) to maxOf(size, 0L)
    }
}
