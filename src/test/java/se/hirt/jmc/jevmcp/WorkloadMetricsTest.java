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

class WorkloadMetricsTest {

	@Test
	void emptyRecordingYieldsZeroedMetricsAndNoCpuField() {
		WorkloadMetrics metrics = WorkloadMetrics.compute(ItemCollectionToolkit.EMPTY, null, null);

		assertEquals(0, metrics.durationSeconds);
		assertEquals(0, metrics.gcCount);
		assertEquals(0, metrics.gcPauseOverheadPct);
		assertEquals(0, metrics.maxPauseMs);
		assertEquals(0, metrics.allocationRateMbPerSec);

		Map<String, Object> state = metrics.toStateMap();
		assertEquals(0.0, state.get("durationSeconds"));
		assertEquals(0L, state.get("gcCount"));
		assertFalse(state.containsKey("avgCpuLoadPct"));
	}

	@Test
	void realRecordingYieldsSaneMetrics() throws Exception {
		RecordingService service = new RecordingService();
		Recording recording = service.load(TestRecordings.wldf().getAbsolutePath());

		WorkloadMetrics metrics = WorkloadMetrics.compute(recording.getItems(), recording.getStart(),
				recording.getEnd());

		assertTrue(metrics.durationSeconds > 0);
		assertTrue(metrics.gcCount >= 0);
		assertTrue(metrics.gcPauseOverheadPct >= 0);
		assertTrue(metrics.maxPauseMs >= 0);
		assertTrue(metrics.allocationRateMbPerSec >= 0);

		Map<String, Object> state = metrics.toStateMap();
		assertTrue(((Number) state.get("durationSeconds")).doubleValue() > 0);
	}
}
