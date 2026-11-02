package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NowPlayingTitleTest {
    @Test
    public void dropsRemasterSuffixes() {
        assertEquals("Peace Sells", NowPlayingShelf.cleanTitle("Peace Sells - 2004 Remaster"));
        assertEquals("Here Comes the Sun", NowPlayingShelf.cleanTitle("Here Comes the Sun - Remastered 2009"));
        assertEquals("Song", NowPlayingShelf.cleanTitle("Song (Remastered 2011)"));
        assertEquals("Song", NowPlayingShelf.cleanTitle("Song [2015 Remaster]"));
    }

    @Test
    public void keepsEverythingElse() {
        assertEquals("Blue Monday - Live", NowPlayingShelf.cleanTitle("Blue Monday - Live"));
        assertEquals("Song (feat. X)", NowPlayingShelf.cleanTitle("Song (feat. X)"));
        assertEquals("Remaster", NowPlayingShelf.cleanTitle("Remaster"));
    }
}
