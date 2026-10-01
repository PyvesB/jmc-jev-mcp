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

import se.hirt.jmc.jevmcp.RecordingService.Recording;

class HotPathMetricsTest {

	@Test
	void emptyRecordingYieldsNoHotPaths() {
		assertNull(
				HotPathMetrics.computeExecutionHotPath(ItemCollectionToolkit.EMPTY, HotPathMetrics.DEFAULT_MAX_NODES));
		assertNull(
				HotPathMetrics.computeAllocationHotPath(ItemCollectionToolkit.EMPTY, HotPathMetrics.DEFAULT_MAX_NODES));
	}

	@Test
	void clampMaxNodesFallsBackAndCaps() {
		assertTrue(HotPathMetrics.clampMaxNodes(null) == HotPathMetrics.DEFAULT_MAX_NODES);
		assertTrue(HotPathMetrics.clampMaxNodes(0) == HotPathMetrics.DEFAULT_MAX_NODES);
		assertTrue(HotPathMetrics.clampMaxNodes(-5) == HotPathMetrics.DEFAULT_MAX_NODES);
		assertTrue(HotPathMetrics.clampMaxNodes(10) == 10);
		assertTrue(HotPathMetrics
				.clampMaxNodes(HotPathMetrics.HARD_CAP_MAX_NODES + 1000) == HotPathMetrics.HARD_CAP_MAX_NODES);
	}

	@Test
	void realRecordingYieldsBoundedPrunedGraph() throws Exception {
		RecordingService service = new RecordingService();
		Recording recording = service.load(TestRecordings.wldf().getAbsolutePath());

		Map<String, Object> executionHotPath = HotPathMetrics.computeExecutionHotPath(recording.getItems(),
				HotPathMetrics.DEFAULT_MAX_NODES);
		Map<String, Object> allocationHotPath = HotPathMetrics.computeAllocationHotPath(recording.getItems(),
				HotPathMetrics.DEFAULT_MAX_NODES);

		// wldf.jfr carries both execution and allocation samples.
		assertNotNull(executionHotPath);
		assertNotNull(allocationHotPath);

		for (Map<String, Object> hotPath : List.of(executionHotPath, allocationHotPath)) {
			assertTrue(hotPath.containsKey("eventType"));
			assertTrue(((Number) hotPath.get("totalTraceCount")).longValue() > 0);

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> nodes = (List<Map<String, Object>>) hotPath.get("nodes");
			assertTrue(nodes.size() > 0);
			assertTrue(nodes.size() <= 80, "expected at most 80 nodes, got " + nodes.size());
			for (Map<String, Object> node : nodes) {
				assertTrue(node.containsKey("id"));
				assertTrue(node.containsKey("frame"));
				assertTrue(node.containsKey("selfCount"));
				assertTrue(node.containsKey("selfWeight"));
				assertTrue(node.containsKey("cumulativeCount"));
			}

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> edges = (List<Map<String, Object>>) hotPath.get("edges");
			for (Map<String, Object> edge : edges) {
				assertTrue(edge.containsKey("from"));
				assertTrue(edge.containsKey("to"));
				assertTrue(edge.containsKey("count"));
				assertTrue(edge.containsKey("value"));
			}
		}
	}

	@Test
	void monitorEnterHotPathIsWeightedByBlockedMilliseconds() throws Exception {
		Recording recording = new RecordingService().load(TestRecordings.wldf().getAbsolutePath());
		Map<String, Object> hotPath = HotPathMetrics.computeMonitorEnterHotPath(recording.getItems(),
				HotPathMetrics.DEFAULT_MAX_NODES);

		// Every recorded enter is at least as long as the recording's 20 ms threshold, so a frame's
		// weight falling back to its event count would show up as less than 20 ms per event.
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> nodes = (List<Map<String, Object>>) hotPath.get("nodes");
		boolean anyWeighted = false;
		for (Map<String, Object> node : nodes) {
			long count = ((Number) node.get("selfCount")).longValue();
			double weight = ((Number) node.get("selfWeight")).doubleValue();
			assertTrue(weight >= 20 * count, node.toString());
			anyWeighted |= count > 0;
		}
		assertTrue(anyWeighted);
	}

	@Test
	void tlabFallbackIsWeightedByTlabSize() throws Exception {
		Recording recording = new RecordingService().load(TestRecordings.wldf().getAbsolutePath());
		Map<String, Object> hotPath = HotPathMetrics.computeAllocationHotPath(recording.getItems(),
				HotPathMetrics.DEFAULT_MAX_NODES);

		// wldf.jfr predates ObjectAllocationSample, so this is the TLAB fallback. Each in-TLAB event
		// stands for a whole TLAB, so weights must be in bytes, far above one per event.
		assertEquals("jdk.ObjectAllocationInNewTLAB/jdk.ObjectAllocationOutsideTLAB", hotPath.get("eventType"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> nodes = (List<Map<String, Object>>) hotPath.get("nodes");
		double weight = nodes.stream().mapToDouble(n -> ((Number) n.get("selfWeight")).doubleValue()).sum();
		long count = nodes.stream().mapToLong(n -> ((Number) n.get("selfCount")).longValue()).sum();
		assertTrue(weight > 1024.0 * count, weight + " bytes for " + count + " events");
	}

	@Test
	void customMaxNodesIsHonored() throws Exception {
		RecordingService service = new RecordingService();
		Recording recording = service.load(TestRecordings.wldf().getAbsolutePath());

		Map<String, Object> executionHotPath = HotPathMetrics.computeExecutionHotPath(recording.getItems(), 10);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> nodes = (List<Map<String, Object>>) executionHotPath.get("nodes");
		assertTrue(nodes.size() <= 10, "expected at most 10 nodes, got " + nodes.size());
	}
}
