/*
 * Copyright (c) 2018-2021 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.test.reactor_original;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utilities around manipulating stack traces and displaying assembly traces.
 *
 * @author Simon Baslé
 * @author Sergei Egorov
 */
final class Traces {

	/**
	 * If set to true, the creation of FluxOnAssembly will capture the raw stacktrace
	 * instead of the sanitized version.
	 */
	static final boolean full = Boolean.parseBoolean(System.getProperty(
			"reactor.trace.assembly.fullstacktrace",
			"false"));

	static final String CALL_SITE_GLUE = " ⇢ ";

	/**
	 * Transform the current stack trace into a {@link String} representation,
	 * each element being prepended with a tabulation and appended with a
	 * newline.
	 */
	static Supplier<Supplier<String>> callSiteSupplierFactory;

	static {
		String[] strategyClasses = {
				Traces.class.getName() + "$ExceptionCallSiteSupplierFactory",
		};
		// find one available call-site supplier w.r.t. the jdk version to provide
		// linkage-compatibility between jdk 8 and 9+
		callSiteSupplierFactory = Stream
				.of(strategyClasses)
				.flatMap(className -> {
					try {
						Class<?> clazz = Class.forName(className);
						@SuppressWarnings("unchecked")
						Supplier<Supplier<String>> function = (Supplier) clazz.getDeclaredConstructor()
						                                                      .newInstance();
						return Stream.of(function);
					}
					// explicitly catch LinkageError to support static code analysis
					// tools detect the attempt at finding out jdk environment
					catch (LinkageError e) {
						return Stream.empty();
					}
					catch (Throwable e) {
						return Stream.empty();
					}
				})
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Valid strategy not found"));
	}
	
	@SuppressWarnings("unused")
	static class ExceptionCallSiteSupplierFactory implements Supplier<Supplier<String>> {

		@Override
		public Supplier<String> get() {
			return new TracingException();
		}

		static class TracingException extends Throwable implements Supplier<String> {

			@Override
			public String get() {
				StackTraceElement previousElement = null;
				StackTraceElement[] stackTrace = getStackTrace();
				// Skip get()
				for (int i = 2; i < stackTrace.length; i++) {
					StackTraceElement e = stackTrace[i];

					String className = e.getClassName();
					if (isUserCode(className)) {
						StringBuilder sb = new StringBuilder();

						if (previousElement != null) {
							sb.append("\t").append(previousElement.toString()).append("\n");
						}
						sb.append("\t").append(e.toString()).append("\n");
						return sb.toString();
					}
					else {
						if (!full && e.getLineNumber() <= 1) {
							continue;
						}

						String classAndMethod = className + "." + e.getMethodName();
						if (!full && shouldSanitize(classAndMethod)) {
							continue;
						}
						previousElement = e;
					}
				}

				return "";
			}
		}
	}

	/**
	 * Return true for strings (usually from a stack trace element) that should be
	 * sanitized out by {@link Traces#callSiteSupplierFactory}.
	 *
	 * @param stackTraceRow the row to check
	 * @return true if it should be sanitized out, false if it should be kept
	 */
	static boolean shouldSanitize(String stackTraceRow) {
		return stackTraceRow.startsWith("java.util.function")
				|| stackTraceRow.startsWith("publisher.Mono.onAssembly")
				|| stackTraceRow.equals("publisher.Mono.onAssembly")
				|| stackTraceRow.equals("publisher.Flux.onAssembly")
				|| stackTraceRow.equals("publisher.ParallelFlux.onAssembly")
				|| stackTraceRow.startsWith("publisher.SignalLogger")
				|| stackTraceRow.startsWith("publisher.FluxOnAssembly")
				|| stackTraceRow.startsWith("publisher.MonoOnAssembly.")
				|| stackTraceRow.startsWith("publisher.MonoCallableOnAssembly.")
				|| stackTraceRow.startsWith("publisher.FluxCallableOnAssembly.")
				|| stackTraceRow.startsWith("publisher.Hooks")
				|| stackTraceRow.startsWith("sun.reflect")
				|| stackTraceRow.startsWith("java.util.concurrent.ThreadPoolExecutor")
				|| stackTraceRow.startsWith("java.lang.reflect");
	}

