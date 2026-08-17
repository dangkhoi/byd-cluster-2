package com.byd.clusternav.navigation

/**
 * Best-effort extraction of a maneuver / arrow HINT from the GMaps accessibility tree (T3 telemetry).
 *
 * GMaps rarely exposes the turn instruction as clean text — the directional cue usually lives on the arrow
 * ImageView's `contentDescription` ("Turn right onto …", "Rẽ phải …"). [com.byd.clusternav.NavAccessLog]
 * logs this next to the screen-read distance/road so we can compare it against our own bitmap-classified
 * arrow off-car. Pure (no Android) so it is unit-tested; the node-tree walk that produces the candidate
 * strings stays in the :app accessibility service.
 *
 * This is diagnostics only: it NEVER feeds the interpolator/refine path, so a wrong or empty hint cannot
 * affect what the cluster shows.
 */
object NavAccessHint {

    // VI + EN directional / maneuver keywords. Deliberately excludes generic words like "đường"/"road"
    // (they match plain street names) so a hint is only reported when a real turn cue is present.
    // (?iu) = case-insensitive WITH Unicode case-folding, so an uppercase Vietnamese "Đi"/"Rẽ" folds to the
    // lowercase keyword (plain (?i) is ASCII-only and would miss "Đ" → "đ").
    private val MANEUVER = Regex(
        "(?iu)(\\bturn\\b|\\bleft\\b|\\bright\\b|\\bstraight\\b|u-?turn|roundabout|\\bexit\\b|\\bmerge\\b|" +
            "\\bkeep\\b|\\bramp\\b|\\bfork\\b|\\bhead\\b|\\bonto\\b|\\bnorth\\b|\\bsouth\\b|\\beast\\b|\\bwest\\b|" +
            "rẽ|quay đầu|quay xe|đi thẳng|vòng xuyến|vòng xoay|nhập làn|ra khỏi|lối ra|chếch|tiếp tục)",
    )

    /**
     * Pick the first string that looks like a maneuver instruction, preferring content descriptions (where
     * GMaps puts the arrow hint) over on-screen text. Returns "" when nothing matches — logged as blank.
     */
    fun maneuverHint(contentDescriptions: List<String>, texts: List<String>): String {
        contentDescriptions.firstOrNull { looksLikeManeuver(it) }?.let { return it.trim() }
        texts.firstOrNull { looksLikeManeuver(it) }?.let { return it.trim() }
        return ""
    }

    /** True when [text] contains a directional/maneuver keyword. */
    fun looksLikeManeuver(text: String): Boolean =
        text.isNotBlank() && MANEUVER.containsMatchIn(text)
}
