package com.pdfreader.app.data.model

data class Book(
    val id: String,
    val name: String,
    val owner: String,
    val driveFileId: String,
    val thumbnailUrl: String? = null,
    val localThumbPath: String? = null,   // locally rendered cover PNG
    val fileSize: Long = 0L,
    val localPath: String? = null
) {
    /** Clean title for display: drops "Emailing " from forwarded files and underscores. */
    val displayName: String get() = prettyTitle(name)
}

fun prettyTitle(raw: String): String =
    raw.removePrefix("Emailing ").removeSuffix(".pdf").replace('_', ' ').replace(Regex("\\s+"), " ").trim()
