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

package org.test.reactor_original;

import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.Spliterator;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.test.reactor_original.FluxOnAssembly.AssemblySnapshot;
import org.test.reactor_original.concurrent.Queues;

/**
 * A Reactive Streams {@link Publisher} with basic rx operators that emits at most one item <em>via</em> the
 * {@code onNext} signal then terminates with an {@code onComplete} signal (successful Mono,
 * with or without value), or only emits a single {@code onError} signal (failed Mono).
 *
 * <p>Most Mono implementations are expected to immediately call {@link Subscriber#onComplete()}
 * after having called {@link Subscriber#onNext(T)}. {@link Mono#never() Mono.never()} is an outlier: it doesn't
 * emit any signal, which is not technically forbidden although not terribly useful outside
 * of tests. On the other hand, a combination of {@code onNext} and {@code onError} is explicitly forbidden.
 *
 * <p>
 * The recommended way to learn about the {@link Mono} API and discover new operators is
 * through the reference documentation, rather than through this javadoc (as opposed to
 * learning more about individual operators). See the <a href="https://projectreactor.io/docs/core/release/reference/docs/index.html#which-operator">
 * "which operator do I need?" appendix</a>.
 *
 * <p><img class="marble" src="doc-files/marbles/mono.svg" alt="">
 *
 * <p>
 *
 * <p>The rx operators will offer aliases for input {@link Mono} type to preserve the "at most one"
 * property of the resulting {@link Mono}. For instance {@link Mono#flatMap flatMap} returns a
 * {@link Mono}, while there is a {@link Mono#flatMapMany flatMapMany} alias with possibly more than
 * 1 emission.
 *
 * <p>{@code Mono<Void>} should be used for {@link Publisher} that just completes without any value.
 *
 * <p>It is intended to be used in implementations and return types, input parameters should keep
 * using raw {@link Publisher} as much as possible.
 *
 * <p>Note that using state in the {@code java.util.function} / lambdas used within Mono operators
 * should be avoided, as these may be shared between several {@link Subscriber Subscribers}.
 *
 * @param <T> the type of the single value of this class
 * @author Sebastien Deleuze
 * @author Stephane Maldini
 * @author David Karnok
 * @author Simon Baslé
 * @see Flux
 */
public abstract class Mono<T> implements CorePublisher<T> {

//	 ==============================================================================================================
//	 Static Generators
//	 ==============================================================================================================

	/**
	 * Create a {@link Mono} provider that will {@link Supplier#get supply} a target {@link Mono} to subscribe to for
	 * each {@link Subscriber} downstream.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/deferForMono.svg" alt="">
	 * <p>
	 * @param supplier a {@link Mono} factory
	 * @param <T> the element type of the returned Mono instance
	 * @return a deferred {@link Mono}
	 * @see #deferContextual(Function)
	 */
	public static <T> Mono<T> defer(Supplier<? extends Mono<? extends T>> supplier) {
		return onAssembly(new MonoDefer<>(supplier));
	}

	/**
	 * Create a {@link Mono} that completes without emitting any item.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/empty.svg" alt="">
	 * <p>
	 * @param <T> the reified {@link Subscriber} type
	 *
	 * @return a completed {@link Mono}
	 */
	public static <T> Mono<T> empty() {
		return MonoEmpty.instance();
	}

	/**
	 * Create a {@link Mono} that terminates with the specified error immediately after
	 * being subscribed to.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/error.svg" alt="">
	 * <p>
	 * @param error the onError signal
	 * @param <T> the reified {@link Subscriber} type
	 *
	 * @return a failing {@link Mono}
	 */
	public static <T> Mono<T> error(Throwable error) {
		return onAssembly(new MonoError<>(error));
	}

