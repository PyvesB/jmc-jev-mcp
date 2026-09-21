/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.io.File;
import java.net.URISyntaxException;

/**
 * Points tests at the sample recording in {@code src/test/resources/recordings}. It's a real
 * WebLogic recording with GC, allocation, and rule findings worth exercising - not a synthetic
 * fixture.
 */
final class TestRecordings {

	private TestRecordings() {
	}

	static File wldf() {
		try {
			return new File(TestRecordings.class.getResource("/recordings/wldf.jfr").toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}
}
