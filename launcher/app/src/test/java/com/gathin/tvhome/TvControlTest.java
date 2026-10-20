package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TvControlTest {

    @Test public void clampKeepsValuesInsideRange() {
        assertEquals(10, TvControl.clampVolume(10, 0, 60));
        assertEquals(0, TvControl.clampVolume(-4, 0, 60));
        assertEquals(60, TvControl.clampVolume(99, 0, 60));
        assertEquals(0, TvControl.clampVolume(5, 0, 0));
    }

    @Test public void clampFallsBackToMinWhenRangeIsBackwards() {
        assertEquals(3, TvControl.clampVolume(50, 3, 1));
    }

    @Test public void stepMovesByTwoAndStopsAtTheBounds() {
        assertEquals(12, TvControl.stepVolume(10, 2, 0, 60));
        assertEquals(8, TvControl.stepVolume(10, -2, 0, 60));
        assertEquals(60, TvControl.stepVolume(60, 2, 0, 60));
        assertEquals(0, TvControl.stepVolume(0, -2, 0, 60));
    }

    @Test public void pictureModeKeyStripsTheMainPrefix() {
        assertEquals("MOVIE", TvControl.pictureModeKey("MAIN_MOVIE"));
        assertEquals("HDR_PERSONAL", TvControl.pictureModeKey("MAIN_HDR_PERSONAL"));
        assertEquals("ISF_NIGHT", TvControl.pictureModeKey("MAIN_ISF_NIGHT"));
    }

    @Test public void pictureModeKeyHandlesUnknownAndEmpty() {
        assertEquals("SOMETHING", TvControl.pictureModeKey("something"));
        assertEquals("", TvControl.pictureModeKey(null));
        assertEquals("", TvControl.pictureModeKey("  "));
    }
}
