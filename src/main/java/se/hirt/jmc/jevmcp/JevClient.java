/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.util.Map;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client for the TypeSafe System One evaluation API. Request and response bodies are plain
 * maps rather than typed DTOs - both are open-ended, model-defined JSON shapes, and Jackson handles
 * {@code Map<String,Object>} under native-image without any extra reflection registration.
 */
@RegisterRestClient(configKey = "jev-api")
@RegisterProvider(JevAuthFilter.class)
public interface JevClient {

	@POST
	@Path("/v1/systemone")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	Map<String, Object> evaluate(Map<String, Object> request);
}
