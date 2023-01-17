/*
 * Copyright (c) 2016-2022 VMware Inc. or its affiliates, All Rights Reserved.
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

package org.test.reactor_new_api;

import java.util.Objects;
import java.util.Queue;
import java.util.function.Function;

/**
 * Maps the values of the source publisher one-on-one via a mapper function.
 * <p>
 * This variant allows composing fuseable stages.
 *
 * @param <T> the source value type
 * @param <R> the result value type
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxMap<T, R> extends InternalFluxOperator<T, R> implements
    Fuseable {

	final Function<? super T, ? extends R> mapper;

	/**
	 * Constructs a FluxMap instance with the given source and mapper.
	 *
	 * @param source the source Publisher instance
	 * @param mapper the mapper function
	 *
	 * @throws NullPointerException if either {@code source} or {@code mapper} is null.
	 */
	FluxMap(Flux<? extends T> source, Function<? super T, ? extends R> mapper) {
		super(source);
		this.mapper = Objects.requireNonNull(mapper, "mapper");
	}

	@Override
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super R> actual) {
		return new MapSubscriber<>(actual, mapper);
	}

	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;
		return super.scanUnsafe(key);
	}

	static final class MapSubscriber<T, R>
			implements InnerOperator<T, R>, QueueSubscription<R> {

		final CoreSubscriber<? super R> actual;
		final Function<? super T, ? extends R> mapper;

		boolean done;

		CoreSubscription<? extends T> s;

		Queue<? extends T> q;

		int supportedFusionMode;

		int sourceMode;

		MapSubscriber(CoreSubscriber<? super R> actual, Function<? super T, ? extends R> mapper) {
			this.actual = actual;
			this.mapper = mapper;
		}

		@Override
		public Object scanUnsafe(Attr key) {
			if (key == Attr.PARENT) return s;
			if (key == Attr.TERMINATED) return done;
			if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;

			return InnerOperator.super.scanUnsafe(key);
		}

		@Override
		public long onSubscribe(CoreSubscription<? extends T> cs, int supportedFusionMode) {
			if (Operators.validate(this.s, s)) {
				this.s = cs;
				this.supportedFusionMode = supportedFusionMode;
				return actual.onSubscribe(this, supportedFusionMode | Fuseable.THREAD_BARRIER);
			}
			return -1;
		}

		@Override
		public long tryOnNext(T t) {
			if (sourceMode == ASYNC) {
				return actual.tryOnNext(null);
			}

			if (done) {
				Operators.onNextDropped(t, actual.currentContext());
				return -1;
			}

			R v;

			try {
				v = mapper.apply(t);
				if (v == null) {
					throw new NullPointerException("The mapper [" + mapper.getClass().getName() + "] returned a null value.");
				}
				return actual.tryOnNext(v);
			}
			catch (Throwable e) {
				Throwable e_ = Operators.onNextError(t, e, actual.currentContext(), s);
				if (e_ != null) {
					onError(e_);
					return -1;
				}
				else {
					return 1;
				}
			}
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t, actual.currentContext());
				return;
			}

			done = true;

			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;

			actual.onComplete();
		}

		@Override
		public CoreSubscriber<? super R> actual() {
			return actual;
		}

		@Override
		public void request(long n) {
			s.request(n);
		}

		@Override
		public void cancel() {
			s.cancel();
		}

		@Override
		public R poll() {
			for(;;) {
				T v = q.poll();
				if (v != null) {
					try {
						return Objects.requireNonNull(mapper.apply(v));
					}
					catch (Throwable t) {
						RuntimeException e_ = Operators.onNextPollError(v, t, currentContext());
						if (e_ != null) {
							throw e_;
						}
						else {
							continue;
						}
					}
				}
				return null;
			}
		}

		@Override
		public boolean isEmpty() {
			return q.isEmpty();
		}

		@Override
		public void clear() {
			q.clear();
		}

		@Override
		public Queue<R> requestFusion(int requestedMode) {
			if ((requestedMode & Fuseable.THREAD_BARRIER) == 0) {
				final Queue<? extends T> ts = s.requestFusion(requestedMode);
				if (ts != null) {
					this.q = ts;
					this.sourceMode = supportedFusionMode & Fuseable.ANY;
					return this;
				}
			}

			return null;
		}

		@Override
		public int size() {
			return q.size();
		}
	}
}