	/**
	 * Expose the specified {@link Publisher} with the {@link Mono} API, and ensure it will emit 0 or 1 item.
	 * The source emitter will be cancelled on the first `onNext`.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/fromForMono.svg" alt="">
	 * <p>
	 * {@link Hooks#onEachOperator(String, Function)} and similar assembly hooks are applied
	 * unless the source is already a {@link Mono} (including {@link Mono} that was decorated as a {@link Flux},
	 * see {@link Flux#from(Publisher)}).
	 *
	 * @param source the {@link Publisher} source
	 * @param <T> the source type
	 *
	 * @return the next item emitted as a {@link Mono}
	 */
	public static <T> Mono<T> from(Publisher<? extends T> source) {
		//some sources can be considered already assembled monos
		//all conversion methods (from, fromDirect, wrap) must accommodate for this
		if (source instanceof Mono) {
			@SuppressWarnings("unchecked")
			Mono<T> casted = (Mono<T>) source;
			return casted;
		}
		if (source instanceof FluxSourceMono
				|| source instanceof FluxSourceMonoFuseable) {
			@SuppressWarnings("unchecked")
      FluxFromMonoOperator<T, T> wrapper = (FluxFromMonoOperator<T,T>) source;
			@SuppressWarnings("unchecked")
			Mono<T> extracted = (Mono<T>) wrapper.source;
			return extracted;
		}

		//we delegate to `wrap` and apply assembly hooks
		@SuppressWarnings("unchecked") Publisher<T> downcasted = (Publisher<T>) source;
		return onAssembly(wrap(downcasted, true));
	}

	/**
	 * Create a {@link Mono} producing its value using the provided {@link Callable}. If
	 * the Callable resolves to {@code null}, the resulting Mono completes empty.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/fromCallable.svg" alt="">
	 * <p>
	 * @param supplier {@link Callable} that will produce the value
	 * @param <T> type of the expected value
	 *
	 * @return A {@link Mono}.
	 */
	public static <T> Mono<T> fromCallable(Callable<? extends T> supplier) {
		return onAssembly(new MonoCallable<>(supplier));
	}

	/**
	 * Convert a {@link Publisher} to a {@link Mono} without any cardinality check
	 * (ie this method doesn't cancel the source past the first element).
	 * Conversion transparently returns {@link Mono} sources without wrapping and otherwise
	 * supports {@link Fuseable} sources.
	 * Note this is an advanced interoperability operator that implies you know the
	 * {@link Publisher} you are converting follows the {@link Mono} semantics and only
	 * ever emits one element.
	 * <p>
	 * {@link Hooks#onEachOperator(String, Function)} and similar assembly hooks are applied
	 * unless the source is already a {@link Mono}.
	 *
	 * @param source the Mono-compatible {@link Publisher} to wrap
	 * @param <I> type of the value emitted by the publisher
	 * @return a wrapped {@link Mono}
	 */
	public static <I> Mono<I> fromDirect(Publisher<? extends I> source){
		//some sources can be considered already assembled monos
		//all conversion methods (from, fromDirect, wrap) must accommodate for this
		if(source instanceof Mono){
			@SuppressWarnings("unchecked")
			Mono<I> m = (Mono<I>)source;
			return m;
		}
		if (source instanceof FluxSourceMono
				|| source instanceof FluxSourceMonoFuseable) {
			@SuppressWarnings("unchecked")
      FluxFromMonoOperator<I, I> wrapper = (FluxFromMonoOperator<I,I>) source;
			@SuppressWarnings("unchecked")
			Mono<I> extracted = (Mono<I>) wrapper.source;
			return extracted;
		}

		//we delegate to `wrap` and apply assembly hooks
		@SuppressWarnings("unchecked") Publisher<I> downcasted = (Publisher<I>) source;
		return onAssembly(wrap(downcasted, false));
	}

	/**
	 * Create a {@link Mono} that completes empty once the provided {@link Runnable} has
	 * been executed.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/fromRunnable.svg" alt="">
	 * <p>
	 * @param runnable {@link Runnable} that will be executed before emitting the completion signal
	 *
	 * @param <T> The generic type of the upstream, which is preserved by this operator
	 * @return A {@link Mono}.
	 */
	public static <T> Mono<T> fromRunnable(Runnable runnable) {
		return onAssembly(new MonoRunnable<>(runnable));
	}

	/**
	 * Create a new {@link Mono} that ignores elements from the source (dropping them),
	 * but completes when the source completes.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/ignoreElementsForMono.svg" alt="">
	 * <p>
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the element from the source.
	 *
	 * @param source the {@link Publisher} to ignore
	 * @param <T> the source type of the ignored data
	 *
	 * @return a new completable {@link Mono}.
	 */
	public static <T> Mono<T> ignoreElements(Publisher<T> source) {
		return onAssembly(new MonoIgnorePublisher<>(source));
	}

