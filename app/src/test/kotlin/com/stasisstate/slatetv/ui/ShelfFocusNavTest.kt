package com.stasisstate.slatetv.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfFocusNavTest {
    private val up = KeyEvent.KEYCODE_DPAD_UP
    private val down = KeyEvent.KEYCODE_DPAD_DOWN

    @Test
    fun cardNotAttachedYetButShelfHasCardsIsPending() {
        assertEquals(ShelfCardFocus.PENDING, resolveCardFocus(focusedNow = false, visible = true, hasCards = true))
    }

    @Test
    fun attachedCardFocusesImmediately() {
        assertEquals(ShelfCardFocus.FOCUSED, resolveCardFocus(focusedNow = true, visible = true, hasCards = true))
    }

    @Test
    fun emptyOrHiddenShelfIsUnavailable() {
        assertEquals(ShelfCardFocus.UNAVAILABLE, resolveCardFocus(false, visible = true, hasCards = false))
        assertEquals(ShelfCardFocus.UNAVAILABLE, resolveCardFocus(false, visible = false, hasCards = true))
    }

    @Test
    fun dockUpWithPendingCardsIsConsumedInsteadOfEnteringGalleryMode() {
        var headerTried = false
        val consumed = shelfEntryConsumed(
            visible = true,
            focusCard = { ShelfCardFocus.PENDING },
            focusHeader = { headerTried = true; true },
        )
        assertTrue(consumed)
        assertFalse("pending card focus must not steal focus to the header", headerTried)
    }

    @Test
    fun dockUpWithNoCardsFallsBackToHeaderThenExits() {
        assertTrue(shelfEntryConsumed(true, { ShelfCardFocus.UNAVAILABLE }, { true }))
        assertFalse(shelfEntryConsumed(true, { ShelfCardFocus.UNAVAILABLE }, { false }))
        assertFalse(shelfEntryConsumed(false, { ShelfCardFocus.FOCUSED }, { true }))
    }

    @Test
    fun downFromHeaderWhileLayoutPendingIsConsumedInsteadOfFallingToDock() {
        assertTrue(shelfVerticalConsumed(down, inHeader = true, { ShelfCardFocus.PENDING }, { false }))
    }

    @Test
    fun downFromHeaderFocusesCards() {
        assertTrue(shelfVerticalConsumed(down, inHeader = true, { ShelfCardFocus.FOCUSED }, { false }))
    }

    @Test
    fun downFromHeaderWithZeroCardsExitsToDock() {
        assertFalse(shelfVerticalConsumed(down, inHeader = true, { ShelfCardFocus.UNAVAILABLE }, { false }))
    }

    @Test
    fun downFromCardsExitsToDock() {
        assertFalse(shelfVerticalConsumed(down, inHeader = false, { ShelfCardFocus.FOCUSED }, { true }))
    }

    @Test
    fun upFromHeaderExitsToGalleryMode() {
        assertFalse(shelfVerticalConsumed(up, inHeader = true, { ShelfCardFocus.FOCUSED }, { true }))
    }

    @Test
    fun upFromCardsMovesToHeaderOrExitsWhenNoHeaderAction() {
        assertTrue(shelfVerticalConsumed(up, inHeader = false, { ShelfCardFocus.FOCUSED }, { true }))
        assertFalse(shelfVerticalConsumed(up, inHeader = false, { ShelfCardFocus.FOCUSED }, { false }))
    }
}
