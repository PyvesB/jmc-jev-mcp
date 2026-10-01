/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openjdk.jmc.common.item.IAccessorKey;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.IType;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.IUnit;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrAttributes;

/**
 * The container's CPU and memory limits and usage, from the JDK's container events (JDK 17+, Linux
 * only, and only when the JVM detects that it runs in a container).
 */
final class ContainerMetrics {

	static final String CONFIGURATION = "jdk.ContainerConfiguration";
	static final String CPU_USAGE = "jdk.ContainerCPUUsage";
	static final String CPU_THROTTLING = "jdk.ContainerCPUThrottling";
	static final String MEMORY_USAGE = "jdk.ContainerMemoryUsage";

	/**
	 * A field of a container event, with the unit the JDK declares for it. JMC has no constants for
	 * these events, and its parser does not turn every unit annotation into a quantity -
	 * microsecond timespans and plain counters come through as raw numbers - so fields are looked
	 * up by name and read as either, with a raw number taken to be in the declared unit.
	 */
	record Field(String name, IUnit unit) {
	}

	static final Field CONTAINER_TYPE = new Field("containerType", null);
	static final Field CPU_SLICE_PERIOD = new Field("cpuSlicePeriod", UnitLookup.MICROSECOND);
	static final Field CPU_QUOTA = new Field("cpuQuota", UnitLookup.MICROSECOND);
	static final Field CPU_SHARES = new Field("cpuShares", null);
	static final Field EFFECTIVE_CPU_COUNT = new Field("effectiveCpuCount", null);
	static final Field MEMORY_SOFT_LIMIT = new Field("memorySoftLimit", UnitLookup.BYTE);
	static final Field MEMORY_LIMIT = new Field("memoryLimit", UnitLookup.BYTE);
	static final Field SWAP_MEMORY_LIMIT = new Field("swapMemoryLimit", UnitLookup.BYTE);
	static final Field HOST_TOTAL_MEMORY = new Field("hostTotalMemory", UnitLookup.BYTE);
	static final Field CPU_TIME = new Field("cpuTime", UnitLookup.NANOSECOND);
	static final Field CPU_ELAPSED_SLICES = new Field("cpuElapsedSlices", null);
	static final Field CPU_THROTTLED_SLICES = new Field("cpuThrottledSlices", null);
	static final Field CPU_THROTTLED_TIME = new Field("cpuThrottledTime", UnitLookup.NANOSECOND);
	static final Field MEMORY_USED = new Field("memoryUsage", UnitLookup.BYTE);
	static final Field MEMORY_FAIL_COUNT = new Field("memoryFailCount", null);

	/**
	 * A field's value at the end time of its event, in nanoseconds since the epoch.
	 */
	record Sample(long timeNs, double value) {
	}

	private static final double MB = 1024.0 * 1024.0;

	private ContainerMetrics() {
	}

	/**
	 * Limits the container runtime reports as unlimited come through as -1 and are left out, so a
	 * missing limit means "not limited" when {@code containerType} is present.
	 */
	static Map<String, Object> compute(IItemCollection items) {
		Map<String, Object> container = new LinkedHashMap<>();
		String containerType = firstText(items, CONFIGURATION, CONTAINER_TYPE);
		if (containerType != null) {
			container.put("containerType", containerType);
		}

		Double quota = first(items, CONFIGURATION, CPU_QUOTA);
		Double period = first(items, CONFIGURATION, CPU_SLICE_PERIOD);
		if (isPositive(quota) && isPositive(period)) {
			container.put("cpuLimitCores", round(quota / period));
		}
		Double shares = first(items, CONFIGURATION, CPU_SHARES);
		if (isPositive(shares)) {
			container.put("cpuShares", shares.longValue());
		}
		Double effectiveCpuCount = first(items, CONFIGURATION, EFFECTIVE_CPU_COUNT);
		if (isPositive(effectiveCpuCount)) {
			container.put("effectiveCpuCount", effectiveCpuCount.longValue());
		}
		putPositiveMb(container, "memoryLimitMb", first(items, CONFIGURATION, MEMORY_LIMIT));
		putPositiveMb(container, "memorySoftLimitMb", first(items, CONFIGURATION, MEMORY_SOFT_LIMIT));
		putPositiveMb(container, "swapMemoryLimitMb", first(items, CONFIGURATION, SWAP_MEMORY_LIMIT));
		putPositiveMb(container, "hostTotalMemoryMb", first(items, CONFIGURATION, HOST_TOTAL_MEMORY));

		throttling(items, container);

		List<Sample> failCounts = samples(items, MEMORY_USAGE, MEMORY_FAIL_COUNT);
		if (failCounts.size() >= 2) {
			container.put("memoryFailCountIncrease",
					(long) (failCounts.get(failCounts.size() - 1).value() - failCounts.get(0).value()));
		}
		return container;
	}

