package com.patron.snaglite.download

/**
 * Turns raw yt-dlp / resolver output into something worth putting on a card.
 *
 * The card used to show `response.err` verbatim — a 400-character tail of a
 * developer log, `ERROR:` prefix included, cut wherever the limit landed. That is
 * the right text for a bug report and the wrong text for a user.
 *
 * The classifier is deliberately conservative. Matching on another project's error
 * strings is matching on something that changes between releases, and a confidently
 * wrong message is worse than an opaque one — so anything unrecognised falls through
 * to a generic line, and the raw text is always preserved as [Classified.detail]
 * rather than discarded.
 */
internal object DownloadErrors {

    data class Classified(
        val message: String,
        /** Raw tool output, shown only behind the card's Details toggle. */
        val detail: String?,
    )

    private const val GENERIC = "Download failed"

    fun classify(raw: String): Classified {
        val text = raw.trim()
        if (text.isEmpty()) return Classified(GENERIC, null)
        val h = text.lowercase()

        val message = when {
            // Order matters, and specific beats generic. "Unable to download webpage"
            // is yt-dlp's wrapper around *any* fetch failure — it prefixes 404s and
            // 403s as readily as real connectivity problems — so every HTTP status
            // has to be tested before anything that could match that wrapper.
            h.contains("sign in to confirm") ||
                h.contains("please sign in") ||
                h.contains("login required") ||
                h.contains("age-restricted") ||
                h.contains("members-only") ||
                h.contains("private video") ->
                "This video requires signing in"

            h.contains("not available in your country") ||
                h.contains("geo restricted") ||
                h.contains("geo-restricted") ||
                h.contains("blocked it in your country") ->
                "Not available in your region"

            h.contains("video unavailable") || h.contains("has been removed") ->
                "This video is no longer available"

            h.contains("http error 404") || h.contains("not found") ->
                "That link doesn't work"

            h.contains("http error 403") || h.contains("forbidden") ->
                "The site blocked this download"

            h.contains("http error 429") || h.contains("too many requests") ->
                "Too many requests — wait a moment and retry"

            h.contains("no space left") || h.contains("enospc") ->
                "Not enough space on your device"

            // Genuine connectivity only. Deliberately excludes "unable to download
            // webpage": that fired on an HTTP 404 during testing and produced a
            // confident "check your network" on a link that was simply missing.
            h.contains("temporary failure in name resolution") ||
                h.contains("name or service not known") ||
                h.contains("network is unreachable") ||
                h.contains("connection refused") ||
                h.contains("connection reset") ||
                h.contains("timed out") ->
                "No connection — check your network and retry"

            h.contains("unsupported url") || h.contains("no video formats found") ->
                "This site isn't supported yet"

            h.contains("is not a valid url") || h.contains("control characters") ->
                "That doesn't look like a valid link"

            else -> GENERIC
        }

        // Keep the raw text only when it is not already what we are showing, so the
        // Details toggle never just repeats the headline.
        val detail = text.takeIf { it.isNotBlank() && !it.equals(message, ignoreCase = true) }
        return Classified(message, cleanDetail(detail))
    }

    /**
     * Tidies raw output for the Details pane: drops yt-dlp's `ERROR:` prefix and
     * collapses the blank lines left by concatenating stderr and stdout. Deliberately
     * does not truncate — this pane is where the full text belongs.
     */
    private fun cleanDetail(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { it.removePrefix("ERROR:").trim() }
            .ifBlank { null }
    }
}
