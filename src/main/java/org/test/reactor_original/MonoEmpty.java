/*
 * Copyright (c) 2016-2021 VMware Inc. or its affiliates, All Rights Reserved.
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

import java.time.Duration;
import org.reactivestreams.Publisher;

/**
 * Represents an empty publisher which only calls onSubscribe and onComplete.
 * <p>
 * This Publisher is effectively stateless and only a single instance exists.
 * Use the {@link #instance()} method to obtain a properly type-parametrized view of it.
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class MonoEmpty extends Mono<Object>
		implements Fuseable.ScalarCallable<Object>, SourceProducer<Object> {

	static final Publisher<Object> INSTANCE = new MonoEmpty();

	MonoEmpty() {
		// deliberately no op
	}

	@Override
	public void subscribe(CoreSubscriber<? super Object> actual) {
		Operators.complete(actual);
	}

	/**
	 * Returns a properly parametrized instance of this empty Publisher.
	 *
	 * @param <T> the output type
	 * @return a properly parametrized instance of this empty Publisher
	 */
	@SuppressWarnings("unchecked")
	static <T> Mono<T> instance() {
		return (Mono<T>) INSTANCE;
	}

	@Override
	public Object call() throws Exception {
		return null; /* Scalar optimizations on empty */
	}

	@Override
	public Object block(Duration m) {
		return null;
	}

	@Override
	public Object block() {
		return null;
	}

	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;
		return null;
	}
}