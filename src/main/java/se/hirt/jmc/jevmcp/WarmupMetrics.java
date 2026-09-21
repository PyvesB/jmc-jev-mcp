/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.util.LinkedHashMap;
import java.util.Map;

import org.openjdk.jmc.common.item.Aggregators;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkTypeIDs;

/**
 * A fixed set of metrics computed from a recording, used as the {@code state} for the "still
 * warming up" Jev question folded into classifyWorkloadProfile. A JVM that is still warming up
 * tends to start recently before (or during) the recording, load classes at a high rate, and
 * spin up threads as subsystems initialize.
 */
final class WarmupMetrics {

	final Double jvmUptimeAtRecordingStartSeconds;
	final long classLoadCount;
	final double classLoadRatePerSecond;
	final long compilationEventCount;
	final long threadStartCount;

	private WarmupMetrics(
		Double jvmUptimeAtRecordingStartSeconds, long classLoadCount, double classLoadRatePerSecond,
		long compilationEventCount, long threadStartCount) {
		this.jvmUptimeAtRecordingStartSeconds = jvmUptimeAtRecordingStartSeconds;
		this.classLoadCount = classLoadCount;
		this.classLoadRatePerSecond = classLoadRatePerSecond;
		this.compilationEventCount = compilationEventCount;
		this.threadStartCount = threadStartCount;
	}

	static WarmupMetrics compute(IItemCollection items, IQuantity start, IQuantity end) {
		double durationSeconds = (start != null && end != null)
				? end.subtract(start).doubleValueIn(UnitLookup.SECOND) : 0;

		IQuantity jvmStartTime = firstValue(items, JdkTypeIDs.VM_INFO, JdkAttributes.JVM_START_TIME);
		Double jvmUptimeAtRecordingStartSeconds = (jvmStartTime != null && start != null)
				? start.subtract(jvmStartTime).doubleValueIn(UnitLookup.SECOND) : null;

		long classLoadCount = countOf(items.apply(ItemFilters.type(JdkTypeIDs.CLASS_LOAD)));
		double classLoadRatePerSecond = durationSeconds > 0 ? classLoadCount / durationSeconds : 0;

		long compilationEventCount = countOf(items.apply(ItemFilters.type(JdkTypeIDs.COMPILATION)));
		long threadStartCount = countOf(items.apply(ItemFilters.type(JdkTypeIDs.JAVA_THREAD_START)));

		return new WarmupMetrics(jvmUptimeAtRecordingStartSeconds, classLoadCount, classLoadRatePerSecond,
				compilationEventCount, threadStartCount);
	}

	/**
	 * The metrics as a plain map, ready to drop into a Jev request's {@code state}.
	 */
	Map<String, Object> toStateMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		if (jvmUptimeAtRecordingStartSeconds != null) {
			map.put("jvmUptimeAtRecordingStartSeconds", round(jvmUptimeAtRecordingStartSeconds));
		}
		map.put("classLoadCount", classLoadCount);
		map.put("classLoadRatePerSecond", round(classLoadRatePerSecond));
		map.put("compilationEventCount", compilationEventCount);
		map.put("threadStartCount", threadStartCount);
		return map;
	}

	private static IQuantity firstValue(IItemCollection items, String typeId, IAttribute<IQuantity> attribute) {
		for (IItemIterable iterable : items.apply(ItemFilters.type(typeId))) {
			IMemberAccessor<IQuantity, IItem> accessor = attribute.getAccessor(iterable.getType());
			if (accessor == null) {
				continue;
			}
			for (IItem item : iterable) {
				IQuantity value = accessor.getMember(item);
				if (value != null) {
					return value;
				}
			}
		}
		return null;
	}

	private static long countOf(IItemCollection items) {
		IQuantity count = items.getAggregate(Aggregators.count());
		return count != null ? count.longValue() : 0;
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
