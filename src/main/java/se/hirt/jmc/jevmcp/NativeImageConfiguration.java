/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native image reflection registrations.
 * <p>
 * The JFR parser materializes the structured values in a recording - threads, classes, methods,
 * stack frames - with {@code Class.newInstance()} followed by {@code Field.set()} (see
 * {@code ValueReaders.ReflectiveReader} in org.openjdk.jmc.flightrecorder). Native image has no way
 * to see those types are needed, so without this registration parsing any recording fails with
 * {@code InstantiationException}. Both the no-arg constructors and the declared fields have to be
 * reachable, hence fields = true.
 */
@RegisterForReflection(fields = true, classNames = {
		"org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes$JfrThread",
		"org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes$JfrThreadGroup",
		"org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes$JfrJavaPackage",
		"org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes$JfrJavaModule",
		"org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes$JfrJavaClassLoader",
		"org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes$JfrJavaClass",
		"org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes$JfrOldObjectGcRoot",
		"org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes$JfrOldObject",
		"org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes$JfrOldObjectArray",
		"org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes$JfrOldObjectField",
		"org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes$JfrMethod",
		"org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes$JfrFrame",
		"org.openjdk.jmc.flightrecorder.internal.parser.v1.StructTypes$JfrStackTrace"})
public final class NativeImageConfiguration {

	private NativeImageConfiguration() {
	}
}
