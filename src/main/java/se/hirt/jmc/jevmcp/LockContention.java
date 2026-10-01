/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openjdk.jmc.common.IMCType;
import org.openjdk.jmc.common.item.Attribute;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkTypeIDs;

/**
 * What threads are blocking on, as opposed to {@link DurationHistograms}' how long. One monitor
 * class with a single instance is a global lock; the same blocked time spread across many classes
 * or instances is a different problem. Instances are only approximated by distinct addresses: a
 * moving GC relocates park blockers, and a deflated and reinflated monitor gets a new address, so
 * the count is an upper bound.
 */
final class LockContention {

	static final int TOP_LOCKS = 10;

	/**
	 * Not in JMC's JdkAttributes. The class of the object passed to LockSupport.park as blocker,
	 * which tells lock contention (e.g. ReentrantLock$NonfairSync) apart from idle threads waiting
	 * for work (e.g. a queue's AbstractQueuedSynchronizer$ConditionObject).
	 */
	private static final IAttribute<IMCType> PARKED_CLASS = Attribute.attr("parkedClass", "Class Parked On",
			UnitLookup.CLASS);
	private static final IAttribute<IQuantity> PARK_ADDRESS = Attribute.attr("address", "Address of Object Parked",
			UnitLookup.ADDRESS);

	private LockContention() {
	}

	static Map<String, Object> compute(IItemCollection items) {
		Map<String, Object> contention = new LinkedHashMap<>();
		List<Map<String, Object>> monitors = DurationHistograms.topByTotalDuration(
				items.apply(ItemFilters.type(JdkTypeIDs.MONITOR_ENTER)), JdkAttributes.MONITOR_CLASS, "monitorClass",
				JdkAttributes.MONITOR_ADDRESS, "distinctAddresses", TOP_LOCKS);
		if (!monitors.isEmpty()) {
			contention.put("monitorEnter", monitors);
		}
		List<Map<String, Object>> parked = DurationHistograms.topByTotalDuration(
				items.apply(ItemFilters.type(JdkTypeIDs.THREAD_PARK)), PARKED_CLASS, "parkedClass", PARK_ADDRESS,
				"distinctAddresses", TOP_LOCKS);
		if (!parked.isEmpty()) {
			contention.put("threadPark", parked);
		}
		return contention;
	}
}
