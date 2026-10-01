/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramIterationValue;
import org.openjdk.jmc.common.IMCType;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemFilter;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkTypeIDs;
import org.openjdk.jmc.flightrecorder.rules.util.RulesToolkit;

/**
 * Duration histograms, backed by HdrHistogram like JMC's own percentile tables, for duration events
 * where the shape of the distribution matters more than the average - a handful of very long
 * monitor enters is a different problem than many short ones, even at the same total blocked time.
 */
final class DurationHistograms {

	/**
	 * Upper bucket bounds in milliseconds, in a 1-2-5 sequence. The last bucket is open ended.
	 */
	private static final double[] BUCKET_UPPER_BOUNDS_MS = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000,
			10000};

	/**
	 * The percentiles JMC's duration percentile table shows, with the median added.
	 */
	private static final double[] PERCENTILES = {50.0, 90.0, 99.0, 99.9, 99.99, 100.0};

	/**
	 * Same precision as JMC's DurationHdrHistogram.
	 */
	private static final int SIGNIFICANT_DIGITS = 3;

	private static final String THRESHOLD_SETTING = "threshold";

	private static final int TOP_OPERATIONS = 5;

	private DurationHistograms() {
	}

	/**
	 * Computes a histogram per event type, keyed by a short name. Event types without any events in
	 * the recording are left out, with {@code eventAvailability} telling why.
	 */
	static Map<String, Object> compute(IItemCollection items) {
		Map<String, Object> histograms = new LinkedHashMap<>();
		putIfPresent(histograms, "monitorEnter", compute(items, JdkTypeIDs.MONITOR_ENTER));
		putIfPresent(histograms, "threadPark", compute(items, JdkTypeIDs.THREAD_PARK));
		putIfPresent(histograms, "gcPause", compute(items, JdkTypeIDs.GC_PAUSE));
		putIfPresent(histograms, "safepointBegin", compute(items, JdkTypeIDs.SAFEPOINT_BEGIN));
		Map<String, Object> vmOperation = compute(items, JdkTypeIDs.VM_OPERATIONS, ItemFilters
				.and(ItemFilters.type(JdkTypeIDs.VM_OPERATIONS), ItemFilters.equals(JdkAttributes.SAFEPOINT, true)));
		if (vmOperation != null) {
			vmOperation.put("topOperations",
					topByTotalDuration(
							items.apply(ItemFilters.and(ItemFilters.type(JdkTypeIDs.VM_OPERATIONS),
									ItemFilters.equals(JdkAttributes.SAFEPOINT, true))),
							JdkAttributes.OPERATION, "operation", null, null, TOP_OPERATIONS));
			histograms.put("vmOperation", vmOperation);
		}
		histograms.put("eventAvailability", JfrToolkit.eventAvailability(items, JdkTypeIDs.MONITOR_ENTER,
				JdkTypeIDs.THREAD_PARK, JdkTypeIDs.GC_PAUSE, JdkTypeIDs.SAFEPOINT_BEGIN, JdkTypeIDs.VM_OPERATIONS));
		return histograms;
	}

	static Map<String, Object> compute(IItemCollection items, String typeId) {
		return compute(items, typeId, ItemFilters.type(typeId));
	}

	private static Map<String, Object> compute(IItemCollection items, String typeId, IItemFilter filter) {
		Histogram histogram = record(items.apply(filter));
		if (histogram.getTotalCount() == 0) {
			return null;
		}

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("eventType", typeId);
		Double thresholdMs = thresholdMs(items, typeId);
		if (thresholdMs != null) {
			summary.put("thresholdMs", round(thresholdMs));
		}
		summary.put("count", histogram.getTotalCount());
		summary.put("totalDurationMs", round(toMs(histogram.getMean() * histogram.getTotalCount())));
		summary.put("percentiles", percentiles(histogram));
		summary.put("buckets", buckets(histogram));
		return summary;
	}

	/**
	 * Groups duration events by an attribute, returning the groups with the highest total duration
	 * first. If {@code distinctBy} is given, each group also reports how many distinct values of it
	 * were seen, e.g. how many lock addresses share a monitor class.
	 */
	static List<Map<String, Object>> topByTotalDuration(
		IItemCollection items, IAttribute<?> groupBy, String groupName, IAttribute<?> distinctBy, String distinctName,
		int limit) {
		Map<String, Group> groups = new HashMap<>();
		for (IItemIterable iterable : items) {
			IMemberAccessor<?, IItem> groupAccessor = groupBy.getAccessor(iterable.getType());
			IMemberAccessor<IQuantity, IItem> durationAccessor = JfrAttributes.DURATION.getAccessor(iterable.getType());
			if (groupAccessor == null || durationAccessor == null) {
				continue;
			}
			IMemberAccessor<?, IItem> distinctAccessor = distinctBy != null ? distinctBy.getAccessor(iterable.getType())
					: null;
			for (IItem item : iterable) {
				IQuantity duration = durationAccessor.getMember(item);
				if (duration == null) {
					continue;
				}
				Group group = groups.computeIfAbsent(describe(groupAccessor.getMember(item)), k -> new Group());
				double ms = duration.doubleValueIn(UnitLookup.MILLISECOND);
				group.count++;
				group.totalMs += ms;
				group.maxMs = Math.max(group.maxMs, ms);
				if (distinctAccessor != null) {
					group.distinct.add(distinctAccessor.getMember(item));
				}
			}
		}
		List<Map<String, Object>> top = new ArrayList<>();
		groups.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue().totalMs, a.getValue().totalMs))
				.limit(limit).forEach(entry -> {
					Group group = entry.getValue();
					Map<String, Object> row = new LinkedHashMap<>();
					row.put(groupName, entry.getKey());
					row.put("count", group.count);
					row.put("totalDurationMs", round(group.totalMs));
					row.put("maxMs", round(group.maxMs));
					if (distinctBy != null) {
						row.put(distinctName, group.distinct.size());
					}
					top.add(row);
				});
		return top;
	}

	private static final class Group {
		long count;
		double totalMs;
		double maxMs;
		final Set<Object> distinct = new HashSet<>();
	}

	private static String describe(Object value) {
		if (value instanceof IMCType type) {
			return type.getFullName();
		}
		return value != null ? value.toString() : "<unknown>";
	}

	private static Histogram record(IItemCollection items) {
		Histogram histogram = new Histogram(SIGNIFICANT_DIGITS);
		for (IItemIterable iterable : items) {
			IMemberAccessor<IQuantity, IItem> accessor = JfrAttributes.DURATION.getAccessor(iterable.getType());
			if (accessor == null) {
				continue;
			}
			for (IItem item : iterable) {
				IQuantity duration = accessor.getMember(item);
				if (duration != null) {
					histogram.recordValue(Math.max(0, duration.clampedLongValueIn(UnitLookup.NANOSECOND)));
				}
			}
		}
		return histogram;
	}

	/**
	 * Like JMC's duration percentile table, each percentile carries the number of events at or
	 * above it, so a p99.9 backed by a single event can be told apart from one backed by thousands.
	 */
	private static List<Map<String, Object>> percentiles(Histogram histogram) {
		List<Map<String, Object>> percentiles = new ArrayList<>();
		for (double percentile : PERCENTILES) {
			long valueNs = percentile == 100.0 ? histogram.getMaxValue() : histogram.getValueAtPercentile(percentile);
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("percentile", percentile);
			entry.put("durationMs", round(toMs(valueNs)));
			entry.put("countAtOrAbove", histogram.getCountBetweenValues(valueNs, histogram.getMaxValue()));
			percentiles.add(entry);
		}
		return percentiles;
	}

	/**
	 * Only the span from the first to the last non-empty bucket is reported, keeping the empty
	 * buckets in between so gaps in the distribution stay visible.
	 */
	private static List<Map<String, Object>> buckets(Histogram histogram) {
		// Not getCountBetweenValues per bucket: it works on equivalence ranges, so values near a
		// bucket edge would be counted in both neighbors.
		long[] counts = new long[BUCKET_UPPER_BOUNDS_MS.length + 1];
		for (HistogramIterationValue value : histogram.recordedValues()) {
			counts[bucketOf(toMs(value.getValueIteratedTo()))] += value.getCountAtValueIteratedTo();
		}
		int first = 0;
		while (counts[first] == 0) {
			first++;
		}
		int last = counts.length - 1;
		while (counts[last] == 0) {
			last--;
		}
		List<Map<String, Object>> buckets = new ArrayList<>();
		for (int i = first; i <= last; i++) {
			Map<String, Object> bucket = new LinkedHashMap<>();
			bucket.put("fromMs", i == 0 ? 0 : BUCKET_UPPER_BOUNDS_MS[i - 1]);
			if (i < BUCKET_UPPER_BOUNDS_MS.length) {
				bucket.put("toMs", BUCKET_UPPER_BOUNDS_MS[i]);
			}
			bucket.put("count", counts[i]);
			buckets.add(bucket);
		}
		return buckets;
	}

	private static int bucketOf(double durationMs) {
		for (int i = 0; i < BUCKET_UPPER_BOUNDS_MS.length; i++) {
			if (durationMs < BUCKET_UPPER_BOUNDS_MS[i]) {
				return i;
			}
		}
		return BUCKET_UPPER_BOUNDS_MS.length;
	}

	private static double toMs(double ns) {
		return ns / 1_000_000.0;
	}

	/**
	 * Events shorter than the threshold are never recorded, so the histogram is cut off below it.
	 * If the threshold changed during the recording, the highest one is reported, since that is
	 * where the histogram is guaranteed to be complete.
	 */
	private static Double thresholdMs(IItemCollection items, String typeId) {
		Double highest = null;
		IItemCollection settings = items.apply(RulesToolkit.getSettingsFilter(THRESHOLD_SETTING, typeId));
		for (IItemIterable iterable : settings) {
			IMemberAccessor<String, IItem> accessor = JdkAttributes.REC_SETTING_VALUE.getAccessor(iterable.getType());
			if (accessor == null) {
				continue;
			}
			for (IItem item : iterable) {
				String value = accessor.getMember(item);
				if (value == null) {
					continue;
				}
				try {
					double ms = UnitLookup.TIMESPAN.parsePersisted(value).doubleValueIn(UnitLookup.MILLISECOND);
					if (highest == null || ms > highest) {
						highest = ms;
					}
				} catch (QuantityConversionException e) {
					// Not a timespan, e.g. a custom setting value - leave the threshold unreported.
				}
			}
		}
		return highest;
	}

	private static void putIfPresent(Map<String, Object> map, String key, Object value) {
		if (value != null) {
			map.put(key, value);
		}
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
