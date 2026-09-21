/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.util.LinkedHashMap;
import java.util.Map;

import org.openjdk.jmc.common.item.Aggregators;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkTypeIDs;

/**
 * A fixed set of metrics computed from a recording, used as the {@code state} for the
 * classifyWorkloadProfile Jev question. Kept deliberately small: every number here is something a
 * human doing GC/allocation tuning would look at first.
 */
final class WorkloadMetrics {

	final double durationSeconds;
	final long gcCount;
	final double gcPauseOverheadPct;
	final double maxPauseMs;
	final double allocationRateMbPerSec;
	final Double avgCpuLoadPct;
	final Map<String, String> eventAvailability;

	private WorkloadMetrics(double durationSeconds, long gcCount, double gcPauseOverheadPct, double maxPauseMs,
			double allocationRateMbPerSec, Double avgCpuLoadPct, Map<String, String> eventAvailability) {
		this.durationSeconds = durationSeconds;
		this.gcCount = gcCount;
		this.gcPauseOverheadPct = gcPauseOverheadPct;
		this.maxPauseMs = maxPauseMs;
		this.allocationRateMbPerSec = allocationRateMbPerSec;
		this.avgCpuLoadPct = avgCpuLoadPct;
		this.eventAvailability = eventAvailability;
	}

	static WorkloadMetrics compute(IItemCollection items, IQuantity start, IQuantity end) {
		double durationSeconds = (start != null && end != null) ? end.subtract(start).doubleValueIn(UnitLookup.SECOND)
				: 0;

		IItemCollection gcEvents = items.apply(ItemFilters.type(JdkTypeIDs.GARBAGE_COLLECTION));
		long gcCount = countOf(gcEvents);
		double totalPauseMs = quantityToMillis(gcEvents.getAggregate(Aggregators.sum(JdkAttributes.GC_SUM_OF_PAUSES)));
		double maxPauseMs = quantityToMillis(gcEvents.getAggregate(Aggregators.max(JdkAttributes.GC_LONGEST_PAUSE)));
		double gcPauseOverheadPct = durationSeconds > 0 ? (totalPauseMs / 1000.0) / durationSeconds * 100.0 : 0;

		IItemCollection allocEvents = items
				.apply(ItemFilters.type(JdkTypeIDs.ALLOC_INSIDE_TLAB, JdkTypeIDs.ALLOC_OUTSIDE_TLAB));
		double totalAllocatedBytes = quantityToBytes(
				allocEvents.getAggregate(Aggregators.sum(JdkAttributes.ALLOCATION_SIZE)));
		double allocationRateMbPerSec = durationSeconds > 0
				? (totalAllocatedBytes / (1024.0 * 1024.0)) / durationSeconds : 0;

		IItemCollection cpuEvents = items.apply(ItemFilters.type(JdkTypeIDs.CPU_LOAD));
		IQuantity avgCpu = cpuEvents.hasItems() ? cpuEvents.getAggregate(Aggregators.avg(JdkAttributes.JVM_TOTAL))
				: null;
		Double avgCpuLoadPct = avgCpu != null ? avgCpu.doubleValueIn(UnitLookup.PERCENT) : null;

		Map<String, String> eventAvailability = JfrToolkit.eventAvailability(items, JdkTypeIDs.GARBAGE_COLLECTION,
				JdkTypeIDs.ALLOC_INSIDE_TLAB, JdkTypeIDs.ALLOC_OUTSIDE_TLAB, JdkTypeIDs.CPU_LOAD);

		return new WorkloadMetrics(durationSeconds, gcCount, gcPauseOverheadPct, maxPauseMs, allocationRateMbPerSec,
				avgCpuLoadPct, eventAvailability);
	}

	/**
	 * The metrics as a plain map, ready to drop into a Jev request's {@code state}.
	 */
	Map<String, Object> toStateMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("durationSeconds", round(durationSeconds));
		map.put("gcCount", gcCount);
		map.put("gcPauseOverheadPct", round(gcPauseOverheadPct));
		map.put("maxPauseMs", round(maxPauseMs));
		map.put("allocationRateMbPerSec", round(allocationRateMbPerSec));
		if (avgCpuLoadPct != null) {
			map.put("avgCpuLoadPct", round(avgCpuLoadPct));
		}
		map.put("eventAvailability", eventAvailability);
		return map;
	}

	private static long countOf(IItemCollection items) {
		IQuantity count = items.getAggregate(Aggregators.count());
		return count != null ? count.longValue() : 0;
	}

	private static double quantityToMillis(IQuantity quantity) {
		return quantity != null ? quantity.doubleValueIn(UnitLookup.MILLISECOND) : 0;
	}

	private static double quantityToBytes(IQuantity quantity) {
		return quantity != null ? quantity.doubleValueIn(UnitLookup.BYTE) : 0;
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
