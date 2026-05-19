package com.patron.snaglite.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream

object MediaSink {

    data class Published(val uri: Uri, val displayName: String)

    private const val TAG = "MediaSink"
    private const val MOVIES_RELATIVE = "Movies/SnagLite"
    private const val MUSIC_RELATIVE = "Music/SnagLite"

    fun publishToMovies(context: Context, source: File, audioOnly: Boolean): Published? {
        if (!source.exists() || source.length() == 0L) {
            Log.w(TAG, "source missing/empty: $source")
            return null
        }

        val displayName = source.name
        val mime = if (audioOnly) "audio/mp4" else "video/mp4"

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(context, source, displayName, mime, audioOnly)
            } else {
                publishViaLegacyFs(source, displayName, audioOnly)
            }
        }.onFailure { Log.e(TAG, "publish failed", it) }.getOrNull()
    }

    private fun publishViaMediaStore(
        context: Context,
        source: File,
        displayName: String,
        mime: String,
        audioOnly: Boolean,
    ): Published? {
        val resolver = context.contentResolver
        val collection = if (audioOnly)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (audioOnly) MUSIC_RELATIVE else MOVIES_RELATIVE)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri, "w")?.use { out ->
            FileInputStream(source).use { input -> input.copyTo(out, bufferSize = 1 shl 16) }
        } ?: run {
            resolver.delete(uri, null, null)
            return null
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        source.delete()
        return Published(uri, displayName)
    }

    /**
     * Removes a previously-published file. Returns true if the underlying file
     * was actually removed. Works for MediaStore-inserted items (this app owns
     * the rows so no user consent dialog is needed) and for legacy file:// URIs.
     */
    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        when (uri.scheme?.lowercase()) {
            "content" -> context.contentResolver.delete(uri, null, null) > 0
            "file" -> uri.path?.let { File(it).delete() } ?: false
            else -> false
        }
    }.onFailure { Log.w(TAG, "delete failed for $uri", it) }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun publishViaLegacyFs(source: File, displayName: String, audioOnly: Boolean): Published? {
        val baseDir = if (audioOnly)
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        else
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val target = File(baseDir, "SnagLite").apply { if (!exists()) mkdirs() }
        val out = File(target, displayName)
        if (!source.renameTo(out)) {
            FileInputStream(source).use { input ->
                out.outputStream().use { dst -> input.copyTo(dst) }
            }
            source.delete()
        }
        return Published(Uri.fromFile(out), displayName)
    }

    /**
     * One-shot migration of previously-published files from the legacy
     * `Movies/Snag` and `Music/Snag` MediaStore paths to the new
     * `Movies/SnagLite` / `Music/SnagLite` paths. Best-effort: rows owned by
     * another package (the old `com.patron.snag` install) silently fail to
     * update, which is acceptable.
     *
     * No-op on API < 29 (legacy FS); legacy-FS users keep their old folder.
     */
    fun migrateLegacyPublishedFiles(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        data class Move(val collection: Uri, val oldPath: String, val newPath: String)
        val moves = listOf(
            Move(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "Movies/Snag/", "Movies/SnagLite/",
            ),
            Move(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "Music/Snag/", "Music/SnagLite/",
            ),
        )
        for ((collection, oldPath, newPath) in moves) {
            runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf(oldPath),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val uri = ContentUris.withAppendedId(collection, id)
                        val cv = ContentValues().apply {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, newPath)
                        }
                        runCatching { resolver.update(uri, cv, null, null) }
                    }
                }
            }.onFailure { Log.w(TAG, "media migration query failed for $oldPath", it) }
        }
    }
}
