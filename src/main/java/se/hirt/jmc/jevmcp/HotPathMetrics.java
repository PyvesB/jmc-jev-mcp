/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkTypeIDs;
import org.openjdk.jmc.flightrecorder.stacktrace.FrameSeparator;
import org.openjdk.jmc.flightrecorder.stacktrace.FrameSeparator.FrameCategorization;
import org.openjdk.jmc.flightrecorder.stacktrace.graph.AggregatableFrame;
import org.openjdk.jmc.flightrecorder.stacktrace.graph.Edge;
import org.openjdk.jmc.flightrecorder.stacktrace.graph.Node;
import org.openjdk.jmc.flightrecorder.stacktrace.graph.Pruning;
import org.openjdk.jmc.flightrecorder.stacktrace.graph.StacktraceGraphModel;

/**
 * A pruned call-graph summary of where a recording's execution or allocation activity is
 * concentrated. Uses JMC's own {@link Pruning} - the entropy-based reduction its graph view uses to
 * keep flame graphs readable - rather than a naive top-N flat list, so caller/callee structure
 * survives the cut down to a size that fits into a single Jev request's state.
 */
final class HotPathMetrics {

	static final int DEFAULT_MAX_NODES = 80;
	static final int HARD_CAP_MAX_NODES = 500;

	private HotPathMetrics() {
	}

	static Map<String, Object> computeExecutionHotPath(IItemCollection items, int maxNodes) {
		IItemCollection filtered = items.apply(ItemFilters.type(JdkTypeIDs.EXECUTION_SAMPLE));
		return compute(filtered, null, JdkTypeIDs.EXECUTION_SAMPLE, maxNodes);
	}

	static Map<String, Object> computeAllocationHotPath(IItemCollection items, int maxNodes) {
		IItemCollection sampled = items.apply(ItemFilters.type(JdkTypeIDs.OBJ_ALLOC_SAMPLE));
		if (sampled.hasItems()) {
			return compute(sampled, JdkAttributes.SAMPLE_WEIGHT, JdkTypeIDs.OBJ_ALLOC_SAMPLE, maxNodes);
		}
		IItemCollection tlab = items
				.apply(ItemFilters.type(JdkTypeIDs.ALLOC_INSIDE_TLAB, JdkTypeIDs.ALLOC_OUTSIDE_TLAB));
		return compute(tlab, JdkAttributes.ALLOCATION_SIZE,
				JdkTypeIDs.ALLOC_INSIDE_TLAB + "/" + JdkTypeIDs.ALLOC_OUTSIDE_TLAB, maxNodes);
	}

	/**
	 * Clamps a caller-supplied node budget the same way the other tools clamp their limits: a
	 * missing or non-positive value falls back to {@link #DEFAULT_MAX_NODES}, and anything above
	 * {@link #HARD_CAP_MAX_NODES} is capped, to keep a single request's state bounded.
	 */
	static int clampMaxNodes(Integer requested) {
		int value = requested == null || requested <= 0 ? DEFAULT_MAX_NODES : requested;
		return Math.min(value, HARD_CAP_MAX_NODES);
	}

	private static Map<String, Object> compute(
		IItemCollection filtered, IAttribute<IQuantity> weightAttribute, String eventTypeLabel, int maxNodes) {
		if (!filtered.hasItems()) {
			return null;
		}
		FrameSeparator separator = new FrameSeparator(FrameCategorization.METHOD, false);
		StacktraceGraphModel model = new StacktraceGraphModel(separator, filtered, weightAttribute);
		if (model.isEmpty()) {
			return null;
		}
		StacktraceGraphModel pruned = Pruning.prune(model, maxNodes, true);

		List<Map<String, Object>> nodes = new ArrayList<>();
		for (Node node : pruned.getNodes()) {
			Map<String, Object> n = new LinkedHashMap<>();
			n.put("id", node.getNodeId());
			n.put("frame", describeFrame(node.getFrame()));
			n.put("selfCount", node.getCount());
			n.put("selfWeight", round(node.getWeight()));
			n.put("cumulativeCount", node.getCumulativeCount());
			nodes.add(n);
		}

		List<Map<String, Object>> edges = new ArrayList<>();
		for (Edge edge : pruned.getEdges()) {
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("from", edge.getFrom().getNodeId());
			e.put("to", edge.getTo().getNodeId());
			e.put("count", edge.getCount());
			e.put("value", round(edge.getValue()));
			edges.add(e);
		}

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("eventType", eventTypeLabel);
		summary.put("totalTraceCount", model.getTotalTraceCount());
		summary.put("nodeCount", nodes.size());
		summary.put("nodes", nodes);
		summary.put("edges", edges);
		return summary;
	}

	private static String describeFrame(AggregatableFrame frame) {
		return frame != null ? frame.getHumanReadableShortString() : "<unknown>";
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