	/**
	 * Extract operator information out of an assembly stack trace in {@link String} form
	 * (see {@link Traces#callSiteSupplierFactory}).
	 * <p>
	 * Most operators will result in a line of the form {@code "Flux.map ⇢ user.code.Class.method(Class.java:123)"},
	 * that is:
	 * <ol>
	 *     <li>The top of the stack is inspected for Reactor API references, and the deepest
	 *     one is kept, since multiple API references generally denote an alias operator.
	 *     (eg. {@code "Flux.map"})</li>
	 *     <li>The next stacktrace element is considered user code and is appended to the
	 *     result with a {@code ⇢} separator. (eg. {@code " ⇢ user.code.Class.method(Class.java:123)"})</li>
	 *     <li>If no user code is found in the sanitized stack, then the API reference is outputed in the later format only.</li>
	 *     <li>If the sanitized stack is empty, returns {@code "[no operator assembly information]"}</li>
	 * </ol>
	 *
	 *
	 * @param source the sanitized assembly stacktrace in String format.
	 * @return a {@link String} representing operator and operator assembly site extracted
	 * from the assembly stack trace.
	 */
	static String extractOperatorAssemblyInformation(String source) {
		String[] parts = extractOperatorAssemblyInformationParts(source);
		switch (parts.length) {
			case 0:
				return "[no operator assembly information]";
			default:
				return String.join(CALL_SITE_GLUE, parts);
		}
	}

	static boolean isUserCode(String line) {
		return !line.startsWith("publisher") || line.contains("Test");
	}

	/**
	 * Extract operator information out of an assembly stack trace in {@link String} form
	 * (see {@link Traces#callSiteSupplierFactory}) which potentially
	 * has a header line that one can skip by setting {@code skipFirst} to {@code true}.
	 * <p>
	 * Most operators will result in a line of the form {@code "Flux.map ⇢ user.code.Class.method(Class.java:123)"},
	 * that is:
	 * <ol>
	 *     <li>The top of the stack is inspected for Reactor API references, and the deepest
	 *     one is kept, since multiple API references generally denote an alias operator.
	 *     (eg. {@code "Flux.map"})</li>
	 *     <li>The next stacktrace element is considered user code and is appended to the
	 *     result with a {@code ⇢} separator. (eg. {@code " ⇢ user.code.Class.method(Class.java:123)"})</li>
	 *     <li>If no user code is found in the sanitized stack, then the API reference is outputed in the later format only.</li>
	 *     <li>If the sanitized stack is empty, returns {@code "[no operator assembly information]"}</li>
	 * </ol>
	 *
	 *
	 * @param source the sanitized assembly stacktrace in String format.
	 * @return a {@link String} representing operator and operator assembly site extracted
	 * from the assembly stack trace.
	 */
	static String[] extractOperatorAssemblyInformationParts(String source) {
		String[] uncleanTraces = source.split("\n");
		final List<String> traces = Stream.of(uncleanTraces)
		                                  .map(String::trim)
		                                  .filter(s -> !s.isEmpty())
		                                  .collect(Collectors.toList());

		if (traces.isEmpty()) {
			return new String[0];
		}

		int i = 0;
		while (i < traces.size() && !isUserCode(traces.get(i))) {
			i++;
		}

		String apiLine;
		String userCodeLine;
		if (i == 0) {
			//no line was a reactor API line
			apiLine = "";
			userCodeLine = traces.get(0);
		}
		else if (i == traces.size()) {
			//we skipped ALL lines, meaning they're all reactor API lines. We'll fully display the last one
			apiLine = "";
			userCodeLine = traces.get(i-1).replaceFirst("publisher.", "");
		}
		else {
			//currently on user code line, previous one is API
			apiLine = traces.get(i - 1);
			userCodeLine = traces.get(i);
		}

		//now we want something in the form "Flux.map ⇢ user.code.Class.method(Class.java:123)"
		if (apiLine.isEmpty()) return new String[] { userCodeLine };

		int linePartIndex = apiLine.indexOf('(');
		if (linePartIndex > 0) {
			apiLine = apiLine.substring(0, linePartIndex);
		}
		apiLine = apiLine.replaceFirst("publisher.", "");

		return new String[] { apiLine, "at " + userCodeLine };
	}
}
