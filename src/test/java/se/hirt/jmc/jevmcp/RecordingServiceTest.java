/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import se.hirt.jmc.jevmcp.RecordingService.Recording;

class RecordingServiceTest {

	@Test
	void loadGetAndUnloadRoundTrip() throws Exception {
		RecordingService service = new RecordingService();
		String path = TestRecordings.wldf().getAbsolutePath();

		Recording loaded = service.load(path);
		assertTrue(loaded.getItems().hasItems());
		assertEquals(1, service.listIds().size());

		// A blank id resolves to the single loaded recording.
		assertSame(loaded, service.get(""));
		// Loading the same path again returns the cached recording rather than a new one.
		assertSame(loaded, service.load(path));

		assertTrue(loaded.getStart().compareTo(loaded.getEnd()) < 0);

		assertTrue(service.unload(loaded.getId()));
		assertTrue(service.listIds().isEmpty());
	}

	@Test
	void getWithoutAnyLoadedRecordingFails() {
		RecordingService service = new RecordingService();
		Exception e = assertThrows(IllegalArgumentException.class, () -> service.get(""));
		assertTrue(e.getMessage().contains("No recording is loaded"));
	}

	@Test
	void ruleResultsAreComputedAndCached() throws Exception {
		RecordingService service = new RecordingService();
		Recording recording = service.load(TestRecordings.wldf().getAbsolutePath());

		var first = service.getRuleResults(recording);
		var second = service.getRuleResults(recording);
		assertFalse(first.isEmpty());
		assertSame(first, second);
	}
}
