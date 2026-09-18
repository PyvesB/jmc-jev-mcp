/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.item.ItemCollectionToolkit;

class JfrToolkitTest {

	@Test
	void formatQuantityHandlesNull() {
		assertEquals("N/A", JfrToolkit.formatQuantity(null));
	}

	@Test
	void describeErrorUsesMessageWhenPresent() {
		assertEquals("boom", JfrToolkit.describeError(new IllegalStateException("boom")));
	}

	@Test
	void describeErrorFallsBackToClassNameWhenMessageIsBlank() {
		assertEquals("IllegalStateException", JfrToolkit.describeError(new IllegalStateException()));
	}

	@Test
	void recordingBoundsAreNullForAnEmptyCollection() {
		assertEquals(null, JfrToolkit.getRecordingStart(ItemCollectionToolkit.EMPTY));
		assertEquals(null, JfrToolkit.getRecordingEnd(ItemCollectionToolkit.EMPTY));
	}
}
