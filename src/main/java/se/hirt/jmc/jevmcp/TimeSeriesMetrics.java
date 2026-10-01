/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemFilter;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkFilters;
import org.openjdk.jmc.flightrecorder.jdk.JdkTypeIDs;

/**
 * The series JMC charts on its Java Application and Heap pages - CPU usage, thread counts, RSS,
 * heap and physical memory, allocation and GC pauses - plus class loading and container usage,
 * downsampled into a fixed number of equal time slices. Whole-recording averages hide trends: a
 * live set that keeps growing after each GC, or a CPU load that only settles halfway through.
 */
final class TimeSeriesMetrics {

	static final int SLICES = 20;

	private static final double MB = 1024.0 * 1024.0;

	private enum Aggregation {
		AVG, MIN, MAX, SUM
	}

	private TimeSeriesMetrics() {
	}

	static Map<String, Object> compute(IItemCollection items, IQuantity start, IQuantity end) {
		if (start == null || end == null || end.compareTo(start) <= 0) {
			return null;
		}
		Slicer slicer = new Slicer(start, end);
		Map<String, Object> series = new LinkedHashMap<>();

		IItemFilter cpuLoad = ItemFilters.type(JdkTypeIDs.CPU_LOAD);
		put(series, "machineTotalCpuPct",
				slicer.series(items, cpuLoad, JdkAttributes.MACHINE_TOTAL, TimeSeriesMetrics::pct, Aggregation.AVG));
		put(series, "jvmTotalCpuPct",
				slicer.series(items, cpuLoad, JdkAttributes.JVM_TOTAL, TimeSeriesMetrics::pct, Aggregation.AVG));
		put(series, "jvmSystemCpuPct",
				slicer.series(items, cpuLoad, JdkAttributes.JVM_SYSTEM, TimeSeriesMetrics::pct, Aggregation.AVG));

		IItemFilter threads = ItemFilters.type(JdkTypeIDs.THREAD_STATISTICS);
		put(series, "activeThreads", slicer.series(items, threads, JdkAttributes.THREADS_ACTIVE_COUNT,
				IQuantity::doubleValue, Aggregation.MAX));
		put(series, "daemonThreads", slicer.series(items, threads, JdkAttributes.THREADS_DAEMON_COUNT,
				IQuantity::doubleValue, Aggregation.MAX));

		put(series, "rssMb", slicer.series(items, ItemFilters.type(JdkTypeIDs.RSS), JdkAttributes.RSS_SIZE,
				TimeSeriesMetrics::mb, Aggregation.MAX));
		put(series, "heapUsedMb", slicer.series(items, JdkFilters.HEAP_SUMMARY, JdkAttributes.HEAP_USED,
				TimeSeriesMetrics::mb, Aggregation.MAX));
		put(series, "heapUsedAfterGcMb", slicer.series(items, JdkFilters.HEAP_SUMMARY_AFTER_GC, JdkAttributes.HEAP_USED,
				TimeSeriesMetrics::mb, Aggregation.MIN));
		put(series, "heapCommittedMb", slicer.series(items, JdkFilters.HEAP_SUMMARY,
				JdkAttributes.GC_HEAPSPACE_COMMITTED, TimeSeriesMetrics::mb, Aggregation.MAX));
		put(series, "physicalMemoryUsedMb", slicer.series(items, ItemFilters.type(JdkTypeIDs.OS_MEMORY_SUMMARY),
				JdkAttributes.OS_MEMORY_USED, TimeSeriesMetrics::mb, Aggregation.MAX));

		put(series, "allocationMbPerSec", allocationRate(items, slicer));
		put(series, "gcPauseMs", slicer.series(items, ItemFilters.type(JdkTypeIDs.GARBAGE_COLLECTION),
				JdkAttributes.GC_SUM_OF_PAUSES, TimeSeriesMetrics::ms, Aggregation.SUM));
		put(series, "loadedClassCount", slicer.series(items, ItemFilters.type(JdkTypeIDs.CLASS_LOAD_STATISTICS),
				JdkAttributes.CLASSLOADER_LOADED_COUNT, IQuantity::doubleValue, Aggregation.MAX));

		List<ContainerMetrics.Sample> memoryUsage = ContainerMetrics.samples(items, ContainerMetrics.MEMORY_USAGE,
				ContainerMetrics.MEMORY_USED);
		put(series, "containerMemoryUsageMb", slicer.maxSeries(memoryUsage, 1 / MB));
		put(series, "containerCpuUsageCores", slicer.rateSeries(
				ContainerMetrics.samples(items, ContainerMetrics.CPU_USAGE, ContainerMetrics.CPU_TIME), null, 1));
		put(series, "containerCpuThrottledPct", slicer.rateSeries(
				ContainerMetrics.samples(items, ContainerMetrics.CPU_THROTTLING, ContainerMetrics.CPU_THROTTLED_SLICES),
				ContainerMetrics.samples(items, ContainerMetrics.CPU_THROTTLING, ContainerMetrics.CPU_ELAPSED_SLICES),
				100));

		Map<String, Object> timeSeries = new LinkedHashMap<>();
		timeSeries.put("sliceCount", SLICES);
		timeSeries.put("sliceSeconds", round(slicer.sliceNs / 1e9));
		timeSeries.put("series", series);
		timeSeries.put("eventAvailability",
				JfrToolkit.eventAvailability(items, JdkTypeIDs.CPU_LOAD, JdkTypeIDs.THREAD_STATISTICS, JdkTypeIDs.RSS,
						JdkTypeIDs.HEAP_SUMMARY, JdkTypeIDs.OS_MEMORY_SUMMARY, JdkTypeIDs.GARBAGE_COLLECTION,
						JdkTypeIDs.CLASS_LOAD_STATISTICS, ContainerMetrics.MEMORY_USAGE, ContainerMetrics.CPU_USAGE,
						ContainerMetrics.CPU_THROTTLING));
		return timeSeries;
	}

