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
        AndroidManifestContractTest.class,
        LlmRankingBenchmarkTest.class,
        MultiDeviceProfileAndOrchestratorTest.class,
        SystemResourceMonitorTest.class,
        AuraDbAndStorageUnitTest.class,
        AiRecommenderEngineTest.class,
        VoiceConciergeAndCompanionUnitTest.class,
        InAppVoiceAndDeepLinkUnitTest.class,
        ServiceHttpMdnsAndSessionUnitTest.class,
        UiPureLogicAndGraphParserTest.class,
        DeepPipelineAndSessionUnitTest.class,
        PhantomSearchAndSessionBugfixTest.class,
        CoreEngineAndServiceCoverageTest.class,
        // Integration Tests
        RecommendationPipelineIntegrationTest.class,
        VoiceAndContinueWatchingIntegrationTest.class,
        LiveJustWatchGraphIntegrationTest.class,
        CatchMeUpCompanionIntegrationTest.class,
        TasteIntelligenceIntegrationTest.class,
        ReserveSyncAndTasteLifecycleIntegrationTest.class,
        AuraAgenticToolLoopIntegrationTest.class
    };

    public static void main(String[] args) {
        printBanner();
        int[] counts = runAllSuites();
        printSummaryAndExit(counts[0], counts[1]);
    }

    private static void printBanner() {
        System.out.println("==========================================================");
        System.out.println("  AURA TV · PRODUCTION UNIT & INTEGRATION TEST SUITE");
        System.out.println("==========================================================");
    }

    private static int[] runAllSuites() {
        int totalRun = 0, totalFailed = 0;
        for (Class<?> testClass : TEST_CLASSES) {
            Result res = JUnitCore.runClasses(testClass);
            totalRun += res.getRunCount();
            totalFailed += res.getFailureCount();
            reportClassResult(testClass, res);
        }
        return new int[]{totalRun, totalFailed};
    }

    private static void reportClassResult(Class<?> testClass, Result res) {
        String status = (res.getFailureCount() == 0) ? "PASS" : "FAIL";
        System.out.printf("  [%s] %-42s (%d tests, %d ms)%n",
            status, testClass.getSimpleName(), res.getRunCount(), res.getRunTime());
        for (Failure f : res.getFailures()) {
            System.out.println("         -> " + f.getTestHeader() + ": " + f.getMessage());
        }
    }

    private static void printSummaryAndExit(int totalRun, int totalFailed) {
        System.out.println("----------------------------------------------------------");
        System.out.printf("  TOTAL: %d tests executed, %d passed, %d failed%n",
            totalRun, totalRun - totalFailed, totalFailed);
        System.out.println("==========================================================");
        System.exit(totalFailed > 0 ? 1 : 0);
    }
}
