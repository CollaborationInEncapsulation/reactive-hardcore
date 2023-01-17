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

package org.test.reactor_new_api;

import java.util.Objects;
import java.util.Queue;
import java.util.function.Predicate;
import org.test.reactor_new_api.context.Context;

/**
 * Filters out values that make a filter function return false.
 *
 * @param <T> the value type
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxFilter<T> extends InternalFluxOperator<T, T> implements Fuseable {

	final Predicate<? super T> predicate;

	FluxFilter(Flux<? extends T> source, Predicate<? super T> predicate) {
		super(source);
		this.predicate = Objects.requireNonNull(predicate, "predicate");
	}

	@Override
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super T> actual) {
		return new FilterSubscriber<>(actual, predicate);
	}

	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;
		return super.scanUnsafe(key);
	}

	static final class FilterSubscriber<T> implements InnerOperator<T, T>, QueueSubscription<T> {

		final CoreSubscriber<? super T> actual;
		final Context ctx;

		final Predicate<? super T> predicate;

		CoreSubscription<? extends T> s;

		Queue<? extends T> q;

		boolean done;

		int supportedFusionMode;

		int sourceMode;

		FilterSubscriber(CoreSubscriber<? super T> actual, Predicate<? super T> predicate) {
			this.actual = actual;
			this.ctx = actual.currentContext();
			this.predicate = predicate;
		}

		@Override
		public long onSubscribe(CoreSubscription<? extends T> cs, int supportedFusionMode) {
			if (Operators.validate(this.s, cs)) {
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
				Operators.onNextDropped(t, this.ctx);
				return -1;
			}

			boolean b;

			try {
				b = predicate.test(t);
			}
			catch (Throwable e) {
				Throwable e_ = Operators.onNextError(t, e, this.ctx, s);
				if (e_ != null) {
					onError(e_);
					return -1;
				}
				Operators.onDiscard(t, this.ctx);
				return 1;
			}
			if (b) {
				return actual.tryOnNext(t);
			}
			else {
				Operators.onDiscard(t, this.ctx);
				return 1;
			}
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t, this.ctx);
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
		public Object scanUnsafe(Attr key) {
			if (key == Attr.PARENT) return s;
			if (key == Attr.TERMINATED) return done;
			if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;

			return InnerOperator.super.scanUnsafe(key);
		}

		@Override
		public CoreSubscriber<? super T> actual() {
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
		public T poll() {
				for (; ; ) {
					T v = q.poll();

					try {
						if (v == null || predicate.test(v)) {
							return v;
						}
						Operators.onDiscard(v, this.ctx);
					}
					catch (Throwable e) {
						RuntimeException e_ = Operators.onNextPollError(v, e, this.ctx);
						Operators.onDiscard(v, this.ctx);
						if (e_ != null) {
							throw e_;
						}
						// else continue
					}
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
		public int size() {
			return q.size();
		}

		@Override
		public Queue<T> requestFusion(int requestedMode) {
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
	}
}
