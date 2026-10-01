package app.navelo.shared

import java.security.MessageDigest
import java.util.Locale

data class ParsedName(val title: String, val year: Int? = null, val show: String? = null, val season: Int? = null, val episode: Int? = null)
object FilenameParser {
    private val episodePattern = Regex("(?i)(?:\\bS(\\d{1,3})[ ._-]*E(\\d{1,4})\\b|\\b(\\d{1,3})x(\\d{1,4})\\b)")
    private val seasonFolder = Regex("(?i)^season[ ._-]*(\\d{1,3})$")
    private val episodeOnly = Regex("(?i)^(?:episode|ep|e)[ ._-]*(\\d{1,4})\\b")
    private val release = Regex("(?i)\\b(?:480[pi]|576[pi]|720[pi]|1080[pi]|2160[pi]|4320[pi]|4k|8k|uhd|bluray|blu-ray|brrip|bdrip|web[ ._-]?dl|webrip|hdtv|dvdrip|remux|x26[45]|h[ .]?26[45]|hevc|av1|aac|dts|truehd|ddp|ac3|hdr10?\\+?|dolby|proper|repack|yify|yts|rarbg)\\b")
    private val yearPattern = Regex("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)")
    fun parse(filename: String, relativePath: String = ""): ParsedName {
        val stem = filename.substringBeforeLast('.', filename)
        val spaced = stem.replace('.', ' ').replace('_', ' ')
        val parts = relativePath.replace('\\', '/').split('/').filter { it.isNotBlank() }
        val match = episodePattern.find(spaced)
        val folderSeasonIndex = parts.indexOfLast { seasonFolder.matches(it) }
        val seasonFromFolder = parts.getOrNull(folderSeasonIndex)?.let { seasonFolder.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        val epOnly = episodeOnly.find(spaced)
        if (match != null || (seasonFromFolder != null && epOnly != null)) {
            val season = match?.let { (it.groupValues[1].ifEmpty { it.groupValues[3] }).toInt() } ?: seasonFromFolder
            val episode = match?.let { (it.groupValues[2].ifEmpty { it.groupValues[4] }).toInt() } ?: epOnly!!.groupValues[1].toInt()
            val prefix = match?.let { clean(spaced.substring(0, it.range.first)) }.orEmpty()
            val folderTitle = if (folderSeasonIndex > 0) parts[folderSeasonIndex - 1] else parts.dropLast(1).lastOrNull()
            val show = prefix.ifBlank { clean(folderTitle ?: "TV Show") }
            return ParsedName(show, show = show, season = season, episode = episode)
        }
        val releaseStart = release.find(spaced)?.range?.first ?: spaced.length
        val withoutRelease = spaced.substring(0, releaseStart)
        // Keep numeric titles (1917, 2001) intact; release years follow a title.
        val yearMatch = yearPattern.findAll(withoutRelease).lastOrNull { it.range.first > 0 }
        val title = clean(if (yearMatch != null) withoutRelease.substring(0, yearMatch.range.first) else withoutRelease).ifBlank { clean(spaced) }
        return ParsedName(title, yearMatch?.value?.toInt())
    }
    private fun clean(value: String) = value.replace(Regex("\\[[^]]*]"), " ").replace(Regex("[._]"), " ")
        .trim(' ', '-', '(', ')', '[', ']').replace(Regex("\\s+"), " ")
}
object StableIds {
    fun forDocument(rootId: String, documentId: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$rootId\u0000$documentId".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}
