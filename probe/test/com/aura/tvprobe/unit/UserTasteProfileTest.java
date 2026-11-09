package com.aura.tvprobe;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link UserTasteProfile}: multi-genre affinity vectors,
 * opt-in anime detection, and local shovelware protection gates.
 */
public class UserTasteProfileTest {

    @Test
    public void testAnimeDisabledByDefaultWhenNotInPreferredLangs() {
        List<String> anchors = Arrays.asList("Severance", "Slow Horses");
        UserTasteProfile defaultProfile = new UserTasteProfile(
            anchors, "IN", "English Hindi", "prestige", 1);
        assertFalse(defaultProfile.isAnimeEnabled());

        UserTasteProfile animeProfile = new UserTasteProfile(
            anchors, "IN", "English Anime Japanese", "prestige", 1);
        assertTrue(animeProfile.isAnimeEnabled());
    }

    @Test
    public void testGenreAffinityRewardsRecordedWatchGenres() {
        UserTasteProfile profile = new UserTasteProfile(
            Arrays.asList("F1", "Silo"), "IN", "English", "prestige", 1);
        profile.recordSeedMetadata("US", "scf,trl", 2.0);
        profile.recordSeedMetadata("GB", "spt,doc", 1.8);

        double scifiScore = profile.computeGenreAffinity("scf,trl");
        double sportsScore = profile.computeGenreAffinity("spt,doc");
        double romanceScore = profile.computeGenreAffinity("rma,cmy");

        assertTrue("Sci-Fi/Thriller must have strong positive affinity", scifiScore > 1.0);
        assertTrue("Sports/Docuseries must have strong positive affinity", sportsScore > 0.9);
        assertEquals("Unwatched Romance/Comedy has zero affinity", 0.0, romanceScore, 0.001);
    }

    @Test
    public void testLocalShovelwareGateRequiresHighVoteConsensus() {
        UserTasteProfile profile = new UserTasteProfile(
            Arrays.asList("Sacred Games"), "IN", "English", "prestige", 1);
        assertFalse("Low-vote regional shovelware must be rejected",
            profile.passesLocalShovelwareGate("IN", 1200));
        assertTrue("Acclaimed regional title with high votes passes",
            profile.passesLocalShovelwareGate("IN", 35000));
    }
}
