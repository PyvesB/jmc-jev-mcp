/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.hirt.jmc.jevmcp.RecordingService.Recording;

/**
 * {@code classifyBiggestIssue}/{@code classifyWorkloadProfile} must fail fast, without a network
 * call, when JEV_KEY is unset - that's what these plain-unit tests check, wiring the tool class
 * directly rather than through CDI. The tests that actually call Jev live in
 * {@link JudgmentToolsLiveTest}, which needs a real key and a Quarkus-booted REST client.
 */
class JudgmentToolsTest {

	private RecordingService recordingService;
	private JudgmentTools judgmentTools;
	private Recording recording;

	@BeforeEach
	void setUp() throws Exception {
		recordingService = new RecordingService();
		RuleAnalysisTools ruleAnalysisTools = new RuleAnalysisTools();
		ruleAnalysisTools.recordings = recordingService;

		judgmentTools = new JudgmentTools();
		judgmentTools.recordings = recordingService;
		judgmentTools.ruleAnalysis = ruleAnalysisTools;
		// jev is left null: the missing-key checks below must fail before it would ever be used.

		recording = recordingService.load(TestRecordings.wldf().getAbsolutePath());
	}

	@Test
	void classifyBiggestIssueFailsFastWithoutJevKey() {
		withoutJevKey(() -> {
			String result = judgmentTools.classifyBiggestIssue(null, "");
			assertTrue(result.startsWith("Error:"));
			assertTrue(result.contains("JEV_KEY"));
		});
	}

	@Test
	void classifyWorkloadProfileFailsFastWithoutJevKey() {
		withoutJevKey(() -> {
			String result = judgmentTools.classifyWorkloadProfile("", null);
			assertTrue(result.startsWith("Error:"));
			assertTrue(result.contains("JEV_KEY"));
		});
	}

	/**
	 * JUnit can't unset an inherited env var, so when JEV_KEY happens to be set in the process
	 * these fail-fast checks are skipped rather than failed - the live tests cover that case
	 * instead.
	 */
	private static void withoutJevKey(Runnable test) {
		String existing = System.getenv(JevAuthFilter.ENV_VAR);
		Assumptions.assumeTrue(existing == null || existing.isBlank(),
				"JEV_KEY is set in this environment; skipping the fail-fast check.");
		test.run();
	}
}
