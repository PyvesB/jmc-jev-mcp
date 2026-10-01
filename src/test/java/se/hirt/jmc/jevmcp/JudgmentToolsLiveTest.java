/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Exercises the real Jev API over HTTPS. Requires a live JEV_KEY - skipped otherwise, since it
 * costs real API usage and would otherwise fail every build that doesn't have a key exported.
 */
@QuarkusTest
@EnabledIfEnvironmentVariable(named = "JEV_KEY", matches = ".+")
class JudgmentToolsLiveTest {

	@Inject
	RecordingService recordings;

	@Inject
	JudgmentTools judgmentTools;

	@Test
	void classifyBiggestIssueCallsJevWhenKeyIsPresent() throws Exception {
		recordings.load(TestRecordings.wldf().getAbsolutePath());
		String result = judgmentTools.classifyBiggestIssue(null, "");
		assertFalse(result.startsWith("Error:"), result);
	}

	@Test
	void classifyWorkloadProfileCallsJevWhenKeyIsPresent() throws Exception {
		recordings.load(TestRecordings.wldf().getAbsolutePath());
		String result = judgmentTools.classifyWorkloadProfile("", null);
		assertFalse(result.startsWith("Error:"), result);
		for (String label : new String[] {"throughputOriented", "pauseTimeSensitive", "memoryConstrained",
				"allocationHeavy", "cpuBound", "lockContended", "stillWarmingUp"}) {
			assertTrue(result.contains(label), "Missing " + label + " in:\n" + result);
		}
	}
}