	/**
	 * Create a new {@link Mono} that emits the specified item, which is captured at
	 * instantiation time.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/just.svg" alt="">
	 * <p>
	 * @param data the only item to onNext
	 * @param <T> the type of the produced item
	 *
	 * @return a {@link Mono}.
	 */
	public static <T> Mono<T> just(T data) {
		return onAssembly(new MonoJust<>(data));
	}

	/**
	 * Create a new {@link Mono} that emits the specified item if {@link Optional#isPresent()} otherwise only emits
	 * onComplete.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/justOrEmpty.svg" alt="">
	 * <p>
	 * @param data the {@link Optional} item to onNext or onComplete if not present
	 * @param <T> the type of the produced item
	 *
	 * @return a {@link Mono}.
	 */
	public static <T> Mono<T> justOrEmpty( Optional<? extends T> data) {
		return data != null && data.isPresent() ? just(data.get()) : empty();
	}

	/**
	 * Create a new {@link Mono} that emits the specified item if non null otherwise only emits
	 * onComplete.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/justOrEmpty.svg" alt="">
	 * <p>
	 * @param data the item to onNext or onComplete if null
	 * @param <T> the type of the produced item
	 *
	 * @return a {@link Mono}.
	 */
	public static <T> Mono<T> justOrEmpty( T data) {
		return data != null ? just(data) : empty();
	}

//	 ==============================================================================================================
//	 Operators
//	 ==============================================================================================================

	/**
	 * Transform this {@link Mono} into a target type.
	 *
	 * <blockquote><pre>
	 * {@code mono.as(Flux::from).subscribe() }
	 * </pre></blockquote>
	 *
	 * @param transformer the {@link Function} to immediately map this {@link Mono}
	 * into a target type
	 * @param <P> the returned instance type
	 *
	 * @return the {@link Mono} transformed to an instance of P
	 * @see #transformDeferred(Function) transformDeferred(Function) for a lazy transformation of Mono
	 */
	public final <P> P as(Function<? super Mono<T>, P> transformer) {
		return transformer.apply(this);
	}

	/**
	 * Subscribe to this {@link Mono} and <strong>block indefinitely</strong> until a next signal is
	 * received. Returns that value, or null if the Mono completes empty. In case the Mono
	 * errors, the original exception is thrown (wrapped in a {@link RuntimeException} if
	 * it was a checked exception).
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/block.svg" alt="">
	 * <p>
	 * Note that each block() will trigger a new subscription: in other words, the result
	 * might miss signal from hot publishers.
	 *
	 * @return T the result
	 */
	
	public T block() {
		BlockingMonoSubscriber<T> subscriber = new BlockingMonoSubscriber<>();
		subscribe((Subscriber<T>) subscriber);
		return subscriber.blockingGet();
	}

	/**
	 * Subscribe to this {@link Mono} and <strong>block</strong> until a next signal is
	 * received or a timeout expires. Returns that value, or null if the Mono completes
	 * empty. In case the Mono errors, the original exception is thrown (wrapped in a
	 * {@link RuntimeException} if it was a checked exception).
	 * If the provided timeout expires, a {@link RuntimeException} is thrown.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/blockWithTimeout.svg" alt="">
	 * <p>
	 * Note that each block() will trigger a new subscription: in other words, the result
	 * might miss signal from hot publishers.
	 *
	 * @param timeout maximum time period to wait for before raising a {@link RuntimeException}
	 *
	 * @return T the result
	 */
	
	public T block(Duration timeout) {
		BlockingMonoSubscriber<T> subscriber = new BlockingMonoSubscriber<>();
		subscribe((Subscriber<T>) subscriber);
		return subscriber.blockingGet(timeout.toNanos(), TimeUnit.NANOSECONDS);
	}


	/**
	 * Concatenate emissions of this {@link Mono} with the provided {@link Publisher}
	 * (no interleave).
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatWithForMono.svg" alt="">
	 *
	 * @param other the {@link Publisher} sequence to concat after this {@link Flux}
	 *
	 * @return a concatenated {@link Flux}
	 */
	public final Flux<T> concatWith(Publisher<? extends T> other) {
		return Flux.concat(this, other);
	}

	/**
	 * Transform the item emitted by this {@link Mono} asynchronously, returning the
	 * value emitted by another {@link Mono} (possibly changing the value type).
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/flatMapForMono.svg" alt="">
	 *
	 * @param transformer the function to dynamically bind a new {@link Mono}
	 * @param <R> the result type bound
	 *
	 * @return a new {@link Mono} with an asynchronously mapped value.
	 */
	public final <R> Mono<R> flatMap(Function<? super T, ? extends Mono<? extends R>>
			transformer) {
		return onAssembly(new MonoFlatMap<>(this, transformer));
	}

