/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jdk.jfr.DataAmount;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.Timespan;

import org.openjdk.jmc.common.IDescribable;
import org.openjdk.jmc.common.item.IAccessorKey;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.ICanonicalAccessorFactory;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.IType;
import org.openjdk.jmc.common.item.ItemCollectionToolkit;
import org.openjdk.jmc.common.item.ItemIterableToolkit;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;

/**
 * Generates {@code recordings/container-synthetic.jfr}: user-defined events that mirror the field
 * layout of the JDK's own container events, with known values, so the container metrics can be
 * tested without running in a container. Run {@link #main} by hand to regenerate the resource, on a
 * JVM that is not containerized (e.g. on macOS) so the JDK does not emit its own container events
 * into the same file:
 *
 * <pre>
 * java -cp target/test-classes se.hirt.jmc.jevmcp.SyntheticContainerRecording src/test/resources/recordings/container-synthetic.jfr
 * </pre>
 */
public final class SyntheticContainerRecording {

	@Name("jdk.ContainerConfiguration")
	static class ContainerConfiguration extends Event {
		String containerType;
		@Timespan(Timespan.MICROSECONDS)
		long cpuSlicePeriod;
		@Timespan(Timespan.MICROSECONDS)
		long cpuQuota;
		long cpuShares;
		long effectiveCpuCount;
		@DataAmount
		long memorySoftLimit;
		@DataAmount
		long memoryLimit;
		@DataAmount
		long swapMemoryLimit;
		@DataAmount
		long hostTotalMemory;
	}

	@Name("jdk.ContainerCPUThrottling")
	static class ContainerCPUThrottling extends Event {
		long cpuElapsedSlices;
		long cpuThrottledSlices;
		@Timespan(Timespan.NANOSECONDS)
		long cpuThrottledTime;
	}

	@Name("jdk.ContainerCPUUsage")
	static class ContainerCPUUsage extends Event {
		@Timespan(Timespan.NANOSECONDS)
		long cpuTime;
	}

	@Name("jdk.ContainerMemoryUsage")
	static class ContainerMemoryUsage extends Event {
		long memoryFailCount;
		@DataAmount
		long memoryUsage;
	}

	private static final long MB = 1024 * 1024;

	private SyntheticContainerRecording() {
	}

	/**
	 * Loads the recording, with the synthetic events under the JDK's own type names. Every
	 * recording carries metadata for the JDK's built-in container events too, so JMC's parser sees
	 * two types per name and gives the synthetic one a unique suffix; this strips it again. The
	 * values still come from JMC's parser, so its unit handling is exercised just as for real
	 * container events.
	 */
	static IItemCollection load() throws Exception {
		IItemCollection items = JfrLoaderToolkit.loadEvents(TestRecordings.containerSynthetic());
		List<IItemIterable> renamed = new ArrayList<>();
		for (IItemIterable iterable : items) {
			IType<IItem> type = iterable.getType();
			String id = type.getIdentifier();
			String name = SYNTHETIC_TYPES.stream().filter(t -> id.startsWith(t) && id.length() > t.length()).findFirst()
					.orElse(null);
			renamed.add(
					name == null ? iterable : ItemIterableToolkit.build(iterable::stream, new RenamedType(type, name)));
		}
		return ItemCollectionToolkit.build(renamed::stream);
	}

	private static final List<String> SYNTHETIC_TYPES = List.of("jdk.ContainerConfiguration",
			"jdk.ContainerCPUThrottling", "jdk.ContainerCPUUsage", "jdk.ContainerMemoryUsage");

	private record RenamedType(IType<IItem> delegate, String identifier) implements IType<IItem> {
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
		public <M> IMemberAccessor<M, IItem> getAccessor(IAccessorKey<M> key) {
			return delegate.getAccessor(key);
		}

		@Override
		public String getIdentifier() {
			return identifier;
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

	public static void main(String[] args) throws Exception {
		try (Recording recording = new Recording()) {
			recording.enable(ContainerConfiguration.class).withoutStackTrace();
			recording.enable(ContainerCPUThrottling.class).withoutStackTrace();
			recording.enable(ContainerCPUUsage.class).withoutStackTrace();
			recording.enable(ContainerMemoryUsage.class).withoutStackTrace();
			recording.start();

			ContainerConfiguration configuration = new ContainerConfiguration();
			configuration.containerType = "cgroupv2";
			configuration.cpuSlicePeriod = 100_000;
			configuration.cpuQuota = 200_000;
			configuration.cpuShares = -1;
			configuration.effectiveCpuCount = 2;
			configuration.memorySoftLimit = -1;
			configuration.memoryLimit = 1024 * MB;
			configuration.swapMemoryLimit = 2048 * MB;
			configuration.hostTotalMemory = 65536 * MB;
			configuration.commit();

			// Cumulative counters, as the JDK reports them: 1000 slices elapsed and 100 throttled
			// before the recording, then 100 more elapsed and 50 throttled per sample.
			for (int i = 0; i < 3; i++) {
				ContainerCPUThrottling throttling = new ContainerCPUThrottling();
				throttling.cpuElapsedSlices = 1000 + 100 * i;
				throttling.cpuThrottledSlices = 100 + 50 * i;
				throttling.cpuThrottledTime = 1_000_000_000L + 250_000_000L * i;
				throttling.commit();

				ContainerCPUUsage usage = new ContainerCPUUsage();
				usage.cpuTime = 10_000_000_000L + 500_000_000L * i;
				usage.commit();

				ContainerMemoryUsage memory = new ContainerMemoryUsage();
				memory.memoryUsage = (500 + 100 * i) * MB;
				memory.memoryFailCount = i == 2 ? 2 : 0;
				memory.commit();

				Thread.sleep(250);
			}
			recording.stop();
			recording.dump(Path.of(args[0]));
		}
	}
}
