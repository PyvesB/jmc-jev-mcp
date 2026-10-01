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

import org.openjdk.jmc.common.IDescribable;
import org.openjdk.jmc.common.item.Attribute;
import org.openjdk.jmc.common.item.IAccessorKey;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.ICanonicalAccessorFactory;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.IType;
import org.openjdk.jmc.common.item.ItemCollectionToolkit;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.item.ItemIterableToolkit;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
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

	/**
	 * The graph model sums the raw double value of its weight attribute, so durations are converted
	 * to a plain millisecond number first rather than left in whatever unit the parser produced.
	 */
	private static final IAttribute<IQuantity> DURATION_MS = new Attribute<IQuantity>("(durationMs)", "Duration (ms)",
			null, UnitLookup.NUMBER) {
		@Override
		public <U> IMemberAccessor<IQuantity, U> customAccessor(IType<U> type) {
			IMemberAccessor<IQuantity, U> accessor = JfrAttributes.DURATION.getAccessor(type);
			return accessor == null ? null : item -> {
				IQuantity duration = accessor.getMember(item);
				return duration == null ? null
						: UnitLookup.NUMBER_UNITY.quantity(duration.doubleValueIn(UnitLookup.MILLISECOND));
			};
		}
	};

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
		return compute(tlab, JdkAttributes.TOTAL_ALLOCATION_SIZE,
				JdkTypeIDs.ALLOC_INSIDE_TLAB + "/" + JdkTypeIDs.ALLOC_OUTSIDE_TLAB, maxNodes);
	}

	/**
	 * Weighted by how long threads were blocked rather than by how many events there were, since a
	 * single long enter matters more than many enters just over the recording threshold.
	 */
	static Map<String, Object> computeMonitorEnterHotPath(IItemCollection items, int maxNodes) {
		IItemCollection filtered = items.apply(ItemFilters.type(JdkTypeIDs.MONITOR_ENTER));
		return compute(filtered, DURATION_MS, JdkTypeIDs.MONITOR_ENTER, maxNodes);
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
		StacktraceGraphModel model = new StacktraceGraphModel(separator,
				weightAttribute != null ? withWeight(filtered, weightAttribute) : filtered, weightAttribute);
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

	/**
	 * StacktraceGraphModel looks its weight up directly on the event type by key, so a derived
	 * attribute such as {@link JdkAttributes#TOTAL_ALLOCATION_SIZE} or {@link #DURATION_MS} would
	 * silently fall back to a weight of 1 per event. Re-exposes the weight under its own key on a
	 * delegating type, and turns a missing value into 0, which the model does not guard against.
	 */
	private static IItemCollection withWeight(IItemCollection items, IAttribute<IQuantity> weight) {
		List<IItemIterable> weighted = new ArrayList<>();
		for (IItemIterable iterable : items) {
			IMemberAccessor<IQuantity, IItem> accessor = weight.getAccessor(iterable.getType());
			if (accessor != null) {
				weighted.add(ItemIterableToolkit.build(iterable::stream,
						new WeightedType(iterable.getType(), weight.getKey(), accessor)));
			}
		}
		return ItemCollectionToolkit.build(weighted::stream);
	}

	private static final class WeightedType implements IType<IItem> {
		private static final IQuantity ZERO = UnitLookup.NUMBER_UNITY.quantity(0);

		private final IType<IItem> delegate;
		private final IAccessorKey<IQuantity> weightKey;
		private final IMemberAccessor<IQuantity, IItem> weightAccessor;

		WeightedType(IType<IItem> delegate, IAccessorKey<IQuantity> weightKey,
				IMemberAccessor<IQuantity, IItem> accessor) {
			this.delegate = delegate;
			this.weightKey = weightKey;
			this.weightAccessor = item -> {
				IQuantity value = accessor.getMember(item);
				return value != null ? value : ZERO;
			};
		}

		@SuppressWarnings("unchecked")
		@Override
		public <M> IMemberAccessor<M, IItem> getAccessor(IAccessorKey<M> key) {
			return weightKey.equals(key) ? (IMemberAccessor<M, IItem>) weightAccessor : delegate.getAccessor(key);
		}

		@Override
		public List<IAttribute<?>> getAttributes() {
			return delegate.getAttributes();
		}

		@Override
		public Map<IAccessorKey<?>, ? extends IDescribable> getAccessorKeys() {
			return delegate.getAccessorKeys();
		}

		@Override
		public boolean hasAttribute(ICanonicalAccessorFactory<?> attribute) {
			return delegate.hasAttribute(attribute);
		}

		@Override
		public String getIdentifier() {
			return delegate.getIdentifier();
		}

		@Override
		public String getName() {
			return delegate.getName();
		}

		@Override
		public String getDescription() {
			return delegate.getDescription();
		}
	}

	private static String describeFrame(AggregatableFrame frame) {
		return frame != null ? frame.getHumanReadableShortString() : "<unknown>";
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
