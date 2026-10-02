/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjdk.jmc.flightrecorder.rules.RuleRegistry;
import org.openjdk.jmc.flightrecorder.rules.Severity;

import se.hirt.jmc.jevmcp.RecordingService.Recording;
import se.hirt.jmc.jevmcp.RuleAnalysisTools.TriggeredRule;

/**
 * {@code classifyBiggestIssue}/{@code classifyWorkloadProfile}/{@code assessRuleResults} must fail
 * fast, without a network call, when JEV_KEY is unset - that's what these plain-unit tests check,
 * wiring the tool class directly rather than through CDI. The tests that actually call Jev live in
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

	@Test
	void assessRuleResultsFailsFastWithoutJevKey() {
		withoutJevKey(() -> {
			String result = judgmentTools.assessRuleResults("", null);
			assertTrue(result.startsWith("Error:"));
			assertTrue(result.contains("JEV_KEY"));
		});
	}

	@Test
	void everyRuleGetsItsOwnQuestion() throws Exception {
		List<TriggeredRule> rules = judgmentTools.ruleAnalysis.evaluate(recording, Severity.IGNORE);
		assertEquals(RuleRegistry.getRules().size(), rules.size(), "every default rule should yield a result");

		Map<String, TriggeredRule> keys = JudgmentTools.questionKeys(rules);
		assertEquals(rules.size(), keys.size());
		for (String key : keys.keySet()) {
			assertTrue(key.matches("rule_[A-Za-z0-9_]+"), key);
		}
		// Rule ids with spaces and dots, e.g. "Fatal Errors" and "Allocations.class", must still be
		// reachable from the state the questions point at.
		Map<String, Object> results = JudgmentTools.ruleResultsState(rules);
		for (TriggeredRule rule : keys.values()) {
			assertTrue(results.containsKey(rule.id), rule.id);
		}
	}

	@Test
	void workloadStateFitsJevsBudget() throws Exception {
		WorkloadMetrics metrics = WorkloadMetrics.compute(recording.getItems(), recording.getStart(),
				recording.getEnd());
		Map<String, Object> state = JudgmentTools.workloadState(recording, metrics, HotPathMetrics.DEFAULT_MAX_NODES,
				Map.of());

		for (String key : List.of("metrics", "warmup", "environment", "timeSeries", "executionHotPath",
				"allocationHotPath", "monitorEnterHotPath", "durationHistograms", "lockContention")) {
			assertTrue(state.containsKey(key), "missing " + key + " in " + state.keySet());
		}
		assertTrue(JudgmentTools.sizeOf(state) <= JudgmentTools.STATE_BUDGET_BYTES);
	}

	@Test
	void oversizedStateIsShrunkToFitJevsBudget() throws Exception {
		WorkloadMetrics metrics = WorkloadMetrics.compute(recording.getItems(), recording.getStart(),
				recording.getEnd());
		Map<String, Object> state = JudgmentTools.workloadState(recording, metrics, HotPathMetrics.HARD_CAP_MAX_NODES,
				Map.of());

		// Three 500-node graphs would be several times over the budget on wldf.jfr.
		assertTrue(((Number) state.get("hotPathMaxNodes")).intValue() < HotPathMetrics.HARD_CAP_MAX_NODES);
		assertTrue(JudgmentTools.sizeOf(state) <= JudgmentTools.STATE_BUDGET_BYTES);
	}

	@Test
	void ruleAssessmentRequestFitsJevsBudget() throws Exception {
		List<TriggeredRule> rules = judgmentTools.ruleAnalysis.evaluate(recording, Severity.IGNORE);
		WorkloadMetrics metrics = WorkloadMetrics.compute(recording.getItems(), recording.getStart(),
				recording.getEnd());
		Map<String, Object> request = JudgmentTools.ruleAssessmentRequest(recording, metrics,
				JudgmentTools.questionKeys(rules), HotPathMetrics.DEFAULT_MAX_NODES);

		@SuppressWarnings("unchecked")
		Map<String, Object> state = (Map<String, Object>) request.get("state");
		assertTrue(state.containsKey("ruleResults"));
		assertTrue(JudgmentTools.sizeOf(state) <= JudgmentTools.STATE_BUDGET_BYTES);
		long findings = rules.stream()
				.filter(rule -> rule.severity == Severity.INFO || rule.severity == Severity.WARNING).count();
		assertTrue(findings > 0);
		assertEquals(rules.size() + findings, ((Map<?, ?>) request.get("questions")).size());
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
