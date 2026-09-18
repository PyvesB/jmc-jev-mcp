/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.io.IOException;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;

/**
 * Adds the {@code Authorization} header to every call to the Jev API, read from the {@code JEV_KEY}
 * environment variable. Deliberately not checked at server startup - a missing key should not stop
 * the rest of the server (recording tools, getRuleResults) from working.
 */
public class JevAuthFilter implements ClientRequestFilter {

	static final String ENV_VAR = "JEV_KEY";

	@Override
	public void filter(ClientRequestContext requestContext) throws IOException {
		String apiKey = System.getenv(ENV_VAR);
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException(
					"The " + ENV_VAR + " environment variable is not set. Export a TypeSafe API key as " + ENV_VAR
							+ " and restart the server to use Jev-backed tools.");
		}
		requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
	}
}
