/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import java.io.PrintStream;

import org.junit.jupiter.api.Test;

/**
 * Prints every Jev classification of {@code wldf.jfr}, for demoing. Not picked up by
 * {@code mvn test}, since the class name does not match surefire's includes; run it with
 * {@code mvn test -Pdemo}. Requires JEV_KEY and costs real API usage.
 */
@QuarkusTest
class JevDemo {

	/**
	 * The STDIO transport swallows System.out, so that nothing but MCP messages reaches stdout.
	 */
	private static final PrintStream OUT = System.err;

	@Inject
	RecordingService recordings;

	@Inject
	JudgmentTools judgmentTools;

	@Test
	void printClassifications() throws Exception {
		recordings.load(TestRecordings.wldf().getAbsolutePath());
		print("classifyBiggestIssue", judgmentTools.classifyBiggestIssue("", null));
		print("classifyWorkloadProfile", judgmentTools.classifyWorkloadProfile("", null));
		print("assessRuleResults", judgmentTools.assessRuleResults("", null));
	}

	private static void print(String tool, String result) {
		OUT.println();
		OUT.println("=== " + tool + " " + "=".repeat(Math.max(0, 95 - tool.length())));
		OUT.println(result);
		assertFalse(result.startsWith("Error:"), result);
	}
}
