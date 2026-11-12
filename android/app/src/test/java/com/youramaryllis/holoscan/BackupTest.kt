package com.youramaryllis.holoscan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class BackupTest {
    @Test fun csvRoundTripsQuotesAndCommas() {
        val rows = listOf(listOf("card_id", "name"), listOf("sv1-1", "Pikachu, \"the\" mouse"), listOf("x", "line\nbreak"))
        assertEquals(rows, Csv.read(Csv.write(rows)))
    }

    @Test fun zipRoundTrip() {
        val card = Card("mcd26-3", "Pikachu, Jr.", "McDonald's 2026", "", "3", "15", "Promo", "", "")
        val out = ByteArrayOutputStream()
        Backup.export(out, mapOf("mcd26-3" to 2, "base1-4" to 1), mapOf(card.id to card),
            mapOf("autoAddSeconds" to "3"), "2026-11-11T20:00Z")
        val back = Backup.import(out.toByteArray().inputStream())
        assertEquals(mapOf("mcd26-3" to 2, "base1-4" to 1), back.collection)
        assertEquals("3", back.settings["autoAddSeconds"])
    }

    @Test fun bareCsvImports() {
        val csv = "﻿Card_ID,Count\r\nsv1-1,3\nsv1-1,1\nsv1-2,\n"
        assertEquals(mapOf("sv1-1" to 4, "sv1-2" to 1), Backup.import(csv.byteInputStream()).collection)
    }

    @Test fun refreshLimit() {
        val m = 60_000L
        assertEquals(0L, RefreshLimit.waitMs(emptyList(), 0))
        assertEquals(3 * m, RefreshLimit.waitMs(listOf(0L), 2 * m))                    // 5 min cooldown
        val five = listOf(0L, 6 * m, 12 * m, 18 * m, 24 * m)
        assertEquals(30 * m, RefreshLimit.waitMs(five, 30 * m))                         // 5 in the hour: until 60 min
        assertEquals(0L, RefreshLimit.waitMs(five, 60 * m))
        assertEquals(0, RefreshLimit.left(five, 30 * m))
    }
}