	private static List<Double> allocationRate(IItemCollection items, Slicer slicer) {
		IItemFilter sampled = ItemFilters.type(JdkTypeIDs.OBJ_ALLOC_SAMPLE);
		List<Double> bytes = items.apply(sampled).hasItems()
				? slicer.series(items, sampled, JdkAttributes.SAMPLE_WEIGHT, TimeSeriesMetrics::mb, Aggregation.SUM)
				: slicer.series(items, JdkFilters.ALLOC_ALL, JdkAttributes.TOTAL_ALLOCATION_SIZE, TimeSeriesMetrics::mb,
						Aggregation.SUM);
		if (bytes == null) {
			return null;
		}
		double sliceSeconds = slicer.sliceNs / 1e9;
		List<Double> rate = new ArrayList<>();
		for (Double value : bytes) {
			rate.add(value == null ? null : round(value / sliceSeconds));
		}
		return rate;
	}

	private static final class Slicer {
		final long startNs;
		final double sliceNs;

		Slicer(IQuantity start, IQuantity end) {
			startNs = start.clampedLongValueIn(UnitLookup.EPOCH_NS);
			sliceNs = (end.clampedLongValueIn(UnitLookup.EPOCH_NS) - startNs) / (double) SLICES;
		}

		int sliceOf(IQuantity time) {
			return sliceOf(time.clampedLongValueIn(UnitLookup.EPOCH_NS));
		}

		int sliceOf(long timeNs) {
			int slice = (int) ((timeNs - startNs) / sliceNs);
			return Math.max(0, Math.min(SLICES - 1, slice));
		}

		/**
		 * One value per slice, with {@code null} for slices without events, or {@code null}
		 * altogether if there were no events at all.
		 */
		List<Double> series(
			IItemCollection items, IItemFilter filter, IAttribute<IQuantity> attribute,
			ToDoubleFunction<IQuantity> convert, Aggregation aggregation) {
			double[] values = new double[SLICES];
			long[] counts = new long[SLICES];
			boolean any = false;
			for (IItemIterable iterable : items.apply(filter)) {
				IMemberAccessor<IQuantity, IItem> time = JfrAttributes.END_TIME.getAccessor(iterable.getType());
				IMemberAccessor<IQuantity, IItem> value = attribute.getAccessor(iterable.getType());
				if (time == null || value == null) {
					continue;
				}
				for (IItem item : iterable) {
					IQuantity t = time.getMember(item);
					IQuantity v = value.getMember(item);
					if (t == null || v == null) {
						continue;
					}
					int slice = sliceOf(t);
					double converted = convert.applyAsDouble(v);
					values[slice] = counts[slice] == 0 ? converted : switch (aggregation) {
					case MIN -> Math.min(values[slice], converted);
					case MAX -> Math.max(values[slice], converted);
					case AVG, SUM -> values[slice] + converted;
					};
					counts[slice]++;
					any = true;
				}
			}
			if (!any) {
				return null;
			}
			List<Double> result = new ArrayList<>(SLICES);
			for (int i = 0; i < SLICES; i++) {
				if (counts[i] == 0) {
					result.add(aggregation == Aggregation.SUM ? 0.0 : null);
				} else {
					result.add(round(aggregation == Aggregation.AVG ? values[i] / counts[i] : values[i]));
				}
			}
			return result;
		}

		List<Double> maxSeries(List<ContainerMetrics.Sample> samples, double scale) {
			if (samples.isEmpty()) {
				return null;
			}
			Double[] result = new Double[SLICES];
			for (ContainerMetrics.Sample sample : samples) {
				int slice = sliceOf(sample.timeNs());
				double value = sample.value() * scale;
				result[slice] = result[slice] == null ? value : Math.max(result[slice], value);
			}
			for (int i = 0; i < SLICES; i++) {
				result[i] = result[i] == null ? null : round(result[i]);
			}
			return Arrays.asList(result);
		}

		/**
		 * For cumulative counters: the change between consecutive samples, divided by the change in
		 * {@code denominators} (or in wall time when that is {@code null}), credited to the slice
		 * of the later sample. Slices without a sample pair are {@code null}.
		 */
		List<Double> rateSeries(
			List<ContainerMetrics.Sample> numerators, List<ContainerMetrics.Sample> denominators, double scale) {
			if (numerators.size() < 2 || (denominators != null && denominators.size() != numerators.size())) {
				return null;
			}
			double[] num = new double[SLICES];
			double[] den = new double[SLICES];
			for (int i = 1; i < numerators.size(); i++) {
				int slice = sliceOf(numerators.get(i).timeNs());
				num[slice] += numerators.get(i).value() - numerators.get(i - 1).value();
				den[slice] += denominators != null ? denominators.get(i).value() - denominators.get(i - 1).value()
						: numerators.get(i).timeNs() - numerators.get(i - 1).timeNs();
			}
			Double[] result = new Double[SLICES];
			for (int i = 0; i < SLICES; i++) {
				result[i] = den[i] > 0 ? round(num[i] / den[i] * scale) : null;
			}
			return Arrays.asList(result);
		}
	}

	private static void put(Map<String, Object> map, String key, List<Double> series) {
		if (series != null) {
			map.put(key, series);
		}
	}

	private static double pct(IQuantity value) {
		return value.doubleValueIn(UnitLookup.PERCENT);
	}

	private static double mb(IQuantity value) {
		return value.doubleValueIn(UnitLookup.BYTE) / MB;
	}

	private static double ms(IQuantity value) {
		return value.doubleValueIn(UnitLookup.MILLISECOND);
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
