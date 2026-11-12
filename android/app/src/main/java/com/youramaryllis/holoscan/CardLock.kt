package com.youramaryllis.holoscan

/** The card currently held on screen. [score] is from the latest frame, [best] the highest since locking. */
data class Locked(
    val card: Card,
    val score: Float,
    val best: Float,
    val since: Long,            // ms (elapsedRealtime) the auto-add timer counts from
    val added: Boolean,         // added to the collection during this lock (auto, check button or pick)
    val others: List<Match>,    // other candidates in a fixed order: new ones are appended, never reordered
)

/**
 * Holds one recognised card on screen while the camera stays on it, so the
 * result doesn't flicker as the score wobbles around 50%.
 *
 * - Locks when the same card is on top for [FRAMES] frames in a row at >= [LOCK_AT].
 * - Stays locked while the score dips, until the top score is below [RELEASE_BELOW]
 *   for [FRAMES] frames (the card left the frame), or a different card beats the
 *   locked card's best score for [FRAMES] frames. Once the card has been added, only a
 *   card that isn't already a candidate can take over (a reprint can't undo the pick).
 * - Candidates (>= [RELEASE_BELOW]) keep the order they first appeared in; each shows its best score.
 * - Once the same card has been locked for [autoAddMs] (0 = off) and its best
 *   score reached [CONFIDENT], [update] returns true, once per lock.
 *
 * Pure logic, no Android types: driven by the analysis callback, unit tested.
 */
class CardLock(var autoAddMs: Long) {
    var state: Locked? = null
        private set

    private var streakId: String? = null
    private var streak = 0
    private var lowFrames = 0

    /** Feed one frame's matches (best first). Returns true when the locked card should be auto-added now. */
    fun update(matches: List<Match>, now: Long): Boolean {
        val top = matches.firstOrNull() ?: return false
        val locked = state
        if (locked == null) {
            if (count(top.card.id.takeIf { top.score >= LOCK_AT }) >= FRAMES) {
                lock(top, merge(emptyList(), matches, top.card.id), now)
            }
            return false
        }

        if (top.score < RELEASE_BELOW) {
            if (++lowFrames >= FRAMES) release()
            return false
        }
        lowFrames = 0

        val known = locked.others.any { it.card.id == top.card.id }
        val challenger = top.card.id.takeIf {
            it != locked.card.id && top.score > locked.best && !(locked.added && known)
        }
        if (count(challenger) >= FRAMES) {
            val previous = listOf(Match(locked.card, locked.best)) + locked.others
            lock(top, merge(previous, matches, top.card.id), now)
            return false
        }

        val mine = matches.firstOrNull { it.card.id == locked.card.id }
        state = locked.copy(
            score = mine?.score ?: locked.score,
            best = maxOf(locked.best, mine?.score ?: 0f),
            others = merge(locked.others, matches, locked.card.id),
        )
        val s = state!!
        if (!s.added && autoAddMs > 0 && s.best >= CONFIDENT && now - s.since >= autoAddMs) {
            state = s.copy(added = true)
            return true
        }
        return false
    }

    /** The user added the locked card (check button): don't auto-add it again during this lock. */
    fun markAdded() {
        state = state?.copy(added = true)
    }

    /** The user picked [card] from the candidates: it becomes the locked card, already added. */
    fun choose(card: Card) {
        val s = state ?: return
        if (card.id == s.card.id) { markAdded(); return }
        val all = listOf(Match(s.card, s.best)) + s.others
        val picked = all.firstOrNull { it.card.id == card.id }?.score ?: 0f
        state = s.copy(card = card, score = picked, added = true, others = all.filter { it.card.id != card.id })
    }

    /** The pick popup was cancelled: back to auto-detect, with the auto-add timer starting over. */
    fun restartTimer(now: Long) {
        state = state?.copy(since = now)
    }

    /** ms left before auto-add, or null when it won't happen during this lock. */
    fun remaining(now: Long): Long? {
        val s = state ?: return null
        if (s.added || autoAddMs <= 0 || s.best < CONFIDENT) return null
        return (autoAddMs - (now - s.since)).coerceAtLeast(0)
    }

    fun release() {
        state = null
        streakId = null
        streak = 0
        lowFrames = 0
    }

    private fun lock(top: Match, others: List<Match>, now: Long) {
        state = Locked(top.card, top.score, top.score, now, added = false, others = others)
        streakId = null
        streak = 0
        lowFrames = 0
    }

    /** [current] in its order with best scores updated, then this frame's new candidates appended. */
    private fun merge(current: List<Match>, frame: List<Match>, exclude: String): List<Match> {
        val scores = frame.associate { it.card.id to it.score }
        val kept = current.filter { it.card.id != exclude }
            .map { m -> scores[m.card.id]?.let { if (it > m.score) Match(m.card, it) else m } ?: m }
        val ids = kept.mapTo(HashSet()) { it.card.id }
        val added = frame.filter { it.card.id != exclude && it.card.id !in ids && it.score >= RELEASE_BELOW }
        return kept + added
    }

    /** Consecutive frames with the same non-null [id]; null breaks the streak. */
    private fun count(id: String?): Int {
        streak = if (id != null && id == streakId) streak + 1 else if (id != null) 1 else 0
        streakId = id
        return streak
    }

    companion object {
        // Cosine-similarity bands, from real use on a Galaxy Z Fold7: matches at or
        // above 0.60 were always the right card; non-cards score around 0.30.
        const val LOCK_AT = 0.50f        // below this, a new card isn't shown
        const val CONFIDENT = 0.60f      // 0.50-0.60 is shown as low confidence, never auto-added
        const val RELEASE_BELOW = 0.40f  // the card has left the frame
        const val FRAMES = 2
    }
}
