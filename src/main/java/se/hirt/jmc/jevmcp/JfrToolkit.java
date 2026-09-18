/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import org.openjdk.jmc.common.IDisplayable;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.JfrAttributes;

/**
 * Small formatting and lookup helpers shared by the tools.
 */
public final class JfrToolkit {

	private JfrToolkit() {
	}

	/**
	 * Returns the earliest start time across all events, used as the zero point for
	 * seconds-from-recording-start parameters.
	 */
	public static IQuantity getRecordingStart(IItemCollection items) {
		IQuantity earliest = null;
		for (IItemIterable iterable : items) {
			IMemberAccessor<IQuantity, IItem> accessor = JfrAttributes.START_TIME.getAccessor(iterable.getType());
			if (accessor == null) {
				continue;
			}
			for (IItem item : iterable) {
				IQuantity start = accessor.getMember(item);
				if (start != null && (earliest == null || start.compareTo(earliest) < 0)) {
					earliest = start;
				}
			}
		}
		return earliest;
	}

	public static IQuantity getRecordingEnd(IItemCollection items) {
		IQuantity latest = null;
		for (IItemIterable iterable : items) {
			IMemberAccessor<IQuantity, IItem> accessor = JfrAttributes.END_TIME.getAccessor(iterable.getType());
			if (accessor == null) {
				continue;
			}
			for (IItem item : iterable) {
				IQuantity end = accessor.getMember(item);
				if (end != null && (latest == null || end.compareTo(latest) > 0)) {
					latest = end;
				}
			}
		}
		return latest;
	}

	public static String formatQuantity(IQuantity value) {
		return value != null ? value.displayUsing(IDisplayable.AUTO) : "N/A";
	}

	/**
	 * Describes an exception for the tools' error responses, falling back to the exception's class
	 * name when it carries no message.
	 */
	public static String describeError(Exception e) {
		String message = e.getMessage();
		return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
	}
}
