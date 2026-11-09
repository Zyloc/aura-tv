package com.aura.tvprobe;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link CandidateValidator}: Bayesian IMDb scoring, genre Jaccard
 * similarity, modern catalog gates, and strict opt-in Anime/Animation filtering.
 */
public class CandidateValidatorTest {

    @Test
    public void testBayesianImdbShrinksLowVoteOutliersTowardPrior() throws Exception {
        JSONObject lowVotes = new JSONObject()
            .put("imdb_raw", 9.5).put("imdb_votes", 100).put("tmdb_popularity", 5.0);
        JSONObject highVotes = new JSONObject()
            .put("imdb_raw", 8.8).put("imdb_votes", 50000).put("tmdb_popularity", 40.0);
        double lowScore = CandidateValidator.computeBayesianImdb(lowVotes);
        double highScore = CandidateValidator.computeBayesianImdb(highVotes);
        assertTrue("High-vote 8.8 must beat low-vote 9.5 outlier", highScore > lowScore);
        assertTrue("Low-vote 9.5 should shrink near 7.0 prior", lowScore < 7.5);
    }

    @Test
    public void testGenreJaccardComputesExactIntersectionOverUnion() {
        double identical = CandidateValidator.computeGenreJaccard("scf,trl", "trl,scf");
        double partial = CandidateValidator.computeGenreJaccard("scf,trl", "scf,drm");
        double disjoint = CandidateValidator.computeGenreJaccard("scf,trl", "spt,doc");
        assertEquals(1.0, identical, 0.001);
        assertEquals(1.0 / 3.0, partial, 0.001);
        assertEquals(0.0, disjoint, 0.001);
    }

    @Test
    public void testUnwantedAnimationBlocksAnimeUnlessOptedIn() {
        assertTrue("Family animation is always blocked",
            CandidateValidator.isUnwantedAnimation("ani,fml,cmy", true));
        assertTrue("Anime is blocked when animeEnabled=false",
            CandidateValidator.isUnwantedAnimation("ani,act,scf", false));
        assertFalse("Anime is allowed when animeEnabled=true and not family",
            CandidateValidator.isUnwantedAnimation("ani,act,scf", true));
        assertFalse("Live-action sci-fi is never blocked",
            CandidateValidator.isUnwantedAnimation("scf,trl,drm", false));
    }

    @Test
    public void testValidCatalogCandidateEnforcesQualityAndOfferGates() throws Exception {
        int year = CandidateValidator.getCurrentYear() - 1;
        JSONObject valid = new JSONObject()
            .put("title", "Severance").put("year", year)
            .put("has_flatrate_offer", true).put("poster_url", "https://img/p.jpg")
            .put("imdb_raw", 8.7).put("imdb_votes", 120000).put("tmdb_popularity", 55.0)
            .put("genre_codes", "scf,trl,drm");
        assertTrue(CandidateValidator.isValidCatalogCandidate(valid, year - 4, 6.5));

        JSONObject noOffer = new JSONObject(valid.toString()).put("has_flatrate_offer", false);
        assertFalse(CandidateValidator.isValidCatalogCandidate(noOffer, year - 4, 6.5));
    }

    @Test
    public void testSportsAndDocuseriesHaveDedicatedVoteThreshold() throws Exception {
        int year = CandidateValidator.getCurrentYear() - 1;
        JSONObject sportsDoc = new JSONObject()
            .put("title", "Formula 1: Drive to Survive").put("year", year)
            .put("has_flatrate_offer", true).put("poster_url", "https://img/f1.jpg")
            .put("imdb_raw", 8.5).put("imdb_votes", 700).put("tmdb_popularity", 12.0)
            .put("genre_codes", "spt,doc");
        assertTrue(CandidateValidator.isValidCatalogCandidate(sportsDoc, year - 4, 6.5));
    }
}
