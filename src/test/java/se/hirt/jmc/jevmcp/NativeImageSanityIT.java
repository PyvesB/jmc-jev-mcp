/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Sanity test for the native image binary. Starts the native executable, sends an MCP initialize +
 * tools/list request over STDIO, and verifies the server responds with its tool list.
 * <p>
 * Skipped unless the {@code native.image.path} system property is set, e.g.:
 *
 * <pre>
 *   mvn verify -Dnative -Dnative.image.path=target/jmc-jev-mcp-...-runner
 * </pre>
 *
 * The release workflow sets this after the native build completes.
 */
class NativeImageSanityIT {

	@Test
	@EnabledIfSystemProperty(named = "native.image.path", matches = ".+")
	void nativeBinaryRespondsToMcpInitialize() throws Exception {
		String binaryPath = System.getProperty("native.image.path");
		Path binary = Path.of(binaryPath);
		assertTrue(Files.exists(binary), "Native binary not found at: " + binaryPath);

		ProcessBuilder pb = new ProcessBuilder(binary.toAbsolutePath().toString(),
				"-Dquarkus.mcp.server.stdio.enabled=true");
		pb.redirectErrorStream(false);
		Process process = pb.start();

		try {
			OutputStream stdin = process.getOutputStream();
			InputStream stdout = process.getInputStream();

			String initRequest = "{\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
					+ "\"capabilities\":{},\"clientInfo\":{\"name\":\"sanity-test\",\"version\":\"1.0\"}},"
					+ "\"jsonrpc\":\"2.0\",\"id\":0}\n";
			stdin.write(initRequest.getBytes(StandardCharsets.UTF_8));
			stdin.flush();

			String initResponse = readResponse(stdout, 10_000);
			assertNotNull(initResponse, "No response from native binary for initialize");
			assertTrue(initResponse.contains("\"serverInfo\""),
					"Initialize response should contain serverInfo: " + initResponse);
			assertTrue(initResponse.contains("jmc-jev-mcp"), "Server name should be jmc-jev-mcp: " + initResponse);

			String initialized = "{\"method\":\"notifications/initialized\",\"jsonrpc\":\"2.0\"}\n";
			stdin.write(initialized.getBytes(StandardCharsets.UTF_8));
			stdin.flush();

			String toolsListRequest = "{\"method\":\"tools/list\",\"params\":{},\"jsonrpc\":\"2.0\",\"id\":1}\n";
			stdin.write(toolsListRequest.getBytes(StandardCharsets.UTF_8));
			stdin.flush();

			String toolsResponse = readResponse(stdout, 10_000);
			assertNotNull(toolsResponse, "No response from native binary for tools/list");
			assertTrue(toolsResponse.contains("\"tools\""),
					"tools/list response should contain tools array: " + toolsResponse);
			assertTrue(toolsResponse.contains("getVersion"), "tools/list should include getVersion: " + toolsResponse);

			String getVersion = "{\"method\":\"tools/call\",\"params\":{\"name\":\"getVersion\",\"arguments\":{}},"
					+ "\"jsonrpc\":\"2.0\",\"id\":2}\n";
			stdin.write(getVersion.getBytes(StandardCharsets.UTF_8));
			stdin.flush();

			String versionResponse = readResponse(stdout, 10_000);
			assertNotNull(versionResponse, "No response from native binary for getVersion");
			assertTrue(versionResponse.contains("\"result\""), "getVersion should return a result: " + versionResponse);
		} finally {
			process.destroyForcibly();
			process.waitFor();
		}
	}

	/**
	 * Reads a single JSON-RPC response line from the process stdout, with a timeout to avoid
	 * hanging forever.
	 */
	private String readResponse(InputStream stdout, long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		StringBuilder sb = new StringBuilder();
		while (System.currentTimeMillis() < deadline) {
			if (stdout.available() > 0) {
				int b = stdout.read();
				if (b == -1) {
					break;
				}
				if (b == '\n') {
					String line = sb.toString().trim();
					if (!line.isEmpty()) {
						return line;
					}
					sb.setLength(0);
				} else {
					sb.append((char) b);
				}
			} else {
				Thread.sleep(50);
			}
		}
		String remaining = sb.toString().trim();
		return remaining.isEmpty() ? null : remaining;
	}
}
