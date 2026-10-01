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
import org.openjdk.jmc.common.item.IItemCollection;

import se.hirt.jmc.jevmcp.RecordingService.Recording;

class EnvironmentMetricsTest {

	@Test
	void realRecordingReportsHeapAndCollectors() throws Exception {
		Recording recording = new RecordingService().load(TestRecordings.wldf().getAbsolutePath());
		Map<String, Object> environment = EnvironmentMetrics.compute(recording.getItems());

		assertEquals(512.0, map(environment, "memory").get("maxHeapMb"));
		assertEquals("ParallelScavenge", map(environment, "gc").get("youngCollector"));
		assertEquals(8L, map(environment, "cpu").get("hwThreads"));
		assertFalse(environment.containsKey("container"), "wldf.jfr was not recorded in a container");
	}

	@Test
	void containerLimitsAndThrottling() throws Exception {
		Map<String, Object> container = map(EnvironmentMetrics.compute(SyntheticContainerRecording.load()),
				"container");

		assertEquals("cgroupv2", container.get("containerType"));
		assertEquals(2.0, container.get("cpuLimitCores"));
		assertEquals(2L, container.get("effectiveCpuCount"));
		assertEquals(1024.0, container.get("memoryLimitMb"));
		assertEquals(65536.0, container.get("hostTotalMemoryMb"));
		// -1 means unlimited, and is left out rather than reported as a negative limit.
		assertFalse(container.containsKey("cpuShares"));
		assertFalse(container.containsKey("memorySoftLimitMb"));

		// Counters go from 1000/100 to 1200/200 elapsed/throttled slices during the recording.
		assertEquals(50.0, container.get("cpuThrottledPctDuringRecording"));
		assertEquals(16.67, container.get("cpuThrottledPctSinceContainerStart"));
		assertEquals(500.0, container.get("cpuThrottledTimeDuringRecordingMs"));
		assertEquals(2L, container.get("memoryFailCountIncrease"));
	}

	@Test
	void containerTimeSeries() throws Exception {
		IItemCollection items = SyntheticContainerRecording.load();
		Map<String, Object> timeSeries = TimeSeriesMetrics.compute(items, JfrToolkit.getRecordingStart(items),
				JfrToolkit.getRecordingEnd(items));
		Map<String, Object> series = map(timeSeries, "series");

		assertTrue(series.containsKey("containerMemoryUsageMb"));
		// 0.5 s of container CPU time per sample, with samples a little over 0.25 s apart.
		for (Object value : (Iterable<?>) series.get("containerCpuUsageCores")) {
			if (value != null) {
				double cores = ((Number) value).doubleValue();
				assertTrue(cores > 1.0 && cores <= 2.0, "unexpected container CPU usage " + cores);
			}
		}
		for (Object value : (Iterable<?>) series.get("containerCpuThrottledPct")) {
			if (value != null) {
				assertEquals(50.0, value);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Map<String, Object> map, String key) {
		return (Map<String, Object>) map.get(key);
	}
}
