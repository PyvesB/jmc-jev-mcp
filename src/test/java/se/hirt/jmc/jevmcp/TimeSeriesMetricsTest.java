/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.item.ItemCollectionToolkit;

import se.hirt.jmc.jevmcp.RecordingService.Recording;

class TimeSeriesMetricsTest {

	@Test
	void missingTimeRangeYieldsNoSeries() {
		assertNull(TimeSeriesMetrics.compute(ItemCollectionToolkit.EMPTY, null, null));
	}

	@Test
	void realRecordingYieldsOneValuePerSlice() throws Exception {
		Recording recording = new RecordingService().load(TestRecordings.wldf().getAbsolutePath());
		Map<String, Object> timeSeries = TimeSeriesMetrics.compute(recording.getItems(), recording.getStart(),
				recording.getEnd());

		@SuppressWarnings("unchecked")
		Map<String, List<Double>> series = (Map<String, List<Double>>) timeSeries.get("series");
		for (String key : List.of("machineTotalCpuPct", "jvmTotalCpuPct", "activeThreads", "heapUsedMb",
				"heapUsedAfterGcMb", "allocationMbPerSec", "gcPauseMs", "loadedClassCount")) {
			assertTrue(series.containsKey(key), "missing " + key + " in " + series.keySet());
		}
		for (Map.Entry<String, List<Double>> entry : series.entrySet()) {
			assertEquals(TimeSeriesMetrics.SLICES, entry.getValue().size(), entry.getKey());
		}

		// The live set left after a GC can never exceed the heap used before it.
		double maxUsed = max(series.get("heapUsedMb"));
		double maxAfterGc = max(series.get("heapUsedAfterGcMb"));
		assertTrue(maxAfterGc <= maxUsed, maxAfterGc + " > " + maxUsed);

		// Slice rates times slice length must add back up to the whole-recording allocation total.
		double sliceSeconds = ((Number) timeSeries.get("sliceSeconds")).doubleValue();
		double sliceTotalMb = series.get("allocationMbPerSec").stream().mapToDouble(Double::doubleValue).sum()
				* sliceSeconds;
		double totalMb = WorkloadMetrics.totalAllocatedBytes(recording.getItems()) / (1024.0 * 1024.0);
		assertEquals(totalMb, sliceTotalMb, totalMb * 0.01);
	}

	private static double max(List<Double> values) {
		return values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElseThrow();
	}
}
