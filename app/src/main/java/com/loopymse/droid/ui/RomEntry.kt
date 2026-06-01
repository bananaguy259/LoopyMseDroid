package com.loopymse.droid.ui

import android.net.Uri
import java.io.File

/**
 * Represents a single Casio Loopy ROM in the library.
 * Stored as a serialized entry in SharedPreferences (JSON list).
 */
data class RomEntry(
    /** URI string of the ROM file (from SAF file picker) */
    val uriString: String,
    /** Display name — derived from filename, can be user-edited later */
    val displayName: String,
    /** File size in bytes — cached from when the ROM was added */
    val fileSizeBytes: Long,
    /** Timestamp (System.currentTimeMillis) of last launch, or 0 if never */
    val lastPlayedMs: Long = 0L
) {
    val uri: Uri get() = Uri.parse(uriString)

    /** Human-readable file size: "2.0 MB", "512 KB" etc. */
    val fileSizeFormatted: String get() = when {
        fileSizeBytes >= 1_048_576 -> "%.1f MB".format(fileSizeBytes / 1_048_576.0)
        fileSizeBytes >= 1_024     -> "%.0f KB".format(fileSizeBytes / 1_024.0)
        else                       -> "$fileSizeBytes B"
    }

    /**
     * Single-line metadata string shown below the title in the list:
     * e.g. "wanwan.bin · 2.0 MB · Last played 2 days ago"
     */
    fun metaString(lastPlayedLabel: String): String {
        val filename = uri.lastPathSegment?.substringAfterLast('/') ?: displayName
        return "$filename · $fileSizeFormatted · $lastPlayedLabel"
    }
}
