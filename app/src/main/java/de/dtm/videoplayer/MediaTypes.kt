package de.dtm.videoplayer

import java.util.Locale

/** Welche Dateien der Player anzeigt und abspielt. */
object MediaTypes {

    val EXTENSIONS = setOf(
        // Video
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "webm", "ts", "m2ts", "mts", "mpg", "mpeg", "mpe",
        "vob", "3gp", "3g2", "ogv", "asf", "divx", "f4v", "mxf", "rm", "rmvb", "xvid", "m2v", "dv",
        // Audio
        "mp3", "flac", "ogg", "oga", "opus", "wav", "m4a", "aac", "wma", "mka", "amr", "ape", "ac3", "dts", "aiff"
    )

    fun isPlayable(name: String, mime: String?): Boolean {
        val m = mime ?: ""
        if (m.startsWith("video/") || m.startsWith("audio/")) return true
        if (m == "application/x-matroska" || m == "application/ogg" || m == "application/mp4") return true
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase(Locale.ROOT) in EXTENSIONS
    }

    fun isAudio(name: String, mime: String?): Boolean {
        val m = mime ?: ""
        if (m.startsWith("audio/")) return true
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in setOf("mp3", "flac", "ogg", "oga", "opus", "wav", "m4a", "aac", "wma", "mka", "amr", "ape", "ac3", "dts", "aiff")
    }
}
