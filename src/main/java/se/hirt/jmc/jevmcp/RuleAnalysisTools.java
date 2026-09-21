/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.rules.IResult;
import org.openjdk.jmc.flightrecorder.rules.IRule;
import org.openjdk.jmc.flightrecorder.rules.ResultToolkit;
import org.openjdk.jmc.flightrecorder.rules.Severity;
import org.openjdk.jmc.flightrecorder.rules.TypedResult;
import se.hirt.jmc.jevmcp.RecordingService.Recording;

/**
 * Runs JMC's built-in automated analysis rules against a recording. Explicitly
 * {@code @ApplicationScoped} (rather than relying on @Tool as a bean-defining annotation) so that
 * {@link JudgmentTools} can inject it and reuse {@link #evaluate} for its Jev candidates.
 */
@ApplicationScoped
public class RuleAnalysisTools {

	static final int RULE_TIMEOUT_SECONDS = 30;

	@Inject
	RecordingService recordings;

	@Tool(description = "Runs JMC's automated analysis rules against the recording and returns the findings: "
			+ "common problems such as GC pressure, lock contention, I/O bottlenecks, and JVM misconfiguration. "
			+ "Returns rule name, severity, score, summary, explanation, and suggested solution. This is the "
			+ "cheapest way to find where to look, and is also what classifyBiggestIssue judges between. "
			+ "Results are computed on first call and cached; the first call on a large recording can take a "
			+ "while.")
	String getRuleResults(
		@ToolArg(description = "Minimum severity to include: IGNORE, NA, OK, INFO, or WARNING (default INFO)", required = false)
		String minSeverity,
		@ToolArg(description = "Include the full explanation and solution text (default true)", required = false)
		Boolean verbose,
		@ToolArg(description = "The recordingId from loadRecording. Leave empty when only one recording is loaded.", required = false)
		String recordingId) {
		try {
			Recording recording = recordings.get(recordingId);
			Severity min = parseSeverity(minSeverity);
			boolean detailed = verbose == null || verbose;

			List<TriggeredRule> triggered = evaluate(recording, min);

			StringBuilder sb = new StringBuilder();
			sb.append("Automated analysis results (severity >= ").append(min.getLocalizedName()).append("):\n\n");
			for (TriggeredRule rule : triggered) {
				sb.append("Rule: ").append(rule.name).append(" [").append(rule.id).append("]\n");
				sb.append("  Severity: ").append(rule.severity.getLocalizedName()).append("\n");
				if (rule.score != null) {
					sb.append("  Score: ").append(rule.score).append("\n");
				}
				appendPopulated(sb, "Summary", rule.summary);
				if (detailed) {
					appendPopulated(sb, "Explanation", rule.explanation);
					appendPopulated(sb, "Solution", rule.solution);
				}
				sb.append("\n");
			}
			if (triggered.isEmpty()) {
				sb.append("No results at or above severity ").append(min.getLocalizedName()).append(".\n");
			}
			return sb.toString();
		} catch (Exception e) {
			return "Error: " + JfrToolkit.describeError(e);
		}
	}

	/**
	 * A rule result flattened to the plain fields {@link JudgmentTools} needs to build a Jev
	 * question, so that class does not have to know about {@link IResult}/{@link Future} at all.
	 */
	static final class TriggeredRule {
		final String id;
		final String name;
		final Severity severity;
		final Double score;
		final String summary;
		final String explanation;
		final String solution;

		TriggeredRule(String id, String name, Severity severity, Double score, String summary, String explanation,
				String solution) {
			this.id = id;
			this.name = name;
			this.severity = severity;
			this.score = score;
			this.summary = summary;
			this.explanation = explanation;
			this.solution = solution;
		}
	}

	/**
	 * Evaluates the rule engine and flattens every result at or above {@code min} severity. Rules
	 * that fail to evaluate or time out are silently skipped, same as the reference JMC MCP server.
	 */
	List<TriggeredRule> evaluate(Recording recording, Severity min) throws InterruptedException {
		Map<IRule, Future<IResult>> resultFutures = recordings.getRuleResults(recording);
		List<Map.Entry<IRule, Future<IResult>>> entries = new ArrayList<>(resultFutures.entrySet());
		entries.sort(Comparator.comparing(e -> e.getKey().getId()));

		List<TriggeredRule> triggered = new ArrayList<>();
		for (Map.Entry<IRule, Future<IResult>> entry : entries) {
			IResult result;
			try {
				result = entry.getValue().get(RULE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (TimeoutException | ExecutionException e) {
				continue;
			}
			// Compare by score, not by enum order: IGNORE is declared last in Severity, so
			// Enum.compareTo would rank it above WARNING and leak ignored results through.
			if (result == null || result.getSeverity().getLimit() < min.getLimit()) {
				continue;
			}
			IQuantity scoreQuantity = result.getResult(TypedResult.SCORE);
			Double score = scoreQuantity != null ? scoreQuantity.doubleValueIn(scoreQuantity.getUnit()) : null;
			triggered.add(new TriggeredRule(result.getRule().getId(), result.getRule().getName(), result.getSeverity(),
					score, populate(result, result.getSummary()), populate(result, result.getExplanation()),
					populate(result, result.getSolution())));
		}
		return triggered;
	}

	private static String populate(IResult result, String message) {
		if (message == null || message.isEmpty()) {
			return null;
		}
		return ResultToolkit.populateMessage(result, message, false);
	}

	private void appendPopulated(StringBuilder sb, String label, String message) {
		if (message == null || message.isEmpty()) {
			return;
		}
		sb.append("  ").append(label).append(": ").append(message).append("\n");
	}

	static Severity parseSeverity(String value) {
		if (value == null || value.isBlank()) {
			return Severity.INFO;
		}
		try {
			return Severity.valueOf(value.trim().toUpperCase(Locale.ENGLISH));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(
					"Unknown severity: " + value + ". Use IGNORE, NA, OK, INFO, or WARNING.");
		}
	}
}