	/**
	 * Transform the item emitted by this {@link Mono} into {@link Iterable}, then forward
	 * its elements into the returned {@link Flux}.
	 * The {@link Iterable#iterator()} method will be called at least once and at most twice.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/flatMapIterableForMono.svg" alt="">
	 * <p>
	 * This operator inspects each {@link Iterable}'s {@link Spliterator} to assess if the iteration
	 * can be guaranteed to be finite (see {@link Operators#onDiscardMultiple(Iterator, boolean, Context)}).
	 * Since the default Spliterator wraps the Iterator we can have two {@link Iterable#iterator()}
	 * calls per iterable. This second invocation is skipped on a {@link Collection } however, a type which is
	 * assumed to be always finite.
	 *
	 * <p><strong>Discard Support:</strong> Upon cancellation, this operator discards {@code T} elements it prefetched and, in
	 * some cases, attempts to discard remainder of the currently processed {@link Iterable} (if it can
	 * safely ensure the iterator is finite). Note that this means each {@link Iterable}'s {@link Iterable#iterator()}
	 * method could be invoked twice.
	 *
	 * @param mapper the {@link Function} to transform input item into a sequence {@link Iterable}
	 * @param <R> the merged output sequence type
	 *
	 * @return a merged {@link Flux}
	 *
	 */
	public final <R> Flux<R> flatMapIterable(Function<? super T, ? extends Iterable<? extends R>> mapper) {
		return Flux.onAssembly(new MonoFlattenIterable<>(this, mapper, Integer
				.MAX_VALUE, Queues.one()));
	}

	/**
	 * Transform the item emitted by this {@link Mono} by applying a synchronous function to it.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mapForMono.svg" alt="">
	 *
	 * @param mapper the synchronous transforming {@link Function}
	 * @param <R> the transformed type
	 *
	 * @return a new {@link Mono}
	 */
	public final <R> Mono<R> map(Function<? super T, ? extends R> mapper) {
		if (this instanceof Fuseable) {
			return onAssembly(new MonoMapFuseable<>(this, mapper));
		}
		return onAssembly(new MonoMap<>(this, mapper));
	}

	/**
	 * Convert this {@link Mono} to a {@link Flux}
	 *
	 * @return a {@link Flux} variant of this {@link Mono}
	 */
    public final Flux<T> flux() {
			if (this instanceof Callable && !(this instanceof Fuseable.ScalarCallable)) {
				@SuppressWarnings("unchecked") Callable<T> thiz = (Callable<T>) this;
				return Flux.onAssembly(new FluxCallable<>(thiz));
			}
		return Flux.from(this);
	}

	@Override
	@SuppressWarnings("unchecked")
	public final void subscribe(Subscriber<? super T> actual) {
		CorePublisher publisher = Operators.onLastAssembly(this);
		CoreSubscriber subscriber = Operators.toCoreSubscriber(actual);

		if (subscriber instanceof Fuseable.QueueSubscription && this != publisher && this instanceof Fuseable && !(publisher instanceof Fuseable)) {
			subscriber = new FluxHide.SuppressFuseableSubscriber<>(subscriber);
		}

		try {
			if (publisher instanceof OptimizableOperator) {
				OptimizableOperator operator = (OptimizableOperator) publisher;
				while (true) {
					subscriber = operator.subscribeOrReturn(subscriber);
					if (subscriber == null) {
						// null means "I will subscribe myself", returning...
						return;
					}

					OptimizableOperator newSource = operator.nextOptimizableSource();
					if (newSource == null) {
						publisher = operator.source();
						break;
					}
					operator = newSource;
				}
			}

			publisher.subscribe(subscriber);
		}
		catch (Throwable e) {
			Operators.reportThrowInSubscribe(subscriber, e);
			return;
		}
	}

	/**
	 * An internal {@link Publisher#subscribe(Subscriber)} that will bypass
	 * {@link Hooks#onLastOperator(Function)} pointcut.
	 * <p>
	 * In addition to behave as expected by {@link Publisher#subscribe(Subscriber)}
	 * in a controlled manner, it supports direct subscribe-time {@link Context} passing.
	 *
	 * @param actual the {@link Subscriber} interested into the published sequence
	 * @see Publisher#subscribe(Subscriber)
	 */
	public abstract void subscribe(CoreSubscriber<? super T> actual);

