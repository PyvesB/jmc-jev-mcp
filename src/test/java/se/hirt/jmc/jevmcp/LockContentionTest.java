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

import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.item.ItemCollectionToolkit;

import se.hirt.jmc.jevmcp.RecordingService.Recording;

class LockContentionTest {

	@Test
	void emptyRecordingYieldsNoLocks() {
		assertTrue(LockContention.compute(ItemCollectionToolkit.EMPTY).isEmpty());
	}

	@Test
	void realRecordingRanksLocksByBlockedTime() throws Exception {
		Recording recording = new RecordingService().load(TestRecordings.wldf().getAbsolutePath());
		Map<String, Object> contention = LockContention.compute(recording.getItems());

		List<Map<String, Object>> monitors = rows(contention, "monitorEnter");
		// wldf.jfr's monitor contention is dominated by logging.
		assertEquals("org.apache.log4j.Logger", monitors.get(0).get("monitorClass"));
		assertSortedByTotalDuration(monitors);

		@SuppressWarnings("unchecked")
		Map<String, Object> histogram = (Map<String, Object>) DurationHistograms.compute(recording.getItems())
				.get("monitorEnter");
		long total = monitors.stream().mapToLong(m -> ((Number) m.get("count")).longValue()).sum();
		assertEquals(((Number) histogram.get("count")).longValue(), total,
				"every monitor enter should land in exactly one monitor class");

		List<Map<String, Object>> parked = rows(contention, "threadPark");
		assertTrue(parked.size() <= LockContention.TOP_LOCKS);
		assertSortedByTotalDuration(parked);
		for (Map<String, Object> row : parked) {
			assertTrue(((Number) row.get("distinctAddresses")).intValue() >= 1);
		}
	}

	private static void assertSortedByTotalDuration(List<Map<String, Object>> rows) {
		for (int i = 1; i < rows.size(); i++) {
			assertTrue(((Number) rows.get(i - 1).get("totalDurationMs"))
					.doubleValue() >= ((Number) rows.get(i).get("totalDurationMs")).doubleValue());
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> rows(Map<String, Object> map, String key) {
		return (List<Map<String, Object>>) map.get(key);
	}
}
