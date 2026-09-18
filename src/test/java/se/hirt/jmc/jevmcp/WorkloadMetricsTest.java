/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.item.ItemCollectionToolkit;

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
}