	/**
	 * Subscribe the given {@link Subscriber} to this {@link Mono} and return said
	 * {@link Subscriber}, allowing subclasses with a richer API to be used fluently.
	 *
	 * @param subscriber the {@link Subscriber} to subscribe with
	 * @param <E> the reified type of the {@link Subscriber} for chaining
	 *
	 * @return the passed {@link Subscriber} after subscribing it to this {@link Mono}
	 */
	public final <E extends Subscriber<? super T>> E subscribeWith(E subscriber) {
		subscribe(subscriber);
		return subscriber;
	}

	/**
	 * To be used by custom operators: invokes assembly {@link Hooks} pointcut given a
	 * {@link Mono}, potentially returning a new {@link Mono}. This is for example useful
	 * to activate cross-cutting concerns at assembly time, eg. a generalized
	 * {@link #checkpoint()}.
	 *
	 * @param <T> the value type
	 * @param source the source to apply assembly hooks onto
	 *
	 * @return the source, potentially wrapped with assembly time cross-cutting behavior
	 */
	@SuppressWarnings("unchecked")
	protected static <T> Mono<T> onAssembly(Mono<T> source) {
		Function<Publisher, Publisher> hook = Hooks.onEachOperatorHook;
		if(hook != null) {
			source = (Mono<T>) hook.apply(source);
		}
		if (Hooks.GLOBAL_TRACE) {
			AssemblySnapshot stacktrace = new AssemblySnapshot(null, Traces.callSiteSupplierFactory.get());
			source = (Mono<T>) Hooks.addAssemblyInfo(source, stacktrace);
		}
		return source;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName();
	}

	/**
	 * Unchecked wrap of {@link Publisher} as {@link Mono}, supporting {@link Fuseable} sources.
	 * When converting a {@link Mono} or {@link Mono Monos} that have been converted to a {@link Flux} and back,
	 * the original {@link Mono} is returned unwrapped.
	 * Note that this bypasses {@link Hooks#onEachOperator(String, Function) assembly hooks}.
	 *
	 * @param source the {@link Publisher} to wrap
	 * @param enforceMonoContract {@code} true to wrap publishers without assumption about their cardinality
	 * (first {@link Subscriber#onNext(Object)} will cancel the source), {@code false} to behave like {@link #fromDirect(Publisher)}.
	 * @param <T> input upstream type
	 * @return a wrapped {@link Mono}
	 */
	static <T> Mono<T> wrap(Publisher<T> source, boolean enforceMonoContract) {
		//some sources can be considered already assembled monos
		//all conversion methods (from, fromDirect, wrap) must accommodate for this
		if (source instanceof Mono) {
			return (Mono<T>) source;
		}
		if (source instanceof FluxSourceMono
				|| source instanceof FluxSourceMonoFuseable) {
			@SuppressWarnings("unchecked")
			Mono<T> extracted = (Mono<T>) ((FluxFromMonoOperator<T,T>) source).source;
			return extracted;
		}

		//equivalent to what from used to be, without assembly hooks
		if (enforceMonoContract) {
			if (source instanceof Flux && source instanceof Callable) {
					@SuppressWarnings("unchecked") Callable<T> m = (Callable<T>) source;
					return Flux.wrapToMono(m);
			}
			if (source instanceof Flux) {
				return new MonoNext<>((Flux<T>) source);
			}
			return new MonoFromPublisher<>(source);
		}

		//equivalent to what fromDirect used to be without onAssembly
		if(source instanceof Flux && source instanceof Fuseable) {
			return new MonoSourceFluxFuseable<>((Flux<T>) source);
		}
		if (source instanceof Flux) {
			return new MonoSourceFlux<>((Flux<T>) source);
		}
		if(source instanceof Fuseable) {
			return new MonoSourceFuseable<>(source);
		}
		return new MonoSource<>(source);
	}

	@SuppressWarnings("unchecked")
	static <T> BiPredicate<? super T, ? super T> equalsBiPredicate(){
		return EQUALS_BIPREDICATE;
	}
	static final BiPredicate EQUALS_BIPREDICATE = Object::equals;
}
