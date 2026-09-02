package com.qiuminal.juicedict.engine

/**
 * Metadata parsed from the `.ifo` file. See the official StarDict file format
 * description for the meaning of each field.
 */
data class Ifo(
    val version: String?,
    val bookName: String,
    val wordCount: Long,
    val idxFileSize: Long,
    /** Empty string means every entry carries its own type sequence. */
    val sameTypeSequence: String,
    val synWordCount: Long,
    /** 32 or 64. When 64, offsets and sizes in the .idx are 8 bytes each. */
    val idxOffsetBits: Int,
    val dictType: String?,
    val author: String?,
    val email: String?,
    val website: String?,
    val date: String?,
    val description: String?,
) {
    companion object {
        fun parse(text: String): Ifo {
            val map = HashMap<String, String>()
            for (line in text.lineSequence()) {
                val t = line.trim()
                val eq = t.indexOf('=')
                if (eq > 0) map[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
            }
            return Ifo(
                version = map["version"],
                bookName = map["bookname"] ?: "Unknown",
                wordCount = map["wordcount"]?.toLongOrNull() ?: 0L,
                idxFileSize = map["idxfilesize"]?.toLongOrNull() ?: 0L,
                sameTypeSequence = map["sametypesequence"] ?: "",
                synWordCount = map["synwordcount"]?.toLongOrNull() ?: 0L,
                idxOffsetBits = map["idxoffsetbits"]?.toIntOrNull() ?: 32,
                dictType = map["dicttype"],
                author = map["author"],
                email = map["email"],
                website = map["website"],
                date = map["date"],
                description = map["description"],
            )
        }
    }
}
