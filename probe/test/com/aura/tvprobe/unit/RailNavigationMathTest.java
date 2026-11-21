package com.aura.tvprobe;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link RailBuilder#computeTargetScrollX}: verifies viewport-aware
 * horizontal rail scrolling for left-to-right, right-to-left, and edge clamping.
 */
public class RailNavigationMathTest {

    @Test
    public void testDoesNotScrollWhenFocusedCardIsAlreadyInsideViewport() {
        // 7-card viewport (width=1840), card #1 (left=265, right=510) is fully visible at scrollX=0
        int targetX = RailBuilder.computeTargetScrollX(1, 8, 265, 510, 0, 1840, 2100, 36);
        assertEquals("Card already inside viewport must not shift scrollX and clip card #0", 0, targetX);
    }

    @Test
    public void testScrollsRightWhenCardExceedsRightViewportEdge() {
        // Card #7 (left=1855, right=2100) exceeds viewportW=1840
        int targetX = RailBuilder.computeTargetScrollX(7, 8, 1855, 2100, 0, 1840, 2100, 36);
        assertEquals("Last card must clamp cleanly to maxScroll (2100 - 1840 = 260)", 260, targetX);
    }

    @Test
    public void testSwipingRightToLeftScrollsSmoothlyAndClampsToZeroAtFirstCard() {
        // Starting scrolled right at curTargetX=260:
        // Moving right-to-left onto card #2 (left=530, right=775) stays at 260 since 530 - 36 >= 260
        int stayX = RailBuilder.computeTargetScrollX(2, 8, 530, 775, 260, 1840, 2100, 36);
        assertEquals(260, stayX);

        // Moving right-to-left onto card #0 (left=0, right=245) resets cleanly to 0
        int resetX = RailBuilder.computeTargetScrollX(0, 8, 0, 245, 260, 1840, 2100, 36);
        assertEquals(0, resetX);
    }

    @Test
    public void testClampsToZeroWhenRowFitsInsideViewportOrBeforeLayout() {
        // 7-card rail where rowW (1835) <= viewportW (1840): maxScroll is 0 even at last card
        int edgeX = RailBuilder.computeTargetScrollX(6, 7, 1590, 1835, 0, 1840, 1835, 36);
        assertEquals("Non-scrollable 7-card rail must clamp to 0 at right edge", 0, edgeX);

        // Pre-layout pass where rowW == 0 must also clamp to 0
        int preLayoutX = RailBuilder.computeTargetScrollX(4, 8, 0, 0, 120, 1840, 0, 36);
        assertEquals(0, preLayoutX);
    }

    @Test
    public void testFullRightToLeftSwipeTraversalNeverOvershootsBounds() {
        int viewportW = 1840, rowW = 2100, cardW = 245, gap = 20, margin = 36;
        int curScrollX = 0;
        for (int idx = 0; idx < 8; idx++) {
            int left = idx * (cardW + gap);
            curScrollX = RailBuilder.computeTargetScrollX(
                idx, 8, left, left + cardW, curScrollX, viewportW, rowW, margin);
            assertTrue(curScrollX >= 0 && curScrollX <= (rowW - viewportW));
        }
        assertEquals(260, curScrollX);
        for (int idx = 7; idx >= 0; idx--) {
            int left = idx * (cardW + gap);
            curScrollX = RailBuilder.computeTargetScrollX(
                idx, 8, left, left + cardW, curScrollX, viewportW, rowW, margin);
            assertTrue(curScrollX >= 0 && curScrollX <= (rowW - viewportW));
        }
        assertEquals(0, curScrollX);
    }

    @Test
    public void testScrollLayoutRetryStopsWhenHiddenOrExhausted() {
        assertTrue(RailBuilder.shouldRetryScrollLayout(2, android.view.View.VISIBLE, true));
        assertFalse(RailBuilder.shouldRetryScrollLayout(0, android.view.View.VISIBLE, true));
        assertFalse(RailBuilder.shouldRetryScrollLayout(2, android.view.View.GONE, true));
        assertFalse(RailBuilder.shouldRetryScrollLayout(2, android.view.View.VISIBLE, false));
    }
}
