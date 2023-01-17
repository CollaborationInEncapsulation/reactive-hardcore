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

import static org.test.reactor_charcs.Exceptions.TERMINATED;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.test.reactor_new_api.Fuseable.QueueSubscription;
import org.test.reactor_new_api.Fuseable.SynchronousSubscription;
import org.test.reactor_new_api.concurrent.Queues;
import org.test.reactor_new_api.context.Context;


/**
 * Maps each upstream value into a Publisher and concatenates them into one
 * sequence of items.
 *
 * @param <T> the source value type
 * @param <R> the output value type
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxConcatMap<T, R> extends InternalFluxOperator<T, R> {

	final Function<? super T, ? extends Publisher<? extends R>> mapper;

	final Supplier<? extends Queue<T>> queueSupplier;

	final int prefetch;

	final ErrorMode errorMode;

	/**
	 * Indicates when an error from the main source should be reported.
	 */
	enum ErrorMode {
		/**
		 * Report the error immediately, cancelling the active inner source.
		 */
		IMMEDIATE, /**
		 * Report error after an inner source terminated.
		 */
		BOUNDARY, /**
		 * Report the error after all sources terminated.
		 */
		END
	}

	static <T, R> CoreSubscriber<T> subscriber(
      CoreSubscriber<? super R> s,
			Function<? super T, ? extends Publisher<? extends R>> mapper,
			Supplier<? extends Queue<T>> queueSupplier,
			int prefetch, ErrorMode errorMode) {
		switch (errorMode) {
			case BOUNDARY:
				return new ConcatMapDelayed<>(s,
						mapper,
						queueSupplier,
						prefetch,
						false);
			case END:
				return new ConcatMapDelayed<>(s,
						mapper,
						queueSupplier,
						prefetch,
						true);
			default:
				return new ConcatMapImmediate<>(s, mapper, queueSupplier, prefetch);
		}
	}

	FluxConcatMap(Flux<? extends T> source,
			Function<? super T, ? extends Publisher<? extends R>> mapper,
			Supplier<? extends Queue<T>> queueSupplier,
			int prefetch,
			ErrorMode errorMode) {
		super(source);
		if (prefetch <= 0) {
			throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
		}
		this.mapper = Objects.requireNonNull(mapper, "mapper");
		this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
		this.prefetch = prefetch;
		this.errorMode = Objects.requireNonNull(errorMode, "errorMode");
	}

	@Override
	public int getPrefetch() {
		return prefetch;
	}

	@Override
	public CoreSubscriber<? super T> subscribeOrReturn(
      CoreSubscriber<? super R> actual) {
		if (FluxFlatMap.trySubscribeScalarMap(source, actual, mapper, false, true)) {
			return null;
		}

		return subscriber(actual, mapper, queueSupplier, prefetch, errorMode);
	}

	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;
		return super.scanUnsafe(key);
	}

	static final class ConcatMapImmediate<T, R>
			implements FluxConcatMapSupport<T, R> {

		final CoreSubscriber<? super R> actual;
		final Context ctx;

		final ConcatMapInner<R> inner;

		final Function<? super T, ? extends Publisher<? extends R>> mapper;

		final Supplier<? extends Queue<T>> queueSupplier;

		final int prefetch;

		final int limit;

		CoreSubscription<? extends T> s;

		int consumed;

		volatile Queue<T> queue;

		volatile boolean done;

		volatile boolean cancelled;

		volatile Throwable error;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<ConcatMapImmediate, Throwable> ERROR =
				AtomicReferenceFieldUpdater.newUpdater(ConcatMapImmediate.class,
						Throwable.class,
						"error");

		volatile boolean active;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<ConcatMapImmediate> WIP =
				AtomicIntegerFieldUpdater.newUpdater(ConcatMapImmediate.class, "wip");

		volatile int guard;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<ConcatMapImmediate> GUARD =
				AtomicIntegerFieldUpdater.newUpdater(ConcatMapImmediate.class, "guard");

		int sourceMode;

		ConcatMapImmediate(CoreSubscriber<? super R> actual,
				Function<? super T, ? extends Publisher<? extends R>> mapper,
				Supplier<? extends Queue<T>> queueSupplier, int prefetch) {
			this.actual = actual;
			this.ctx = actual.currentContext();
			this.mapper = mapper;
			this.queueSupplier = queueSupplier;
			this.prefetch = prefetch;
			this.limit = Operators.unboundedOrLimit(prefetch);
			this.inner = new ConcatMapInner<>(this);
		}

		@Override
		public Object scanUnsafe(Attr key) {
			if (key == Attr.PARENT) return s;
			if (key == Attr.TERMINATED) return done || error == TERMINATED;
			if (key == Attr.CANCELLED) return cancelled;
			if (key == Attr.PREFETCH) return prefetch;
			if (key == Attr.BUFFERED) return queue != null ? queue.size() : 0;
			if (key == Attr.ERROR) return error;
			if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;

			return FluxConcatMapSupport.super.scanUnsafe(key);
		}

		@Override
		@SuppressWarnings("unchecked")
		public long onSubscribe(CoreSubscription<? extends T> cs, int supportedFusionModes) {
			if (Operators.validate(this.s, cs)) {
				this.s = cs;

				if ((supportedFusionModes & Fuseable.THREAD_BARRIER) == 0 && (this.sourceMode = (supportedFusionModes & Fuseable.ANY)) > 0) {
					this.queue = (Queue<T>) cs.requestFusion(Fuseable.ANY | Fuseable.THREAD_BARRIER);
					if (this.sourceMode == Fuseable.SYNC) {
						this.done = true;
						final long requestOrCancel = this.actual.onSubscribe(this, Fuseable.NONE);
						if (requestOrCancel < 0) {
							this.queue.clear();
							return requestOrCancel;
						}

						if (requestOrCancel > 0) {
							inner.requested = requestOrCancel;
						}

						drain();
					} else {
						final long requestOrCancel = this.actual.onSubscribe(this, Fuseable.NONE);
						if (requestOrCancel < 0) {
							this.queue.clear();
							return requestOrCancel;
						}

						if (requestOrCancel > 0) {
							inner.requested = requestOrCancel;
						}
					}
					return 0;
				} else {
					this.queue = queueSupplier.get();
				}

				final long requestOrCancel = actual.onSubscribe(this, Fuseable.NONE);
				if (requestOrCancel < 0) {
					return requestOrCancel;
				}

				if (requestOrCancel > 0) {
					inner.requested = requestOrCancel;
				}

				return Operators.unboundedOrPrefetch(prefetch);
			}

			return  -1;
		}

		@Override
		public long tryOnNext(T t) {
			if (sourceMode == Fuseable.ASYNC) {
				drain();
			}
			else if (!queue.offer(t)) {
				onError(Operators.onOperatorError(s, Exceptions.failWithOverflow(
                Exceptions.BACKPRESSURE_ERROR_QUEUE_FULL), t,
						this.ctx));
				Operators.onDiscard(t, this.ctx);
				return -1;
			}
			else {
				drain();
			}

			return 0;
		}

		@Override
		public void onError(Throwable t) {
			if (Exceptions.addThrowable(ERROR, this, t)) {
				inner.cancel();

				if (GUARD.getAndIncrement(this) == 0) {
					t = Exceptions.terminate(ERROR, this);
					if (t != TERMINATED) {
						actual.onError(t);
						Operators.onDiscardQueueWithClear(queue, this.ctx, null);
					}
				}
			}
			else {
				Operators.onErrorDropped(t, this.ctx);
			}
		}

		@Override
		public void onComplete() {
			done = true;
			drain();
		}

		@Override
		public long innerNext(R value) {
			if (guard == 0 && GUARD.compareAndSet(this, 0, 1)) {
				long result = actual.tryOnNext(value);
				if (GUARD.compareAndSet(this, 1, 0)) {
					return result;
				}
				Throwable e = Exceptions.terminate(ERROR, this);
				if (e != TERMINATED) {
					actual.onError(e);
				}
				return -1;
			}

			return -1;
		}

		@Override
		public long innerComplete() {
			active = false;
			drain();

			return 0;
		}

		@Override
		public void innerError(Throwable e) {
			e = Operators.onNextInnerError(e, currentContext(), s);
			if(e != null) {
				if (Exceptions.addThrowable(ERROR, this, e)) {
					s.cancel();

					if (GUARD.getAndIncrement(this) == 0) {
						e = Exceptions.terminate(ERROR, this);
						if (e != TERMINATED) {
							actual.onError(e);
							Operators.onDiscardQueueWithClear(queue, this.ctx, null);
						}
					}
				}
				else {
					Operators.onErrorDropped(e, this.ctx);
				}
			}
			else {
				active = false;
				drain();
			}
		}

		@Override
		public CoreSubscriber<? super R> actual() {
			return actual;
		}

		@Override
		public void request(long n) {
			inner.request(n);
		}

		@Override
		public void cancel() {
			if (!cancelled) {
				cancelled = true;

				inner.cancel();
				s.cancel();
				Operators.onDiscardQueueWithClear(queue, this.ctx, null);
			}
		}

		void drain() {
			if (WIP.getAndIncrement(this) == 0) {
				for (; ; ) {
					if (cancelled) {
						return;
					}

					if (!active) {
						boolean d = done;

						T v;

						try {
							v = queue.poll();
						}
						catch (Throwable e) {
							actual.onError(Operators.onOperatorError(s, e, this.ctx));
							return;
						}

						boolean empty = v == null;

						if (d && empty) {
							actual.onComplete();
							return;
						}

						if (!empty) {
							Publisher<? extends R> p;

							try {
								p = Objects.requireNonNull(mapper.apply(v),
								"The mapper returned a null Publisher");
							}
							catch (Throwable e) {
								Operators.onDiscard(v, this.ctx);
								Throwable e_ = Operators.onNextError(v, e, this.ctx, s);
								if (e_ != null) {
									actual.onError(Operators.onOperatorError(s, e, v,
											this.ctx));
									return;
								}
								else {
									continue;
								}
							}

							if (sourceMode == Fuseable.NONE) {
								int c = consumed + 1;
								if (c == limit) {
									consumed = 0;
									s.request(c);
								}
								else {
									consumed = c;
								}
							}

							if (p instanceof Callable) {
								@SuppressWarnings("unchecked") Callable<R> callable =
										(Callable<R>) p;

								R vr;

								try {
									vr = callable.call();
								}
								catch (Throwable e) {
									Throwable e_ = Operators.onNextError(v, e, this.ctx, s);
									if (e_ != null) {
										actual.onError(Operators.onOperatorError(s, e, v,
												this.ctx));
										Operators.onDiscardQueueWithClear(queue, this.ctx, null);
										return;
									}
									else {
										continue;
									}
								}

								if (vr == null) {
									continue;
								}

								if (inner.isUnbounded()) {
									if (guard == 0 && GUARD.compareAndSet(this, 0, 1)) {
										actual.onNext(vr);
										if (!GUARD.compareAndSet(this, 1, 0)) {
											Throwable e =
													Exceptions.terminate(ERROR, this);
											if (e != TERMINATED) {
												actual.onError(e);
											}
											return;
										}
									}
									continue;
								}
								else {
									active = true;
									inner.set(new WeakScalarSubscription<>(vr, inner));
								}

							}
							else {
								active = true;
								p.subscribe(inner);
							}
						}
					}
					if (WIP.decrementAndGet(this) == 0) {
						break;
					}
				}
			}
		}

		@Override
		public Queue<R> requestFusion(int fusionMode) {
			return null;
		}
	}

	static final class WeakScalarSubscription<T> implements SynchronousSubscription<T> {

		final CoreSubscriber<? super T> actual;
		final T                     value;
		boolean once;

		WeakScalarSubscription(T value, CoreSubscriber<? super T> actual) {
			this.value = value;
			this.actual = actual;
		}

		@Override
		public void request(long n) {
			if (n > 0 && !once) {
				once = true;
				Subscriber<? super T> a = actual;
				a.onNext(value);
				a.onComplete();
			}
		}

		@Override
		public void cancel() {
			Operators.onDiscard(value, actual.currentContext());
		}

		@Override
		public T poll() {
			if (once) {
				return null;
			}

			this.once = true;
			return value;
		}

		@Override
		public int size() {
			return once ? 0 : 1;
		}

		@Override
		public boolean isEmpty() {
			return once;
		}

		@Override
		public void clear() {
			if (once) {
				return;
			}

			once = true;
			Operators.onDiscard(value, actual.currentContext());
		}
	}

	static final class ConcatMapDelayed<T, R>
			implements FluxConcatMapSupport<T, R> {

		final CoreSubscriber<? super R> actual;

		final ConcatMapInner<R> inner;

		final Function<? super T, ? extends Publisher<? extends R>> mapper;

		final Supplier<? extends Queue<T>> queueSupplier;

		final int prefetch;

		final int limit;

		final boolean veryEnd;

		Subscription s;

		int consumed;

		volatile Queue<T> queue;

		volatile boolean done;

		volatile boolean cancelled;

		volatile Throwable error;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<ConcatMapDelayed, Throwable> ERROR =
				AtomicReferenceFieldUpdater.newUpdater(ConcatMapDelayed.class,
						Throwable.class,
						"error");

		volatile boolean active;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<ConcatMapDelayed> WIP =
				AtomicIntegerFieldUpdater.newUpdater(ConcatMapDelayed.class, "wip");

		int sourceMode;

		ConcatMapDelayed(CoreSubscriber<? super R> actual,
				Function<? super T, ? extends Publisher<? extends R>> mapper,
				Supplier<? extends Queue<T>> queueSupplier,
				int prefetch, boolean veryEnd) {
			this.actual = actual;
			this.mapper = mapper;
			this.queueSupplier = queueSupplier;
			this.prefetch = prefetch;
			this.limit = Operators.unboundedOrLimit(prefetch);
			this.veryEnd = veryEnd;
			this.inner = new ConcatMapInner<>(this);
		}

		@Override
		public CoreSubscriber<? super R> actual() {
			return actual;
		}

		@Override
		public Object scanUnsafe(Attr key) {
			if (key == Attr.PARENT) return s;
			if (key == Attr.TERMINATED) return done;
			if (key == Attr.CANCELLED) return cancelled;
			if (key == Attr.PREFETCH) return prefetch;
			if (key == Attr.BUFFERED) return queue != null ? queue.size() : 0;
			if (key == Attr.ERROR) return error;
			if (key == Attr.DELAY_ERROR) return true;
			if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;

			return FluxConcatMapSupport.super.scanUnsafe(key);
		}

		@Override
		@SuppressWarnings("unchecked")
		public long onSubscribe(CoreSubscription<? extends T> cs, int supportedFusionModes) {
			if (Operators.validate(this.s, cs)) {
				this.s = cs;

				if ((supportedFusionModes & Fuseable.THREAD_BARRIER) == 0 && (this.sourceMode = (supportedFusionModes & Fuseable.ANY)) > 0) {
					this.queue = (Queue<T>) cs.requestFusion(Fuseable.ANY | Fuseable.THREAD_BARRIER);
					if (this.sourceMode == Fuseable.SYNC) {
						this.done = true;
						final long requestOrCancel = this.actual.onSubscribe(this, Fuseable.NONE);
						if (requestOrCancel < 0) {
							this.queue.clear();
							return requestOrCancel;
						}

						if (requestOrCancel > 0) {
							inner.requested = requestOrCancel;
						}

						drain();
					} else {
						final long requestOrCancel = this.actual.onSubscribe(this, Fuseable.NONE);
						if (requestOrCancel < 0) {
							this.queue.clear();
							return requestOrCancel;
						}

						if (requestOrCancel > 0) {
							inner.requested = requestOrCancel;
						}
					}
					return 0;
				} else {
					this.queue = queueSupplier.get();
				}

				final long requestOrCancel = actual.onSubscribe(this, Fuseable.NONE);
				if (requestOrCancel < 0) {
					return requestOrCancel;
				}

				if (requestOrCancel > 0) {
					inner.requested = requestOrCancel;
				}

				return Operators.unboundedOrPrefetch(prefetch);
			}

			return  -1;
		}

		@Override
		public long tryOnNext(T t) {
			if (sourceMode == Fuseable.ASYNC) {
				drain();
			}
			else if (!queue.offer(t)) {
				Context ctx = actual.currentContext();
				onError(Operators.onOperatorError(s, Exceptions.failWithOverflow(
                Exceptions.BACKPRESSURE_ERROR_QUEUE_FULL), t,
						ctx));
				Operators.onDiscard(t, ctx);

				return -1;
			}
			else {
				drain();
			}

			return 0;
		}

		@Override
		public void onError(Throwable t) {
			if (Exceptions.addThrowable(ERROR, this, t)) {
				done = true;
				drain();
			}
			else {
				Operators.onErrorDropped(t, actual.currentContext());
			}
		}

		@Override
		public void onComplete() {
			done = true;
			drain();
		}

		@Override
		public long innerNext(R value) {
			return actual.tryOnNext(value);
		}

		@Override
		public long innerComplete() {
			active = false;
			drain();

			return 0;
		}

		@Override
		public void innerError(Throwable e) {
			e = Operators.onNextInnerError(e, currentContext(), s);
			if(e != null) {
				if (Exceptions.addThrowable(ERROR, this, e)) {
					if (!veryEnd) {
						s.cancel();
						done = true;
					}
					active = false;
					drain();
				}
				else {
					Operators.onErrorDropped(e, actual.currentContext());
				}
			}
			else {
				active = false;
			}
		}

		@Override
		public void request(long n) {
			inner.request(n);
		}

		@Override
		public void cancel() {
			if (!cancelled) {
				cancelled = true;

				inner.cancel();
				s.cancel();
				Operators.onDiscardQueueWithClear(queue, actual.currentContext(), null);
			}
		}

		void drain() {
			if (WIP.getAndIncrement(this) == 0) {
				Context ctx = null;
				for (; ; ) {
					if (cancelled) {
						return;
					}

					if (!active) {

						boolean d = done;

						if (d && !veryEnd) {
							Throwable ex = error;
							if (ex != null) {
								ex = Exceptions.terminate(ERROR, this);
								if (ex != TERMINATED) {
									actual.onError(ex);
								}
								return;
							}
						}

						T v;

						try {
							v = queue.poll();
						}
						catch (Throwable e) {
							actual.onError(Operators.onOperatorError(s, e, actual.currentContext()));
							return;
						}

						boolean empty = v == null;

						if (d && empty) {
							Throwable ex = Exceptions.terminate(ERROR, this);
							if (ex != null && ex != TERMINATED) {
								actual.onError(ex);
							}
							else {
								actual.onComplete();
							}
							return;
						}

						if (!empty) {
							Publisher<? extends R> p;

							try {
								p = Objects.requireNonNull(mapper.apply(v),
										"The mapper returned a null Publisher");
							}
							catch (Throwable e) {
								if (ctx == null) {
									ctx = actual.currentContext();
								}
								Operators.onDiscard(v, ctx);
								Throwable e_ = Operators.onNextError(v, e, ctx, s);
								if (e_ != null) {
									actual.onError(Operators.onOperatorError(s, e, v, ctx));
									return;
								}
								else {
									continue;
								}
							}

							if (sourceMode != Fuseable.SYNC) {
								int c = consumed + 1;
								if (c == limit) {
									consumed = 0;
									s.request(c);
								}
								else {
									consumed = c;
								}
							}

							if (p instanceof Callable) {
								@SuppressWarnings("unchecked") Callable<R> supplier =
										(Callable<R>) p;

								R vr;

								try {
									vr = supplier.call();
								}
								catch (Throwable e) {
									//does the strategy apply? if so, short-circuit the delayError. In any case, don't cancel
									if (ctx == null) {
										ctx = actual.currentContext();
									}
									Throwable e_ = Operators.onNextError(v, e, ctx);
									if (e_ == null) {
										continue;
									}
									//now if error mode strategy doesn't apply, let delayError play
									if (veryEnd && Exceptions.addThrowable(ERROR, this, e_)) {
										continue;
									}
									else {
										actual.onError(Operators.onOperatorError(s, e_, v, ctx));
										return;
									}
								}

								if (vr == null) {
									continue;
								}

								if (inner.isUnbounded()) {
									actual.tryOnNext(vr);
									continue;
								}
								else {
									active = true;
									if (inner.set(new WeakScalarSubscription<>(vr, inner)) > 0) {
										inner.tryOnNext(vr);
									}
								}
							}
							else {
								active = true;
								p.subscribe(inner);
							}
						}
					}
					if (WIP.decrementAndGet(this) == 0) {
						break;
					}
				}
			}
		}

		@Override
		public Queue<R> requestFusion(int fusionMode) {
			return null;
		}
	}

	/**
	 * @param <I> input type consumed by the InnerOperator
	 * @param <T> output type, as forwarded by the inner this helper supports
	 */
	interface FluxConcatMapSupport<I, T> extends InnerOperator<I, T> {

		long innerNext(T value);

		long innerComplete();

		void innerError(Throwable e);
	}

	static final class ConcatMapInner<R>
			extends Operators.MultiSubscriptionSubscriber<R, R> {

		final FluxConcatMapSupport<?, R> parent;

		long produced;

		ConcatMapInner(FluxConcatMapSupport<?, R> parent) {
			super(Operators.emptySubscriber());
			this.parent = parent;
		}

		@Override
		public Context currentContext() {
			return parent.currentContext();
		}

		@Override
		public Object scanUnsafe(Attr key) {
			if (key == Attr.ACTUAL) return parent;
			if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;

			return super.scanUnsafe(key);
		}

		@Override
		public long tryOnNext(R t) {
			produced++;

			final long requested = parent.innerNext(t);

			if (requested > 0) {
				this.requested = Operators.addCap(requested, this.requested);
				return requested;
			}

			return requested;
		}

		@Override
		public void onError(Throwable t) {
			long p = produced;

			if (p != 0L) {
				produced = 0L;
				produced(p);
			}

			parent.innerError(t);
		}

		@Override
		public void onComplete() {
			long p = produced;

			if (p != 0L) {
				produced = 0L;
				produced(p);
			}

			parent.innerComplete();
		}

		@Override
		public Queue<R> requestFusion(int fusionMode) {
			return null;
		}
	}
}
