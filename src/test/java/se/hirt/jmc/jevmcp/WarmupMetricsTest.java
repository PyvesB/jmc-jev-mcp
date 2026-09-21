/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.item.ItemCollectionToolkit;

import se.hirt.jmc.jevmcp.RecordingService.Recording;

class WarmupMetricsTest {

	@Test
	void emptyRecordingYieldsZeroedMetricsAndNoUptimeField() {
		WarmupMetrics metrics = WarmupMetrics.compute(ItemCollectionToolkit.EMPTY, null, null);

		assertEquals(null, metrics.jvmUptimeAtRecordingStartSeconds);
		assertEquals(0, metrics.classLoadCount);
		assertEquals(0, metrics.classLoadRatePerSecond);
		assertEquals(0, metrics.compilationEventCount);
		assertEquals(0, metrics.threadStartCount);

		Map<String, Object> state = metrics.toStateMap();
		assertFalse(state.containsKey("jvmUptimeAtRecordingStartSeconds"));
		assertEquals(0L, state.get("classLoadCount"));
	}

	@Test
	void realRecordingLooksLikeStartupWarmup() throws Exception {
		RecordingService service = new RecordingService();
		Recording recording = service.load(TestRecordings.wldf().getAbsolutePath());

		WarmupMetrics metrics = WarmupMetrics.compute(recording.getItems(), recording.getStart(),
				recording.getEnd());

		// wldf.jfr is a real recording of a WebLogic server starting up, so it should show heavy
		// class loading and thread startup activity.
		assertTrue(metrics.classLoadCount > 1000, "expected heavy class loading, got " + metrics.classLoadCount);
		assertTrue(metrics.classLoadRatePerSecond > 0);
		assertTrue(metrics.threadStartCount > 0);

		Map<String, Object> state = metrics.toStateMap();
		assertTrue(((Number) state.get("classLoadCount")).longValue() > 1000);
	}
}
