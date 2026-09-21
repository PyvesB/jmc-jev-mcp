/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjdk.jmc.flightrecorder.rules.Severity;

import se.hirt.jmc.jevmcp.RecordingService.Recording;
import se.hirt.jmc.jevmcp.RuleAnalysisTools.TriggeredRule;

class RuleAnalysisToolsTest {

	private RecordingService recordingService;
	private RuleAnalysisTools tools;
	private Recording recording;

	@BeforeEach
	void setUp() throws Exception {
		recordingService = new RecordingService();
		tools = new RuleAnalysisTools();
		tools.recordings = recordingService;
		recording = recordingService.load(TestRecordings.wldf().getAbsolutePath());
	}

	@Test
	void evaluateFindsTriggeredRulesWithPopulatedMessages() throws Exception {
		List<TriggeredRule> triggered = tools.evaluate(recording, Severity.INFO);

		assertFalse(triggered.isEmpty());
		for (TriggeredRule rule : triggered) {
			// Messages must be populated, not left as raw "{placeholder}" templates.
			if (rule.summary != null) {
				assertFalse(rule.summary.contains("{"), "Unpopulated summary in " + rule.id + ": " + rule.summary);
			}
		}
	}

	@Test
	void higherMinSeverityNarrowsResults() throws Exception {
		List<TriggeredRule> info = tools.evaluate(recording, Severity.INFO);
		List<TriggeredRule> warning = tools.evaluate(recording, Severity.WARNING);

		assertTrue(warning.size() <= info.size());
		for (TriggeredRule rule : warning) {
			assertTrue(rule.severity.getLimit() >= Severity.WARNING.getLimit());
		}
	}

	@Test
	void getRuleResultsFormatsTriggeredRules() {
		String result = tools.getRuleResults("INFO", true, "");

		assertTrue(result.startsWith("Automated analysis results"));
		assertTrue(result.contains("Rule:"));
		assertTrue(result.contains("Severity:"));
	}

	@Test
	void getRuleResultsReportsUnknownSeverity() {
		String result = tools.getRuleResults("NOT_A_SEVERITY", true, "");
		assertTrue(result.startsWith("Error:"));
	}
}
