/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import jakarta.enterprise.context.ApplicationScoped;

import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.CouldNotLoadRecordingException;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.openjdk.jmc.flightrecorder.rules.IResult;
import org.openjdk.jmc.flightrecorder.rules.IRule;
import org.openjdk.jmc.flightrecorder.rules.RuleRegistry;
import org.openjdk.jmc.flightrecorder.rules.util.RulesToolkit;

/**
 * Holds JFR recordings loaded on demand by file path, along with the rule results computed from
 * them. Rule evaluation is cached per recording since it is the input both {@code getRuleResults}
 * and {@code classifyBiggestIssue} need, and is expensive enough to be worth computing once.
 */
@ApplicationScoped
public class RecordingService {

	/**
	 * A loaded recording and everything derived from it, computed lazily since a session may never
	 * ask for it.
	 */
	public static final class Recording {
		private final String id;
		private final IItemCollection items;

		private volatile Map<IRule, Future<IResult>> ruleResults;
		private volatile IQuantity start;
		private volatile IQuantity end;
		private volatile boolean boundsComputed;

		private Recording(String id, IItemCollection items) {
			this.id = id;
			this.items = items;
		}

		public String getId() {
			return id;
		}

		public IItemCollection getItems() {
			return items;
		}

		public IQuantity getStart() {
			computeBounds();
			return start;
		}

		public IQuantity getEnd() {
			computeBounds();
			return end;
		}

		private void computeBounds() {
			if (!boundsComputed) {
				synchronized (this) {
					if (!boundsComputed) {
						start = JfrToolkit.getRecordingStart(items);
						end = JfrToolkit.getRecordingEnd(items);
						boundsComputed = true;
					}
				}
			}
		}
	}

	private final Map<String, Recording> recordings = new ConcurrentHashMap<>();

	/**
	 * Loads and registers a recording. Loading a path that is already loaded returns the existing
	 * {@link Recording} rather than replacing it, so a repeated loadRecording call never discards
	 * cached rule results.
	 */
	public Recording load(String path) throws IOException, CouldNotLoadRecordingException {
		String id = canonicalize(path);
		Recording existing = recordings.get(id);
		if (existing != null) {
			return existing;
		}
		Recording recording = new Recording(id, JfrLoaderToolkit.loadEvents(new File(path)));
		Recording previous = recordings.putIfAbsent(id, recording);
		return previous != null ? previous : recording;
	}

	/**
	 * Recordings are keyed by canonical path so that a client can refer to one by any spelling of
	 * its path - relative, containing "..", or through a symlink - and still hit the same recording.
	 */
	private static String canonicalize(String path) {
		try {
			return new File(path).getCanonicalPath();
		} catch (IOException e) {
			return new File(path).getAbsoluteFile().toPath().normalize().toString();
		}
	}

	/**
	 * Resolves a recording id. When exactly one recording is loaded, a blank id resolves to it, so
	 * single-recording sessions do not have to thread the path through every call.
	 */
	public Recording get(String recordingId) {
		if (recordingId == null || recordingId.isBlank()) {
			if (recordings.size() == 1) {
				return recordings.values().iterator().next();
			}
			throw new IllegalArgumentException(
					recordings.isEmpty() ? "No recording is loaded. Call loadRecording with a JFR file path first."
							: "Several recordings are loaded - specify a recordingId. Loaded: " + listIds());
		}
		Recording recording = recordings.get(recordingId);
		if (recording == null) {
			recording = recordings.get(canonicalize(recordingId));
		}
		if (recording == null) {
			throw new IllegalArgumentException(
					"Unknown recording: " + recordingId + ". Loaded: " + listIds() + ". Call loadRecording first.");
		}
		return recording;
	}

	public List<String> listIds() {
		return List.copyOf(recordings.keySet());
	}

	public boolean unload(String recordingId) {
		Recording recording = recordings.get(recordingId);
		if (recording == null) {
			recording = recordings.get(canonicalize(recordingId));
		}
		return recording != null && recordings.remove(recording.getId()) != null;
	}

	/**
	 * Runs (and caches) the full set of built-in automated analysis rules against the recording.
	 */
	public Map<IRule, Future<IResult>> getRuleResults(Recording recording) {
		Map<IRule, Future<IResult>> results = recording.ruleResults;
		if (results == null) {
			synchronized (recording) {
				results = recording.ruleResults;
				if (results == null) {
					results = RulesToolkit.evaluateParallel(RuleRegistry.getRules(), recording.getItems(), null, 0);
					recording.ruleResults = results;
				}
			}
		}
		return results;
	}
}
