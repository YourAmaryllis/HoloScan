package com.youramaryllis.holoscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardLockTest {
    private fun card(id: String) = Card(id, id, "set", "", "1", "", "", "", "")
    private val a = card("a")
    private val b = card("b")
    private val noise = card("noise")

    private fun frame(vararg m: Pair<Card, Float>) = m.map { Match(it.first, it.second) }

    @Test fun locksAfterTwoFramesAndHoldsThroughDips() {
        val lock = CardLock(autoAddMs = 0)
        lock.update(frame(a to 0.55f, b to 0.5f), 0)
        assertNull(lock.state)
        lock.update(frame(a to 0.52f, b to 0.5f), 100)
        assertEquals("a", lock.state?.card?.id)
        // Dips below 50% and a reprint briefly on top: still a.
        lock.update(frame(a to 0.45f, b to 0.44f), 200)
        lock.update(frame(b to 0.51f, a to 0.50f), 300)
        lock.update(frame(a to 0.49f), 400)
        assertEquals("a", lock.state?.card?.id)
        assertEquals(0.52f, lock.state!!.best, 0f)   // the score it locked at; dips don't lower it
    }

    @Test fun releasesAfterTwoFramesBelow40() {
        val lock = CardLock(autoAddMs = 0)
        lock.update(frame(a to 0.7f), 0); lock.update(frame(a to 0.7f), 1)
        lock.update(frame(noise to 0.35f), 2)
        assertEquals("a", lock.state?.card?.id)   // one blurry frame isn't enough
        lock.update(frame(noise to 0.30f), 3)
        assertNull(lock.state)
    }

    @Test fun switchesWhenAnotherCardBeatsTheBestTwice() {
        val lock = CardLock(autoAddMs = 0)
        lock.update(frame(a to 0.62f), 0); lock.update(frame(a to 0.62f), 1)
        lock.update(frame(b to 0.70f, a to 0.4f), 2)
        assertEquals("a", lock.state?.card?.id)
        lock.update(frame(b to 0.72f, a to 0.4f), 3)
        assertEquals("b", lock.state?.card?.id)
    }

    @Test fun autoAddsOncePerLockOnlyWhenConfident() {
        val lock = CardLock(autoAddMs = 2000)
        lock.update(frame(a to 0.55f), 0); lock.update(frame(a to 0.55f), 0)
        assertFalse(lock.update(frame(a to 0.58f), 5000))   // low confidence: never auto-added
        lock.release()

        lock.update(frame(a to 0.7f), 0); lock.update(frame(a to 0.7f), 0)
        assertFalse(lock.update(frame(a to 0.48f), 1500))
        assertTrue(lock.update(frame(a to 0.46f), 2000))
        assertFalse(lock.update(frame(a to 0.7f), 4000))
        assertTrue(lock.state!!.added)

        // Card removed, then put back (or the next copy): a new cycle.
        lock.update(frame(noise to 0.3f), 4100); lock.update(frame(noise to 0.3f), 4200)
        lock.update(frame(a to 0.7f), 5000); lock.update(frame(a to 0.7f), 5100)
        assertTrue(lock.update(frame(a to 0.7f), 7100))
    }

    @Test fun manualAddSuppressesAutoAdd() {
        val lock = CardLock(autoAddMs = 1000)
        lock.update(frame(a to 0.7f), 0); lock.update(frame(a to 0.7f), 0)
        lock.markAdded()
        assertFalse(lock.update(frame(a to 0.7f), 5000))
        assertNull(lock.remaining(5000))
    }

    private val c = card("c")
    private val d = card("d")

    @Test fun candidateOrderStaysPutAndNewOnesAppend() {
        val lock = CardLock(autoAddMs = 0)
        lock.update(frame(a to 0.7f, b to 0.6f, c to 0.5f, noise to 0.3f), 0)
        lock.update(frame(a to 0.7f, b to 0.6f, c to 0.5f, noise to 0.3f), 1)
        assertEquals(listOf("b", "c"), lock.state!!.others.map { it.card.id })   // < 40% not listed
        lock.update(frame(a to 0.7f, c to 0.65f, d to 0.45f, b to 0.55f), 2)
        assertEquals(listOf("b", "c", "d"), lock.state!!.others.map { it.card.id })
        assertEquals(0.65f, lock.state!!.others[1].score, 0f)   // best score, not latest
        assertEquals(0.60f, lock.state!!.others[0].score, 0f)
    }

    @Test fun pickReplacesPrimaryAndSticks() {
        val lock = CardLock(autoAddMs = 1000)
        lock.update(frame(a to 0.7f, b to 0.68f), 0); lock.update(frame(a to 0.7f, b to 0.68f), 0)
        lock.choose(b)
        assertEquals("b", lock.state!!.card.id)
        assertTrue(lock.state!!.added)
        assertEquals(listOf("a"), lock.state!!.others.map { it.card.id })
        // a keeps scoring higher, but it's a known candidate and the user picked b.
        repeat(5) { assertFalse(lock.update(frame(a to 0.75f, b to 0.68f), 5000L + it)) }
        assertEquals("b", lock.state!!.card.id)
    }

    @Test fun cancelRestartsTheTimer() {
        val lock = CardLock(autoAddMs = 2000)
        lock.update(frame(a to 0.7f), 0); lock.update(frame(a to 0.7f), 0)
        lock.restartTimer(1500)   // popup was open from 1.0 s to 1.5 s
        assertFalse(lock.update(frame(a to 0.7f), 2500))
        assertEquals(1000L, lock.remaining(2500))
        assertTrue(lock.update(frame(a to 0.7f), 3500))
    }
}