	/**
	 * The throttling counters are cumulative since the container started, so the recording's own
	 * share is the difference between its first and last sample. With the default 30 s period a
	 * short recording may have only one sample, in which case only the since-start ratio is known.
	 */
	private static void throttling(IItemCollection items, Map<String, Object> container) {
		List<Sample> elapsed = samples(items, CPU_THROTTLING, CPU_ELAPSED_SLICES);
		List<Sample> throttled = samples(items, CPU_THROTTLING, CPU_THROTTLED_SLICES);
		if (elapsed.isEmpty() || elapsed.size() != throttled.size()) {
			return;
		}
		double lastElapsed = elapsed.get(elapsed.size() - 1).value();
		double lastThrottled = throttled.get(throttled.size() - 1).value();
		if (lastElapsed > 0) {
			container.put("cpuThrottledPctSinceContainerStart", round(lastThrottled / lastElapsed * 100));
		}
		if (elapsed.size() >= 2) {
			double elapsedDelta = lastElapsed - elapsed.get(0).value();
			if (elapsedDelta > 0) {
				container.put("cpuThrottledPctDuringRecording",
						round((lastThrottled - throttled.get(0).value()) / elapsedDelta * 100));
			}
		}
		List<Sample> throttledTime = samples(items, CPU_THROTTLING, CPU_THROTTLED_TIME);
		if (throttledTime.size() >= 2) {
			container.put("cpuThrottledTimeDuringRecordingMs",
					round((throttledTime.get(throttledTime.size() - 1).value() - throttledTime.get(0).value()) / 1e6));
		}
	}

	/**
	 * All values of a numeric field, sorted by time.
	 */
	static List<Sample> samples(IItemCollection items, String typeId, Field field) {
		List<Sample> samples = new ArrayList<>();
		for (IItemIterable iterable : items.apply(ItemFilters.type(typeId))) {
			IMemberAccessor<IQuantity, IItem> time = JfrAttributes.END_TIME.getAccessor(iterable.getType());
			IMemberAccessor<?, IItem> value = accessor(iterable.getType(), field.name());
			if (time == null || value == null) {
				continue;
			}
			for (IItem item : iterable) {
				IQuantity t = time.getMember(item);
				Double v = toDouble(value.getMember(item), field.unit());
				if (t != null && v != null) {
					samples.add(new Sample(t.clampedLongValueIn(UnitLookup.EPOCH_NS), v));
				}
			}
		}
		samples.sort(Comparator.comparingLong(Sample::timeNs));
		return samples;
	}

	private static Double first(IItemCollection items, String typeId, Field field) {
		List<Sample> samples = samples(items, typeId, field);
		return samples.isEmpty() ? null : samples.get(0).value();
	}

	private static String firstText(IItemCollection items, String typeId, Field field) {
		for (IItemIterable iterable : items.apply(ItemFilters.type(typeId))) {
			IMemberAccessor<?, IItem> accessor = accessor(iterable.getType(), field.name());
			if (accessor == null) {
				continue;
			}
			for (IItem item : iterable) {
				Object value = accessor.getMember(item);
				if (value != null) {
					return value.toString();
				}
			}
		}
		return null;
	}

	private static IMemberAccessor<?, IItem> accessor(IType<IItem> type, String name) {
		for (IAccessorKey<?> key : type.getAccessorKeys().keySet()) {
			if (key.getIdentifier().equals(name)) {
				return type.getAccessor(key);
			}
		}
		return null;
	}

	private static Double toDouble(Object value, IUnit unit) {
		if (value instanceof IQuantity quantity) {
			return unit != null ? quantity.doubleValueIn(unit) : quantity.doubleValue();
		}
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		return null;
	}

	private static boolean isPositive(Double value) {
		return value != null && value > 0;
	}

	private static void putPositiveMb(Map<String, Object> map, String key, Double bytes) {
		if (isPositive(bytes)) {
			map.put(key, round(bytes / MB));
		}
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
