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
 * The static resource budget the workload runs under - CPUs, heap, collectors, physical memory and,
 * when containerized, the container's CPU and memory limits. Without it, a CPU load or heap usage
 * figure cannot be judged: 90% of a two-CPU container quota is a different situation than 90% of a
 * 64-core host.
 */
final class EnvironmentMetrics {

	private static final double MB = 1024.0 * 1024.0;

	private EnvironmentMetrics() {
	}

	static Map<String, Object> compute(IItemCollection items) {
		Map<String, Object> environment = new LinkedHashMap<>();

		Map<String, Object> cpu = new LinkedHashMap<>();
		putNumber(cpu, "hwThreads", JfrToolkit.firstValue(items, JdkTypeIDs.CPU_INFORMATION, JdkAttributes.HW_THREADS));
		putNumber(cpu, "cores",
				JfrToolkit.firstValue(items, JdkTypeIDs.CPU_INFORMATION, JdkAttributes.NUMBER_OF_CORES));
		putNumber(cpu, "sockets",
				JfrToolkit.firstValue(items, JdkTypeIDs.CPU_INFORMATION, JdkAttributes.NUMBER_OF_SOCKETS));
		putIfNotEmpty(environment, "cpu", cpu);

		Map<String, Object> memory = new LinkedHashMap<>();
		putMb(memory, "maxHeapMb", JfrToolkit.firstValue(items, JdkTypeIDs.HEAP_CONF, JdkAttributes.HEAP_MAX_SIZE));
		putMb(memory, "initialHeapMb",
				JfrToolkit.firstValue(items, JdkTypeIDs.HEAP_CONF, JdkAttributes.HEAP_INITIAL_SIZE));
		putMb(memory, "physicalMemoryTotalMb",
				JfrToolkit.firstValue(items, JdkTypeIDs.OS_MEMORY_SUMMARY, JdkAttributes.OS_MEMORY_TOTAL));
		IItemCollection rss = items.apply(ItemFilters.type(JdkTypeIDs.RSS));
		if (rss.hasItems()) {
			putMb(memory, "rssPeakMb", rss.getAggregate(Aggregators.max(JdkAttributes.RSS_PEAK)));
		}
		putIfNotEmpty(environment, "memory", memory);

		Map<String, Object> gc = new LinkedHashMap<>();
		putIfPresent(gc, "youngCollector",
				JfrToolkit.firstValue(items, JdkTypeIDs.GC_CONF, JdkAttributes.YOUNG_COLLECTOR));
		putIfPresent(gc, "oldCollector", JfrToolkit.firstValue(items, JdkTypeIDs.GC_CONF, JdkAttributes.OLD_COLLECTOR));
		putIfNotEmpty(environment, "gc", gc);

		putIfNotEmpty(environment, "container", ContainerMetrics.compute(items));

		environment.put("eventAvailability", JfrToolkit.eventAvailability(items, JdkTypeIDs.CPU_INFORMATION,
				JdkTypeIDs.HEAP_CONF, JdkTypeIDs.GC_CONF, JdkTypeIDs.OS_MEMORY_SUMMARY, JdkTypeIDs.RSS,
				ContainerMetrics.CONFIGURATION, ContainerMetrics.CPU_THROTTLING, ContainerMetrics.MEMORY_USAGE));
		return environment;
	}

	private static void putNumber(Map<String, Object> map, String key, IQuantity value) {
		if (value != null) {
			map.put(key, value.longValue());
		}
	}

	private static void putMb(Map<String, Object> map, String key, IQuantity value) {
		if (value != null) {
			map.put(key, round(value.doubleValueIn(UnitLookup.BYTE) / MB));
		}
	}

	private static void putIfPresent(Map<String, Object> map, String key, Object value) {
		if (value != null) {
			map.put(key, value);
		}
	}

	private static void putIfNotEmpty(Map<String, Object> map, String key, Map<String, Object> value) {
		if (!value.isEmpty()) {
			map.put(key, value);
		}
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
