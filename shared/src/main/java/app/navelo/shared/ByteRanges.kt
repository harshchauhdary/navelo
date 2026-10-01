package app.navelo.shared

/** All offsets are 64-bit; a range is never materialised in memory. */
data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
    fun contentRange(size: Long) = "bytes $start-$endInclusive/$size"
}
class InvalidRange : IllegalArgumentException("Requested bytes are not available")
object ByteRanges {
    fun parse(header: String?, size: Long): ByteRange? {
        require(size >= 0)
        if (header == null) return null
        if (size == 0L || !header.startsWith("bytes=") || ',' in header) throw InvalidRange()
        val match = Regex("^(\\d*)-(\\d*)$").matchEntire(header.removePrefix("bytes=").trim()) ?: throw InvalidRange()
        val (left, right) = match.destructured
        if (left.isEmpty()) {
            val suffix = right.toLongOrNull()?.takeIf { it > 0 } ?: throw InvalidRange()
            return ByteRange((size - suffix).coerceAtLeast(0), size - 1)
        }
        val start = left.toLongOrNull()?.takeIf { it < size } ?: throw InvalidRange()
        val end = if (right.isEmpty()) size - 1 else right.toLongOrNull() ?: throw InvalidRange()
        if (end < start) throw InvalidRange()
        return ByteRange(start, end.coerceAtMost(size - 1))
    }
}
