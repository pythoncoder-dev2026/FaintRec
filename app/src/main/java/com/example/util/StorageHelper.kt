package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

enum class FaintRecFolder(val relativeSubPath: String) {
    ROOT("Documents/FaintRec"),
    IMPORTED("Documents/FaintRec/Imported"),
    CONVERTED("Documents/FaintRec/Converted"),
    SPIRIT_BOX("Documents/FaintRec/SpiritBox"),
    PROCESSED("Documents/FaintRec/Processed")
}

object StorageHelper {

    /**
     * Returns an app-private working file used for high-throughput, random-access WAV writing,
     * reverse streaming, editing, and FFT analysis, which is then mirrored to Documents/FaintRec via MediaStore.
     */
    fun createWorkingFile(context: Context, folder: FaintRecFolder, fileName: String): File {
        val dir = File(context.filesDir, folder.relativeSubPath)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, sanitizeFileName(fileName))
    }

    fun getAvailableBytes(context: Context): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    fun hasSufficientSpace(context: Context, minRequiredBytes: Long = 20L * 1024L * 1024L): Boolean {
        return getAvailableBytes(context) >= minRequiredBytes
    }

    /**
     * Publishes a completed audio or JSON sidecar file into Documents/FaintRec/... via MediaStore
     * on Android 10+ (API 29+), or into app external documents on API 26-28.
     * Returns the MediaStore content URI string if published.
     */
    fun publishToMediaStore(
        context: Context,
        sourceFile: File,
        folder: FaintRecFolder,
        mimeType: String,
        displayName: String = sourceFile.name
    ): String? {
        if (!sourceFile.exists()) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizeFileName(displayName))
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder.relativeSubPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = resolver.insert(collection, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out, bufferSize = 65536)
                    }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri.toString()
            } else {
                val extDir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    folder.relativeSubPath.removePrefix("Documents/")
                )
                if (!extDir.exists()) extDir.mkdirs()
                val dest = File(extDir, sanitizeFileName(displayName))
                sourceFile.copyTo(dest, overwrite = true)
                Uri.fromFile(dest).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteMediaStoreUri(context: Context, mediaStoreUri: String?) {
        if (mediaStoreUri.isNullOrBlank()) return
        try {
            val uri = Uri.parse(mediaStoreUri)
            if (uri.scheme == "content") {
                context.contentResolver.delete(uri, null, null)
            } else if (uri.scheme == "file" && uri.path != null) {
                File(uri.path!!).delete()
            }
        } catch (_: Exception) {
        }
    }

    fun getShareUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun formatSizeBytes(bytes: Long): String = AudioMath.formatFileSize(bytes)

    fun shareUri(context: Context, uri: Uri, mimeType: String, title: String = "Share audio") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun shareFile(context: Context, file: File, mimeType: String, title: String = "Share audio") {
        if (!file.exists()) return
        val uri = getShareUri(context, file)
        shareUri(context, uri, mimeType, title)
    }

    fun openDocumentsFolder(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    MediaStore.Files.getContentUri("external"),
                    "vnd.android.document/directory"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }

    fun mimeTypeForExtension(ext: String): String = when (ext.lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "json" -> "application/json"
        else -> "audio/*"
    }

    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim().ifBlank { "FaintRec_Audio.wav" }
    }
}
