package app.serene.browser

/**
 * Which background tabs give their page up to save memory (Chrome's tab discarding). Every tab's
 * page lives in the one renderer process WebView has for the app, so each page left open makes it
 * bigger, and when Android kills it for memory, every tab loses its page at once (and the model
 * holds over 2 GB beside it). A discarded tab keeps its address, title, card picture and
 * back/forward list, and loads its page again when it is shown (Tab.discard).
 *
 * Pure rules, so they can be tested: [pick] gets the tabs that could go (not the one on screen,
 * nothing playing or typed in, see TabManager.discardIdle) and says which go.
 */
object TabDiscard {
    enum class Pressure {
        /** Nothing in particular: idle tabs go, and the oldest beyond [KEEP_LIVE]. */
        NONE,
        /** The app is out of sight and in Android's list of apps to kill first (TRIM_MEMORY_BACKGROUND). */
        BACKGROUND,
        /** The phone is low on memory while Helmi is in use: every background tab goes. */
        LOW,
    }

    /** A tab that may go, by when it was last on screen (SystemClock.elapsedRealtime). */
    data class Candidate(val id: Long, val lastShown: Long)

    /** Background tabs kept with their page, the most recently shown first. */
    const val KEEP_LIVE = 4
    /** A background tab not shown for this long goes. */
    const val IDLE_MS = 30 * 60_000L
    /** Out of sight, only the tab shown last before the one on screen stays, and only if it was shown lately. */
    const val BACKGROUND_KEEP = 1
    const val BACKGROUND_IDLE_MS = 10 * 60_000L
    /** A page opened by another page (a sign-in popup) and its opener keep their pages this long. */
    const val OPENER_MS = 15 * 60_000L

    /** The ids of [candidates] to discard now, at [now]. */
    fun pick(candidates: List<Candidate>, now: Long, pressure: Pressure): List<Long> {
        val (keep, idle) = when (pressure) {
            Pressure.NONE -> KEEP_LIVE to IDLE_MS
            Pressure.BACKGROUND -> BACKGROUND_KEEP to BACKGROUND_IDLE_MS
            Pressure.LOW -> 0 to 0L
        }
        val recent = candidates.sortedByDescending { it.lastShown }
        return recent.filterIndexed { i, c -> i >= keep || now - c.lastShown >= idle }.map { it.id }
    }
}
