package com.aura.tvprobe;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/**
 * Command-line JUnit 4 runner for Aura TV Unit and Integration Test Suites.
 */
public final class AuraTestSuiteRunner {

    private static final Class<?>[] TEST_CLASSES = {
        // Unit Tests
        CandidateValidatorTest.class,
        UserTasteProfileTest.class,
        HybridScorerTest.class,
        SlateAllocatorTest.class,
        DynamicRailClustererTest.class,
        ContinueWatchingSelectorTest.class,
        AppCatalogProviderTest.class,
        RailNavigationMathTest.class,
        // Integration Tests
        RecommendationPipelineIntegrationTest.class,
        LiveJustWatchGraphIntegrationTest.class
    };

    public static void main(String[] args) {
        System.out.println("==========================================================");
        System.out.println("  AURA TV · PRODUCTION UNIT & INTEGRATION TEST SUITE");
        System.out.println("==========================================================");
        int totalRun = 0;
        int totalFailed = 0;

        for (Class<?> testClass : TEST_CLASSES) {
            Result res = JUnitCore.runClasses(testClass);
            int run = res.getRunCount();
            int fail = res.getFailureCount();
            totalRun += run;
            totalFailed += fail;
            String status = (fail == 0) ? "PASS" : "FAIL";
            System.out.printf("  [%s] %-42s (%d tests, %d ms)%n",
                status, testClass.getSimpleName(), run, res.getRunTime());
            for (Failure f : res.getFailures()) {
                System.out.println("         -> " + f.getTestHeader() + ": " + f.getMessage());
            }
        }

        System.out.println("----------------------------------------------------------");
        System.out.printf("  TOTAL: %d tests executed, %d passed, %d failed%n",
            totalRun, totalRun - totalFailed, totalFailed);
        System.out.println("==========================================================");
        System.exit(totalFailed > 0 ? 1 : 0);
    }
}
