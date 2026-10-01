/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.io.File;
import java.net.URISyntaxException;

/**
 * Points tests at the sample recordings in {@code src/test/resources/recordings}. {@code wldf.jfr}
 * is a real WebLogic recording with GC, allocation, and rule findings worth exercising - not a
 * synthetic fixture.
 */
final class TestRecordings {

	private TestRecordings() {
	}

	static File wldf() {
		return resource("wldf.jfr");
	}

	/**
	 * Synthetic container events with known values, see {@link SyntheticContainerRecording}.
	 */
	static File containerSynthetic() {
		return resource("container-synthetic.jfr");
	}

	private static File resource(String name) {
		try {
			return new File(TestRecordings.class.getResource("/recordings/" + name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}
}
