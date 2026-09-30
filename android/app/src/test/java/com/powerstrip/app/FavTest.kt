package com.powerstrip.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the phone-local favorites helpers (pure logic, no Android needed).
 */
class FavTest {

    @Test
    fun parseAcceptsOnlyWellFormedRefs() {
        val favs = FavStore.parse("""["88D039943EA8:2","bad","88D039943EA8:9","AA:1","88D039943EA8:2"]""")
        assertEquals(listOf(FavRef("88D039943EA8", 2)), favs)
    }

    @Test
    fun parseRejectsGarbage() {
        assertTrue(FavStore.parse("").isEmpty())
        assertTrue(FavStore.parse("not json").isEmpty())
        assertTrue(FavStore.parse("[]").isEmpty())
    }

    @Test
    fun serializeRoundTrip() {
        val favs = listOf(FavRef("88D039943EA8", 2), FavRef("AABBCCDDEEFF", 1))
        assertEquals(favs, FavStore.parse(FavStore.serialize(favs)))
    }

    @Test
    fun moveSwapsWithinBounds() {
        val favs = listOf(FavRef("A", 1), FavRef("B", 2), FavRef("C", 3))
        assertEquals(
            listOf(FavRef("B", 2), FavRef("A", 1), FavRef("C", 3)),
            FavStore.move(favs, 1, -1),
        )
        assertEquals(
            listOf(FavRef("A", 1), FavRef("C", 3), FavRef("B", 2)),
            FavStore.move(favs, 1, 1),
        )
        assertEquals(favs, FavStore.move(favs, 0, -1))
        assertEquals(favs, FavStore.move(favs, 2, 1))
        assertEquals(favs, FavStore.move(favs, 9, 1))
    }
}
