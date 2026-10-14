package com.github.kr328.clash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelRestoreTest {
    @Test fun returningToMainReopensThePanelLeftInBackground() {
        assertEquals(
            AppPanel.Proxy,
            PanelRestore.panelToReopen(
                visible = AppPanel.Main,
                remembered = AppPanel.Proxy,
                userNavigatedBack = false,
            ),
        )
    }

    @Test fun otherPanelsReopenTheSameWay() {
        assertEquals(
            AppPanel.Profiles,
            PanelRestore.panelToReopen(
                visible = AppPanel.Main,
                remembered = AppPanel.Profiles,
                userNavigatedBack = false,
            ),
        )
    }

    @Test fun pressingBackKeepsTheMainScreen() {
        assertNull(
            PanelRestore.panelToReopen(
                visible = AppPanel.Main,
                remembered = AppPanel.Proxy,
                userNavigatedBack = true,
            ),
        )
    }

    @Test fun aVisiblePanelIsNotReplaced() {
        assertNull(
            PanelRestore.panelToReopen(
                visible = AppPanel.Proxy,
                remembered = AppPanel.Profiles,
                userNavigatedBack = false,
            ),
        )
    }

    @Test fun firstLaunchStaysOnMain() {
        assertNull(
            PanelRestore.panelToReopen(
                visible = AppPanel.Main,
                remembered = null,
                userNavigatedBack = false,
            ),
        )
        assertNull(
            PanelRestore.panelToReopen(
                visible = AppPanel.Main,
                remembered = AppPanel.Main,
                userNavigatedBack = false,
            ),
        )
    }

    @Test fun userBackStaysFreshOnlyForTheReturnTransition() {
        assertFalse(PanelRestore.userBackIsFresh(backAtMs = 0L, nowMs = 5_000L))
        assertTrue(PanelRestore.userBackIsFresh(backAtMs = 1_000L, nowMs = 1_500L))
        assertFalse(PanelRestore.userBackIsFresh(backAtMs = 1_000L, nowMs = 4_000L))
        assertFalse(PanelRestore.userBackIsFresh(backAtMs = 5_000L, nowMs = 1_000L))
    }
}
