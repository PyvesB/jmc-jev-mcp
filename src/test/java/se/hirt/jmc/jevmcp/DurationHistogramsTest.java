/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.item.ItemCollectionToolkit;
import org.openjdk.jmc.flightrecorder.jdk.JdkTypeIDs;

import se.hirt.jmc.jevmcp.RecordingService.Recording;

class DurationHistogramsTest {

	@Test
	void emptyRecordingYieldsOnlyAvailability() {
		Map<String, Object> histograms = DurationHistograms.compute(ItemCollectionToolkit.EMPTY);
		assertEquals(List.of("eventAvailability"), List.copyOf(histograms.keySet()));
		assertNull(DurationHistograms.compute(ItemCollectionToolkit.EMPTY, JdkTypeIDs.MONITOR_ENTER));
	}

	@Test
	void vmOperationsAreBrokenDownByOperation() throws Exception {
		RecordingService service = new RecordingService();
		Recording recording = service.load(TestRecordings.wldf().getAbsolutePath());

		@SuppressWarnings("unchecked")
		Map<String, Object> vmOperation = (Map<String, Object>) DurationHistograms.compute(recording.getItems())
				.get("vmOperation");
		assertNotNull(vmOperation);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> top = (List<Map<String, Object>>) vmOperation.get("topOperations");
		assertTrue(!top.isEmpty());
		long topCount = top.stream().mapToLong(row -> ((Number) row.get("count")).longValue()).sum();
		assertTrue(topCount <= ((Number) vmOperation.get("count")).longValue());
	}

	@Test
	void realRecordingYieldsConsistentHistograms() throws Exception {
		RecordingService service = new RecordingService();
		Recording recording = service.load(TestRecordings.wldf().getAbsolutePath());

		Map<String, Object> histograms = DurationHistograms.compute(recording.getItems());
		assertNotNull(histograms.get("monitorEnter"), "expected monitor enter events in wldf.jfr: " + histograms);

		for (Map.Entry<String, Object> entry : histograms.entrySet()) {
			if (entry.getKey().equals("eventAvailability")) {
				continue;
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> histogram = (Map<String, Object>) entry.getValue();
			long count = ((Number) histogram.get("count")).longValue();
			assertTrue(count > 0);

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> buckets = (List<Map<String, Object>>) histogram.get("buckets");
			long bucketTotal = buckets.stream().mapToLong(b -> ((Number) b.get("count")).longValue()).sum();
			assertEquals(count, bucketTotal, entry.getKey() + ": bucket counts must add up to the event count");
			assertTrue(((Number) buckets.get(0).get("count")).longValue() > 0);
			assertTrue(((Number) buckets.get(buckets.size() - 1).get("count")).longValue() > 0);

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> percentiles = (List<Map<String, Object>>) histogram.get("percentiles");
			double previous = 0;
			for (Map<String, Object> percentile : percentiles) {
				double durationMs = ((Number) percentile.get("durationMs")).doubleValue();
				assertTrue(durationMs >= previous, entry.getKey() + ": percentiles must be non-decreasing");
				previous = durationMs;
				long atOrAbove = ((Number) percentile.get("countAtOrAbove")).longValue();
				assertTrue(atOrAbove >= 1 && atOrAbove <= count);
			}
		}
	}
}
