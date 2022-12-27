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

package org.test.reactor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collector;
import java.util.stream.Stream;

import io.micrometer.core.instrument.MeterRegistry;
import observability.SignalListener;
import observability.SignalListenerFactory;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.test.reactor.FluxOnAssembly.AssemblySnapshot;
import org.test.reactor.concurrent.Queues;
import org.test.reactor.context.Context;
import org.test.reactor.context.ContextView;
import org.test.reactor.scheduler.Scheduler;
import org.test.reactor.scheduler.Schedulers;

/**
 * A Reactive Streams {@link Publisher} with rx operators that emits 0 to N elements, and then completes
 * (successfully or with an error).
 * <p>
 * The recommended way to learn about the {@link Flux} API and discover new operators is
 * through the reference documentation, rather than through this javadoc (as opposed to
 * learning more about individual operators). See the <a href="https://projectreactor.io/docs/core/release/reference/docs/index.html#which-operator">
 * "which operator do I need?" appendix</a>.
 *
 * <p>
 * <img class="marble" src="doc-files/marbles/flux.svg" alt="">
 *
 * <p>It is intended to be used in implementations and return types. Input parameters should keep using raw
 * {@link Publisher} as much as possible.
 *
 * <p>If it is known that the underlying {@link Publisher} will emit 0 or 1 element, {@link Mono} should be used
 * instead.
 *
 * <p>Note that using state in the {@code java.util.function} / lambdas used within Flux operators
 * should be avoided, as these may be shared between several {@link Subscriber Subscribers}.
 *
 * <p> {@link #subscribe(CoreSubscriber)} is an internal extension to
 * {@link #subscribe(Subscriber)} used internally for {@link Context} passing. User
 * provided {@link Subscriber} may
 * be passed to this "subscribe" extension but will loose the available
 * per-subscribe {@link Hooks#onLastOperator}.
 *
 * @param <T> the element type of this Reactive Streams {@link Publisher}
 *
 * @author Sebastien Deleuze
 * @author Stephane Maldini
 * @author David Karnok
 * @author Simon Baslé
 *
 * @see Mono
 */
public abstract class Flux<T> implements CorePublisher<T> {

//	 ==============================================================================================================
//	 Static Generators
//	 ==============================================================================================================

	/**
	 * Concatenates the values to the end of the {@link Flux}
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatWithValues.svg" alt="">
	 *
	 * @param values The values to concatenate
	 *
	 * @return a new {@link Flux} concatenating all source sequences
	 */
	@SafeVarargs
	public final Flux<T> concatWithValues(T... values) {
	    return concatWith(Flux.fromArray(values));
	}

	/**
	 * Concatenate all sources emitted as an onNext signal from a parent {@link Publisher},
	 * forwarding elements emitted by the sources downstream.
	 * <p>
	 * Concatenation is achieved by sequentially subscribing to the first source then
	 * waiting for it to complete before subscribing to the next, and so on until the
	 * last source completes. Any error interrupts the sequence immediately and is
	 * forwarded downstream.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatAsyncSources.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements it internally queued for backpressure upon cancellation.
	 *
	 * @param sources The {@link Publisher} of {@link Publisher} to concatenate
	 * @param <T> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux} concatenating all inner sources sequences
	 */
	public static <T> Flux<T> concat(Publisher<? extends Publisher<? extends T>> sources) {
		return concat(sources, Queues.XS_BUFFER_SIZE);
	}

	/**
	 * Concatenate all sources emitted as an onNext signal from a parent {@link Publisher},
	 * forwarding elements emitted by the sources downstream.
	 * <p>
	 * Concatenation is achieved by sequentially subscribing to the first source then
	 * waiting for it to complete before subscribing to the next, and so on until the
	 * last source completes. Any error interrupts the sequence immediately and is
	 * forwarded downstream.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatAsyncSources.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements it internally queued for backpressure upon cancellation.
	 *
	 * @param sources The {@link Publisher} of {@link Publisher} to concatenate
	 * @param prefetch the number of Publishers to prefetch from the outer {@link Publisher}
	 * @param <T> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux} concatenating all inner sources sequences
	 */
	public static <T> Flux<T> concat(Publisher<? extends Publisher<? extends T>> sources, int prefetch) {
		return from(sources).concatMap(identityFunction(), prefetch);
	}

	/**
	 * Concatenate all sources provided as a vararg, forwarding elements emitted by the
	 * sources downstream.
	 * <p>
	 * Concatenation is achieved by sequentially subscribing to the first source then
	 * waiting for it to complete before subscribing to the next, and so on until the
	 * last source completes. Any error interrupts the sequence immediately and is
	 * forwarded downstream.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatVarSources.svg" alt="">
	 *
	 * @param sources The {@link Publisher} of {@link Publisher} to concat
	 * @param <T> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux} concatenating all source sequences
	 */
	@SafeVarargs
	public static <T> Flux<T> concat(Publisher<? extends T>... sources) {
		return onAssembly(new FluxConcatArray<>(false, sources));
	}

	/**
	 * Concatenate all sources emitted as an onNext signal from a parent {@link Publisher},
	 * forwarding elements emitted by the sources downstream.
	 * <p>
	 * Concatenation is achieved by sequentially subscribing to the first source then
	 * waiting for it to complete before subscribing to the next, and so on until the
	 * last source completes. Errors do not interrupt the main sequence but are propagated
	 * after the rest of the sources have had a chance to be concatenated.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatAsyncSources.svg" alt="">
	 *
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements it internally queued for backpressure upon cancellation.
	 *
	 * @param sources The {@link Publisher} of {@link Publisher} to concatenate
	 * @param <T> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux} concatenating all inner sources sequences, delaying errors
	 */
	public static <T> Flux<T> concatDelayError(Publisher<? extends Publisher<? extends T>> sources) {
		return concatDelayError(sources, Queues.XS_BUFFER_SIZE);
	}

	/**
	 * Concatenate all sources emitted as an onNext signal from a parent {@link Publisher},
	 * forwarding elements emitted by the sources downstream.
	 * <p>
	 * Concatenation is achieved by sequentially subscribing to the first source then
	 * waiting for it to complete before subscribing to the next, and so on until the
	 * last source completes. Errors do not interrupt the main sequence but are propagated
	 * after the rest of the sources have had a chance to be concatenated.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatAsyncSources.svg" alt="">
	 * <p>
	 * <p><strong>Discard Support:</strong> This operator discards elements it internally queued for backpressure upon cancellation.
	 *
	 * @param sources The {@link Publisher} of {@link Publisher} to concatenate
	 * @param prefetch number of elements to prefetch from the source, to be turned into inner Publishers
	 * @param <T> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux} concatenating all inner sources sequences until complete or error
	 */
	public static <T> Flux<T> concatDelayError(Publisher<? extends Publisher<? extends T>> sources, int prefetch) {
		return from(sources).concatMapDelayError(identityFunction(), prefetch);
	}

	/**
	 * Concatenate all sources emitted as an onNext signal from a parent {@link Publisher},
	 * forwarding elements emitted by the sources downstream.
	 * <p>
	 * Concatenation is achieved by sequentially subscribing to the first source then
	 * waiting for it to complete before subscribing to the next, and so on until the
	 * last source completes.
	 * <p>
	 * Errors do not interrupt the main sequence but are propagated after the current
	 * concat backlog if {@code delayUntilEnd} is {@literal false} or after all sources
	 * have had a chance to be concatenated if {@code delayUntilEnd} is {@literal true}.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatAsyncSources.svg" alt="">
	 * <p>
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements it internally queued for backpressure upon cancellation.
	 *
	 * @param sources The {@link Publisher} of {@link Publisher} to concatenate
	 * @param delayUntilEnd delay error until all sources have been consumed instead of
	 * after the current source
	 * @param prefetch the number of Publishers to prefetch from the outer {@link Publisher}
	 * @param <T> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux} concatenating all inner sources sequences until complete or error
	 */
	public static <T> Flux<T> concatDelayError(Publisher<? extends Publisher<? extends
			T>> sources, boolean delayUntilEnd, int prefetch) {
		return from(sources).concatMapDelayError(identityFunction(), delayUntilEnd, prefetch);
	}

	/**
	 * Concatenate all sources provided as a vararg, forwarding elements emitted by the
	 * sources downstream.
	 * <p>
	 * Concatenation is achieved by sequentially subscribing to the first source then
	 * waiting for it to complete before subscribing to the next, and so on until the
	 * last source completes. Errors do not interrupt the main sequence but are propagated
	 * after the rest of the sources have had a chance to be concatenated.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatVarSources.svg" alt="">
	 * <p>
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements it internally queued for backpressure upon cancellation.
	 *
	 * @param sources The {@link Publisher} of {@link Publisher} to concat
	 * @param <T> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux} concatenating all source sequences
	 */
	@SafeVarargs
	public static <T> Flux<T> concatDelayError(Publisher<? extends T>... sources) {
		return onAssembly(new FluxConcatArray<>(true, sources));
	}

	/**
	 * Create a {@link Flux} that completes without emitting any item.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/empty.svg" alt="">
	 *
	 * @param <T> the reified type of the target {@link Subscriber}
	 *
	 * @return an empty {@link Flux}
	 */
	public static <T> Flux<T> empty() {
		return FluxEmpty.instance();
	}

	/**
	 * Create a {@link Flux} that terminates with the specified error immediately after
	 * being subscribed to.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/error.svg" alt="">
	 *
	 * @param error the error to signal to each {@link Subscriber}
	 * @param <T> the reified type of the target {@link Subscriber}
	 *
	 * @return a new failing {@link Flux}
	 */
	public static <T> Flux<T> error(Throwable error) {
		return error(error, false);
	}

	/**
	 * Create a {@link Flux} that terminates with an error immediately after being
	 * subscribed to. The {@link Throwable} is generated by a {@link Supplier}, invoked
	 * each time there is a subscription and allowing for lazy instantiation.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/errorWithSupplier.svg" alt="">
	 *
	 * @param errorSupplier the error signal {@link Supplier} to invoke for each {@link Subscriber}
	 * @param <T> the reified type of the target {@link Subscriber}
	 *
	 * @return a new failing {@link Flux}
	 */
	public static <T> Flux<T> error(Supplier<? extends Throwable> errorSupplier) {
		return onAssembly(new FluxErrorSupplied<>(errorSupplier));
	}

	/**
	 * Create a {@link Flux} that terminates with the specified error, either immediately
	 * after being subscribed to or after being first requested.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/errorWhenRequested.svg" alt="">
	 *
	 * @param throwable the error to signal to each {@link Subscriber}
	 * @param whenRequested if true, will onError on the first request instead of subscribe().
	 * @param <O> the reified type of the target {@link Subscriber}
	 *
	 * @return a new failing {@link Flux}
	 */
	public static <O> Flux<O> error(Throwable throwable, boolean whenRequested) {
		if (whenRequested) {
			return onAssembly(new FluxErrorOnRequest<>(throwable));
		}
		else {
			return onAssembly(new FluxError<>(throwable));
		}
	}

	/**
	 * Pick the first {@link Publisher} to emit any signal (onNext/onError/onComplete) and
	 * replay all signals from that {@link Publisher}, effectively behaving like the
	 * fastest of these competing sources.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/firstWithSignalForFlux.svg" alt="">
	 *
	 * @param sources The competing source publishers
	 * @param <I> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux} behaving like the fastest of its sources
	 * @deprecated use {@link #firstWithSignal(Publisher[])}. To be removed in reactor 3.5.
	 */
	@SafeVarargs
	@Deprecated
	public static <I> Flux<I> first(Publisher<? extends I>... sources) {
		return firstWithSignal(sources);
	}

	/**
	 * Pick the first {@link Publisher} to emit any signal (onNext/onError/onComplete) and
	 * replay all signals from that {@link Publisher}, effectively behaving like the
	 * fastest of these competing sources.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/firstWithSignalForFlux.svg" alt="">
	 *
	 * @param sources The competing source publishers
	 * @param <I> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux} behaving like the fastest of its sources
	 * @deprecated use {@link #firstWithSignal(Iterable)}. To be removed in reactor 3.5.
	 */
	@Deprecated
	public static <I> Flux<I> first(Iterable<? extends Publisher<? extends I>> sources) {
		return firstWithSignal(sources);
	}

	/**
	 * Pick the first {@link Publisher} to emit any signal (onNext/onError/onComplete) and
	 * replay all signals from that {@link Publisher}, effectively behaving like the
	 * fastest of these competing sources.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/firstWithSignalForFlux.svg" alt="">
	 *
	 * @param sources The competing source publishers
	 * @param <I> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux} behaving like the fastest of its sources
	 */
	@SafeVarargs
	public static <I> Flux<I> firstWithSignal(Publisher<? extends I>... sources) {
		return onAssembly(new FluxFirstWithSignal<>(sources));
	}

	/**
	 * Pick the first {@link Publisher} to emit any signal (onNext/onError/onComplete) and
	 * replay all signals from that {@link Publisher}, effectively behaving like the
	 * fastest of these competing sources.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/firstWithSignalForFlux.svg" alt="">
	 *
	 * @param sources The competing source publishers
	 * @param <I> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux} behaving like the fastest of its sources
	 */
	public static <I> Flux<I> firstWithSignal(Iterable<? extends Publisher<? extends I>> sources) {
		return onAssembly(new FluxFirstWithSignal<>(sources));
	}

	/**
	 * Pick the first {@link Publisher} to emit any value and replay all values
	 * from that {@link Publisher}, effectively behaving like the source that first
	 * emits an {@link Subscriber#onNext(Object) onNext}.
	 *
	 * <p>
	 * Sources with values always "win" over empty sources (ones that only emit onComplete)
	 * or failing sources (ones that only emit onError).
	 * <p>
	 * When no source can provide a value, this operator fails with a {@link NoSuchElementException}
	 * (provided there are at least two sources). This exception has a {@link Exceptions#multiple(Throwable...) composite}
	 * as its {@link Throwable#getCause() cause} that can be used to inspect what went wrong with each source
	 * (so the composite has as many elements as there are sources).
	 * <p>
	 * Exceptions from failing sources are directly reflected in the composite at the index of the failing source.
	 * For empty sources, a {@link NoSuchElementException} is added at their respective index.
	 * One can use {@link Exceptions#unwrapMultiple(Throwable) Exceptions.unwrapMultiple(topLevel.getCause())}
	 * to easily inspect these errors as a {@link List}.
	 * <p>
	 * Note that like in {@link #firstWithSignal(Iterable)}, an infinite source can be problematic
	 * if no other source emits onNext.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/firstWithValueForFlux.svg" alt="">
	 *
	 * @param sources An {@link Iterable} of the competing source publishers
	 * @param <I> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux} behaving like the fastest of its sources
	 */
	public static <I> Flux<I> firstWithValue(Iterable<? extends Publisher<? extends I>> sources) {
		return onAssembly(new FluxFirstWithValue<>(sources));
	}

	/**
	 * Pick the first {@link Publisher} to emit any value and replay all values
	 * from that {@link Publisher}, effectively behaving like the source that first
	 * emits an {@link Subscriber#onNext(Object) onNext}.
	 * <p>
	 * Sources with values always "win" over an empty source (ones that only emit onComplete)
	 * or failing sources (ones that only emit onError).
	 * <p>
	 * When no source can provide a value, this operator fails with a {@link NoSuchElementException}
	 * (provided there are at least two sources). This exception has a {@link Exceptions#multiple(Throwable...) composite}
	 * as its {@link Throwable#getCause() cause} that can be used to inspect what went wrong with each source
	 * (so the composite has as many elements as there are sources).
	 * <p>
	 * Exceptions from failing sources are directly reflected in the composite at the index of the failing source.
	 * For empty sources, a {@link NoSuchElementException} is added at their respective index.
	 * One can use {@link Exceptions#unwrapMultiple(Throwable) Exceptions.unwrapMultiple(topLevel.getCause())}
	 * to easily inspect these errors as a {@link List}.
	 * <p>
	 * Note that like in {@link #firstWithSignal(Publisher[])}, an infinite source can be problematic
	 * if no other source emits onNext.
	 * In case the {@code first} source is already an array-based {@link #firstWithValue(Publisher, Publisher[])}
	 * instance, nesting is avoided: a single new array-based instance is created with all the
	 * sources from {@code first} plus all the {@code others} sources at the same level.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/firstWithValueForFlux.svg" alt="">
	 *
	 * @param first The first competing source publisher
	 * @param others The other competing source publishers
	 * @param <I> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux} behaving like the fastest of its sources
	 */
	@SafeVarargs
	public static <I> Flux<I> firstWithValue(Publisher<? extends I> first, Publisher<? extends I>... others) {
		if (first instanceof FluxFirstWithValue) {
			@SuppressWarnings("unchecked")
			FluxFirstWithValue<I> orPublisher = (FluxFirstWithValue<I>) first;

			FluxFirstWithValue<I> result = orPublisher.firstValuedAdditionalSources(others);
			if (result != null) {
				return result;
			}
		}
		return onAssembly(new FluxFirstWithValue<>(first, others));
	}

	/**
	 * Decorate the specified {@link Publisher} with the {@link Flux} API.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/fromForFlux.svg" alt="">
	 * <p>
	 * {@link Hooks#onEachOperator(String, Function)} and similar assembly hooks are applied
	 * unless the source is already a {@link Flux}.
	 *
	 * @param source the source to decorate
	 * @param <T> The type of values in both source and output sequences
	 *
	 * @return a new {@link Flux}
	 */
	public static <T> Flux<T> from(Publisher<? extends T> source) {
		//duplicated in wrap, but necessary to detect early and thus avoid applying assembly
		if (source instanceof Flux) {
			@SuppressWarnings("unchecked")
			Flux<T> casted = (Flux<T>) source;
			return casted;
		}

		//all other cases (including ScalarCallable) are managed without assembly in wrap
		//let onAssembly point to Flux.from:
		return onAssembly(wrap(source));
	}

	/**
	 * Create a {@link Flux} that emits the items contained in the provided array.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/fromArray.svg" alt="">
	 *
	 * @param array the array to read data from
	 * @param <T> The type of values in the source array and resulting Flux
	 *
	 * @return a new {@link Flux}
	 */
	public static <T> Flux<T> fromArray(T[] array) {
		if (array.length == 0) {
			return empty();
		}
		if (array.length == 1) {
			return just(array[0]);
		}
		return onAssembly(new FluxArray<>(array));
	}

	/**
	 * Create a {@link Flux} that emits the items contained in the provided {@link Iterable}.
	 * The {@link Iterable#iterator()} method will be invoked at least once and at most twice
	 * for each subscriber.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/fromIterable.svg" alt="">
	 * <p>
	 * This operator inspects the {@link Iterable}'s {@link Spliterator} to assess if the iteration
	 * can be guaranteed to be finite (see {@link Operators#onDiscardMultiple(Iterator, boolean, Context)}).
	 * Since the default Spliterator wraps the Iterator we can have two {@link Iterable#iterator()}
	 * calls. This second invocation is skipped on a {@link Collection} however, a type which is
	 * assumed to be always finite.
	 *
	 * <p><strong>Discard Support:</strong> Upon cancellation, this operator attempts to discard the remainder of the
	 * {@link Iterable} if it can safely ensure the iterator is finite.
	 * Note that this means the {@link Iterable#iterator()} method could be invoked twice.
	 *
	 * @param it the {@link Iterable} to read data from
	 * @param <T> The type of values in the source {@link Iterable} and resulting Flux
	 *
	 * @return a new {@link Flux}
	 */
	public static <T> Flux<T> fromIterable(Iterable<? extends T> it) {
		return onAssembly(new FluxIterable<>(it));
	}

	/**
	 * Create a {@link Flux} that emits the items contained in the provided {@link Stream}.
	 * Keep in mind that a {@link Stream} cannot be re-used, which can be problematic in
	 * case of multiple subscriptions or re-subscription (like with {@link #repeat()} or
	 * {@link #retry()}). The {@link Stream} is {@link Stream#close() closed} automatically
	 * by the operator on cancellation, error or completion.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/fromStream.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> Upon cancellation, this operator attempts to discard remainder of the
	 * {@link Stream} through its open {@link Spliterator}, if it can safely ensure it is finite
	 * (see {@link Operators#onDiscardMultiple(Iterator, boolean, Context)}).
	 *
	 * @param s the {@link Stream} to read data from
	 * @param <T> The type of values in the source {@link Stream} and resulting Flux
	 *
	 * @return a new {@link Flux}
	 */
	public static <T> Flux<T> fromStream(Stream<? extends T> s) {
		Objects.requireNonNull(s, "Stream s must be provided");
		return onAssembly(new FluxStream<>(() -> s));
	}

	/**
	 * Create a {@link Flux} that emits the items contained in a {@link Stream} created by
	 * the provided {@link Supplier} for each subscription. The {@link Stream} is
	 * {@link Stream#close() closed} automatically by the operator on cancellation, error
	 * or completion.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/fromStream.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> Upon cancellation, this operator attempts to discard remainder of the
	 * {@link Stream} through its open {@link Spliterator}, if it can safely ensure it is finite
	 * (see {@link Operators#onDiscardMultiple(Iterator, boolean, Context)}).
	 *
	 * @param streamSupplier the {@link Supplier} that generates the {@link Stream} from
	 * which to read data
	 * @param <T> The type of values in the source {@link Stream} and resulting Flux
	 *
	 * @return a new {@link Flux}
	 */
	public static <T> Flux<T> fromStream(Supplier<Stream<? extends T>> streamSupplier) {
		return onAssembly(new FluxStream<>(streamSupplier));
	}

	/**
	 * Programmatically create a {@link Flux} by generating signals one-by-one via a
	 * consumer callback.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/generateStateless.svg" alt="">
	 *
	 * @param <T> the value type emitted
	 * @param generator Consume the {@link SynchronousSink} provided per-subscriber by Reactor
	 * to generate a <strong>single</strong> signal on each pass.
	 *
	 * @return a {@link Flux}
	 */
	public static <T> Flux<T> generate(Consumer<SynchronousSink<T>> generator) {
		Objects.requireNonNull(generator, "generator");
		return onAssembly(new FluxGenerate<>(generator));
	}

	/**
	 * Programmatically create a {@link Flux} by generating signals one-by-one via a
	 * consumer callback and some state. The {@code stateSupplier} may return {@literal null}.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/generate.svg" alt="">
	 *
	 * @param <T> the value type emitted
	 * @param <S> the per-subscriber custom state type
	 * @param stateSupplier called for each incoming Subscriber to provide the initial state for the generator bifunction
	 * @param generator Consume the {@link SynchronousSink} provided per-subscriber by Reactor
	 * as well as the current state to generate a <strong>single</strong> signal on each pass
	 * and return a (new) state.
	 * @return a {@link Flux}
	 */
	public static <T, S> Flux<T> generate(Callable<S> stateSupplier, BiFunction<S, SynchronousSink<T>, S> generator) {
		return onAssembly(new FluxGenerate<>(stateSupplier, generator));
	}

	/**
	 * Programmatically create a {@link Flux} by generating signals one-by-one via a
	 * consumer callback and some state, with a final cleanup callback. The
	 * {@code stateSupplier} may return {@literal null} but your cleanup {@code stateConsumer}
	 * will need to handle the null case.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/generateWithCleanup.svg" alt="">
	 *
	 * @param <T> the value type emitted
	 * @param <S> the per-subscriber custom state type
	 * @param stateSupplier called for each incoming Subscriber to provide the initial state for the generator bifunction
	 * @param generator Consume the {@link SynchronousSink} provided per-subscriber by Reactor
	 * as well as the current state to generate a <strong>single</strong> signal on each pass
	 * and return a (new) state.
	 * @param stateConsumer called after the generator has terminated or the downstream cancelled, receiving the last
	 * state to be handled (i.e., release resources or do other cleanup).
	 *
	 * @return a {@link Flux}
	 */
	public static <T, S> Flux<T> generate(Callable<S> stateSupplier, BiFunction<S, SynchronousSink<T>, S> generator, Consumer<? super S> stateConsumer) {
		return onAssembly(new FluxGenerate<>(stateSupplier, generator, stateConsumer));
	}

	/**
	 * Create a {@link Flux} that emits long values starting with 0 and incrementing at
	 * specified time intervals on the global timer. The first element is emitted after
	 * an initial delay equal to the {@code period}. If demand is not produced in time,
	 * an onError will be signalled with an {@link Exceptions#isOverflow(Throwable) overflow}
	 * {@code IllegalStateException} detailing the tick that couldn't be emitted.
	 * In normal conditions, the {@link Flux} will never complete.
	 * <p>
	 * Runs on the {@link Schedulers#parallel()} Scheduler.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/interval.svg" alt="">
	 *
	 * @param period the period {@link Duration} between each increment
	 * @return a new {@link Flux} emitting increasing numbers at regular intervals
	 */
	public static Flux<Long> interval(Duration period) {
		return interval(period, Schedulers.parallel());
	}

	/**
	 * Create a {@link Flux} that emits long values starting with 0 and incrementing at
	 * specified time intervals, after an initial delay, on the global timer. If demand is
	 * not produced in time, an onError will be signalled with an
	 * {@link Exceptions#isOverflow(Throwable) overflow} {@code IllegalStateException}
	 * detailing the tick that couldn't be emitted. In normal conditions, the {@link Flux}
	 * will never complete.
	 * <p>
	 * Runs on the {@link Schedulers#parallel()} Scheduler.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/intervalWithDelay.svg" alt="">
	 *
	 * @param delay  the {@link Duration} to wait before emitting 0l
	 * @param period the period {@link Duration} before each following increment
	 *
	 * @return a new {@link Flux} emitting increasing numbers at regular intervals
	 */
	public static Flux<Long> interval(Duration delay, Duration period) {
		return interval(delay, period, Schedulers.parallel());
	}

	/**
	 * Create a {@link Flux} that emits long values starting with 0 and incrementing at
	 * specified time intervals, on the specified {@link Scheduler}. The first element is
	 * emitted after an initial delay equal to the {@code period}. If demand is not
	 * produced in time, an onError will be signalled with an {@link Exceptions#isOverflow(Throwable) overflow}
	 * {@code IllegalStateException} detailing the tick that couldn't be emitted.
	 * In normal conditions, the {@link Flux} will never complete.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/interval.svg" alt="">
	 *
	 * @param period the period {@link Duration} between each increment
	 * @param timer a time-capable {@link Scheduler} instance to run on
	 *
	 * @return a new {@link Flux} emitting increasing numbers at regular intervals
	 */
	public static Flux<Long> interval(Duration period, Scheduler timer) {
		return interval(period, period, timer);
	}

	/**
	 * Create a {@link Flux} that emits long values starting with 0 and incrementing at
	 * specified time intervals, after an initial delay, on the specified {@link Scheduler}.
	 * If demand is not produced in time, an onError will be signalled with an
	 * {@link Exceptions#isOverflow(Throwable) overflow} {@code IllegalStateException}
	 * detailing the tick that couldn't be emitted. In normal conditions, the {@link Flux}
	 * will never complete.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/intervalWithDelay.svg" alt="">
	 *
	 * @param delay  the {@link Duration} to wait before emitting 0l
	 * @param period the period {@link Duration} before each following increment
	 * @param timer a time-capable {@link Scheduler} instance to run on
	 *
	 * @return a new {@link Flux} emitting increasing numbers at regular intervals
	 */
	public static Flux<Long> interval(Duration delay, Duration period, Scheduler timer) {
		return onAssembly(new FluxInterval(delay.toNanos(), period.toNanos(), TimeUnit.NANOSECONDS, timer));
	}

	/**
	 * Create a {@link Flux} that emits the provided elements and then completes.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/justMultiple.svg" alt="">
	 *
	 * @param data the elements to emit, as a vararg
	 * @param <T> the emitted data type
	 *
	 * @return a new {@link Flux}
	 */
	@SafeVarargs
	public static <T> Flux<T> just(T... data) {
		return fromArray(data);
	}

	/**
	 * Create a new {@link Flux} that will only emit a single element then onComplete.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/just.svg" alt="">
	 *
	 * @param data the single element to emit
	 * @param <T> the emitted data type
	 *
	 * @return a new {@link Flux}
	 */
	public static <T> Flux<T> just(T data) {
		return onAssembly(new FluxJust<>(data));
	}

	/**
	 * Merge data from {@link Publisher} sequences emitted by the passed {@link Publisher}
	 * into an interleaved merged sequence. Unlike {@link #concat(Publisher) concat}, inner
	 * sources are subscribed to eagerly.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeAsyncSources.svg" alt="">
	 *
	 * <p>
	 * Note that merge is tailored to work with asynchronous sources or finite sources. When dealing with
	 * an infinite source that doesn't already publish on a dedicated Scheduler, you must isolate that source
	 * in its own Scheduler, as merge would otherwise attempt to drain it before subscribing to
	 * another source.
	 *
	 * @param source a {@link Publisher} of {@link Publisher} sources to merge
	 * @param <T> the merged type
	 *
	 * @return a merged {@link Flux}
	 */
	public static <T> Flux<T> merge(Publisher<? extends Publisher<? extends T>> source) {
		return merge(source,
				Queues.SMALL_BUFFER_SIZE,
				Queues.XS_BUFFER_SIZE);
	}

	/**
	 * Merge data from {@link Publisher} sequences emitted by the passed {@link Publisher}
	 * into an interleaved merged sequence. Unlike {@link #concat(Publisher) concat}, inner
	 * sources are subscribed to eagerly (but at most {@code concurrency} sources are
	 * subscribed to at the same time).
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeAsyncSources.svg" alt="">
	 * <p>
	 * Note that merge is tailored to work with asynchronous sources or finite sources. When dealing with
	 * an infinite source that doesn't already publish on a dedicated Scheduler, you must isolate that source
	 * in its own Scheduler, as merge would otherwise attempt to drain it before subscribing to
	 * another source.
	 *
	 * @param source a {@link Publisher} of {@link Publisher} sources to merge
	 * @param concurrency the request produced to the main source thus limiting concurrent merge backlog
	 * @param <T> the merged type
	 *
	 * @return a merged {@link Flux}
	 */
	public static <T> Flux<T> merge(Publisher<? extends Publisher<? extends T>> source, int concurrency) {
		return merge(source, concurrency, Queues.XS_BUFFER_SIZE);
	}

	/**
	 * Merge data from {@link Publisher} sequences emitted by the passed {@link Publisher}
	 * into an interleaved merged sequence. Unlike {@link #concat(Publisher) concat}, inner
	 * sources are subscribed to eagerly (but at most {@code concurrency} sources are
	 * subscribed to at the same time).
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeAsyncSources.svg" alt="">
	 * <p>
	 * Note that merge is tailored to work with asynchronous sources or finite sources. When dealing with
	 * an infinite source that doesn't already publish on a dedicated Scheduler, you must isolate that source
	 * in its own Scheduler, as merge would otherwise attempt to drain it before subscribing to
	 * another source.
	 *
	 * @param source a {@link Publisher} of {@link Publisher} sources to merge
	 * @param concurrency the request produced to the main source thus limiting concurrent merge backlog
	 * @param prefetch the inner source request size
	 * @param <T> the merged type
	 *
	 * @return a merged {@link Flux}
	 */
	public static <T> Flux<T> merge(Publisher<? extends Publisher<? extends T>> source, int concurrency, int prefetch) {
		return onAssembly(new FluxFlatMap<>(
				from(source),
				identityFunction(),
				false,
				concurrency,
				Queues.get(concurrency),
				prefetch,
				Queues.get(prefetch)));
	}

	/**
	 * Merge data from {@link Publisher} sequences contained in an {@link Iterable}
	 * into an interleaved merged sequence. Unlike {@link #concat(Publisher) concat}, inner
	 * sources are subscribed to eagerly.
	 * A new {@link Iterator} will be created for each subscriber.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeFixedSources.svg" alt="">
	 * <p>
	 * Note that merge is tailored to work with asynchronous sources or finite sources. When dealing with
	 * an infinite source that doesn't already publish on a dedicated Scheduler, you must isolate that source
	 * in its own Scheduler, as merge would otherwise attempt to drain it before subscribing to
	 * another source.
	 *
	 * @param sources the {@link Iterable} of sources to merge (will be lazily iterated on subscribe)
	 * @param <I> The source type of the data sequence
	 *
	 * @return a merged {@link Flux}
	 */
	public static <I> Flux<I> merge(Iterable<? extends Publisher<? extends I>> sources) {
		return merge(fromIterable(sources));
	}

	/**
	 * Merge data from {@link Publisher} sequences contained in an array / vararg
	 * into an interleaved merged sequence. Unlike {@link #concat(Publisher) concat},
	 * sources are subscribed to eagerly.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeFixedSources.svg" alt="">
	 * <p>
	 * Note that merge is tailored to work with asynchronous sources or finite sources. When dealing with
	 * an infinite source that doesn't already publish on a dedicated Scheduler, you must isolate that source
	 * in its own Scheduler, as merge would otherwise attempt to drain it before subscribing to
	 * another source.
	 *
	 * @param sources the array of {@link Publisher} sources to merge
	 * @param <I> The source type of the data sequence
	 *
	 * @return a merged {@link Flux}
	 */
	@SafeVarargs
	public static <I> Flux<I> merge(Publisher<? extends I>... sources) {
		return merge(Queues.XS_BUFFER_SIZE, sources);
	}

	/**
	 * Merge data from {@link Publisher} sequences contained in an array / vararg
	 * into an interleaved merged sequence. Unlike {@link #concat(Publisher) concat},
	 * sources are subscribed to eagerly.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeFixedSources.svg" alt="">
	 * <p>
	 * Note that merge is tailored to work with asynchronous sources or finite sources. When dealing with
	 * an infinite source that doesn't already publish on a dedicated Scheduler, you must isolate that source
	 * in its own Scheduler, as merge would otherwise attempt to drain it before subscribing to
	 * another source.
	 *
	 * @param sources the array of {@link Publisher} sources to merge
	 * @param prefetch the inner source request size
	 * @param <I> The source type of the data sequence
	 *
	 * @return a fresh Reactive {@link Flux} publisher ready to be subscribed
	 */
	@SafeVarargs
	public static <I> Flux<I> merge(int prefetch, Publisher<? extends I>... sources) {
		return merge(prefetch, false, sources);
	}

	/**
	 * Merge data from {@link Publisher} sequences contained in an array / vararg
	 * into an interleaved merged sequence. Unlike {@link #concat(Publisher) concat},
	 * sources are subscribed to eagerly.
	 * This variant will delay any error until after the rest of the merge backlog has been processed.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeFixedSources.svg" alt="">
	 * <p>
	 * Note that merge is tailored to work with asynchronous sources or finite sources. When dealing with
	 * an infinite source that doesn't already publish on a dedicated Scheduler, you must isolate that source
	 * in its own Scheduler, as merge would otherwise attempt to drain it before subscribing to
	 * another source.
	 *
	 * @param sources the array of {@link Publisher} sources to merge
	 * @param prefetch the inner source request size
	 * @param <I> The source type of the data sequence
	 *
	 * @return a fresh Reactive {@link Flux} publisher ready to be subscribed
	 */
	@SafeVarargs
	public static <I> Flux<I> mergeDelayError(int prefetch, Publisher<? extends I>... sources) {
		return merge(prefetch, true, sources);
	}

	/**
	 * Merge data from provided {@link Publisher} sequences into an ordered merged sequence,
	 * by picking the smallest values from each source (as defined by the provided
	 * {@link Comparator}) <strong>as they arrive</strong>. This is not a {@link #sort(Comparator)}, as it doesn't consider
	 * the whole of each sequences. Unlike mergeComparing, this operator does <em>not</em> wait for a value from each
	 * source to arrive either.
	 * <p>
	 * While this operator does retrieve at most one value from each source, it only compares values when two or more
	 * sources emit at the same time. In that case it picks the smallest of these competing values and continues doing so
	 * as long as there is demand. It is therefore best suited for asynchronous sources where you do not want to wait
	 * for a value from each source before emitting a value downstream.
	 * <p>
	 * Note that it is delaying errors until all data is consumed.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergePriority.svg" alt="">
	 *
	 * @param prefetch the number of elements to prefetch from each source (avoiding too
	 * many small requests to the source when picking)
	 * @param comparator the {@link Comparator} to use to find the smallest value
	 * @param sources {@link Publisher} sources to merge
	 * @param <T> the merged type
	 * @return a merged {@link Flux} that compares the latest available value from each source, publishing the
	 * smallest value and replenishing the source that produced it.
	 */
	@SafeVarargs
	public static <T> Flux<T> mergePriorityDelayError(int prefetch, Comparator<? super T> comparator, Publisher<? extends T>... sources) {
		if (sources.length == 0) {
			return empty();
		}
		if (sources.length == 1) {
			return from(sources[0]);
		}
		return onAssembly(new FluxMergeComparing<>(prefetch, comparator, true, false, sources));
	}

	/**
	 * Merge data from provided {@link Publisher} sequences into an ordered merged sequence,
	 * by picking the smallest values from each source (as defined by their natural order).
	 * This is not a {@link #sort()}, as it doesn't consider the whole of each sequences.
	 * <p>
	 * Instead, this operator considers only one value from each source and picks the
	 * smallest of all these values, then replenishes the slot for that picked source.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeComparingNaturalOrder.svg" alt="">
	 *
	 * @param sources {@link Publisher} sources of {@link Comparable} to merge
	 * @param <I> a {@link Comparable} merged type that has a {@link Comparator#naturalOrder() natural order}
	 * @return a merged {@link Flux} that , subscribing early but keeping the original ordering
	 */
	@SafeVarargs
	public static <I extends Comparable<? super I>> Flux<I> mergeComparing(Publisher<? extends I>... sources) {
		return mergeComparing(Queues.SMALL_BUFFER_SIZE, Comparator.naturalOrder(), sources);
	}

	/**
	 * Merge data from provided {@link Publisher} sequences into an ordered merged sequence,
	 * by picking the smallest values from each source (as defined by the provided
	 * {@link Comparator}). This is not a {@link #sort(Comparator)}, as it doesn't consider
	 * the whole of each sequences.
	 * <p>
	 * Instead, this operator considers only one value from each source and picks the
	 * smallest of all these values, then replenishes the slot for that picked source.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeComparing.svg" alt="">
	 *
	 * @param comparator the {@link Comparator} to use to find the smallest value
	 * @param sources {@link Publisher} sources to merge
	 * @param <T> the merged type
	 * @return a merged {@link Flux} that compares latest values from each source, using the
	 * smallest value and replenishing the source that produced it
	 */
	@SafeVarargs
	public static <T> Flux<T> mergeComparing(Comparator<? super T> comparator, Publisher<? extends T>... sources) {
		return mergeComparing(Queues.SMALL_BUFFER_SIZE, comparator, sources);
	}

	/**
	 * Merge data from provided {@link Publisher} sequences into an ordered merged sequence,
	 * by picking the smallest values from each source (as defined by the provided
	 * {@link Comparator}). This is not a {@link #sort(Comparator)}, as it doesn't consider
	 * the whole of each sequences.
	 * <p>
	 * Instead, this operator considers only one value from each source and picks the
	 * smallest of all these values, then replenishes the slot for that picked source.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeComparing.svg" alt="">
	 *
	 * @param prefetch the number of elements to prefetch from each source (avoiding too
	 * many small requests to the source when picking)
	 * @param comparator the {@link Comparator} to use to find the smallest value
	 * @param sources {@link Publisher} sources to merge
	 * @param <T> the merged type
	 * @return a merged {@link Flux} that compares latest values from each source, using the
	 * smallest value and replenishing the source that produced it
	 */
	@SafeVarargs
	public static <T> Flux<T> mergeComparing(int prefetch, Comparator<? super T> comparator, Publisher<? extends T>... sources) {
		if (sources.length == 0) {
			return empty();
		}
		if (sources.length == 1) {
			return from(sources[0]);
		}
		return onAssembly(new FluxMergeComparing<>(prefetch, comparator, false, true, sources));
	}

	/**
	 * Merge data from provided {@link Publisher} sequences into an ordered merged sequence,
	 * by picking the smallest values from each source (as defined by the provided
	 * {@link Comparator}). This is not a {@link #sort(Comparator)}, as it doesn't consider
	 * the whole of each sequences.
	 * <p>
	 * Instead, this operator considers only one value from each source and picks the
	 * smallest of all these values, then replenishes the slot for that picked source.
	 * <p>
	 * Note that it is delaying errors until all data is consumed.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeComparing.svg" alt="">
	 *
	 * @param prefetch the number of elements to prefetch from each source (avoiding too
	 * many small requests to the source when picking)
	 * @param comparator the {@link Comparator} to use to find the smallest value
	 * @param sources {@link Publisher} sources to merge
	 * @param <T> the merged type
	 * @return a merged {@link Flux} that compares latest values from each source, using the
	 * smallest value and replenishing the source that produced it
	 */
	@SafeVarargs
	public static <T> Flux<T> mergeComparingDelayError(int prefetch, Comparator<? super T> comparator, Publisher<? extends T>... sources) {
		if (sources.length == 0) {
			return empty();
		}
		if (sources.length == 1) {
			return from(sources[0]);
		}
		return onAssembly(new FluxMergeComparing<>(prefetch, comparator, true, true, sources));
	}

	/**
	 * Merge data from provided {@link Publisher} sequences into an ordered merged sequence,
	 * by picking the smallest values from each source (as defined by their natural order).
	 * This is not a {@link #sort()}, as it doesn't consider the whole of each sequences.
	 * <p>
	 * Instead, this operator considers only one value from each source and picks the
	 * smallest of all these values, then replenishes the slot for that picked source.
	 * <p>
	 * Note that it is delaying errors until all data is consumed.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeComparingNaturalOrder.svg" alt="">
	 *
	 * @param sources {@link Publisher} sources of {@link Comparable} to merge
	 * @param <I> a {@link Comparable} merged type that has a {@link Comparator#naturalOrder() natural order}
	 * @return a merged {@link Flux} that compares latest values from each source, using the
	 * smallest value and replenishing the source that produced it
	 * @deprecated Use {@link #mergeComparingDelayError(int, Comparator, Publisher[])} instead
	 * (as {@link #mergeComparing(Publisher[])} don't have this operator's delayError behavior).
	 * To be removed in 3.6.0 at the earliest.
	 */
	@SafeVarargs
	@Deprecated
	public static <I extends Comparable<? super I>> Flux<I> mergeOrdered(Publisher<? extends I>... sources) {
		return mergeOrdered(Queues.SMALL_BUFFER_SIZE, Comparator.naturalOrder(), sources);
	}

	/**
	 * Merge data from provided {@link Publisher} sequences into an ordered merged sequence,
	 * by picking the smallest values from each source (as defined by the provided
	 * {@link Comparator}). This is not a {@link #sort(Comparator)}, as it doesn't consider
	 * the whole of each sequences.
	 * <p>
	 * Instead, this operator considers only one value from each source and picks the
	 * smallest of all these values, then replenishes the slot for that picked source.
	 * <p>
	 * Note that it is delaying errors until all data is consumed.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeComparing.svg" alt="">
	 *
	 * @param comparator the {@link Comparator} to use to find the smallest value
	 * @param sources {@link Publisher} sources to merge
	 * @param <T> the merged type
	 * @return a merged {@link Flux} that compares latest values from each source, using the
	 * smallest value and replenishing the source that produced it
	 * @deprecated Use {@link #mergeComparingDelayError(int, Comparator, Publisher[])} instead
	 * (as {@link #mergeComparing(Publisher[])} don't have this operator's delayError behavior).
	 * To be removed in 3.6.0 at the earliest.
	 */
	@SafeVarargs
	@Deprecated
	public static <T> Flux<T> mergeOrdered(Comparator<? super T> comparator, Publisher<? extends T>... sources) {
		return mergeOrdered(Queues.SMALL_BUFFER_SIZE, comparator, sources);
	}

	/**
	 * Merge data from provided {@link Publisher} sequences into an ordered merged sequence,
	 * by picking the smallest values from each source (as defined by the provided
	 * {@link Comparator}). This is not a {@link #sort(Comparator)}, as it doesn't consider
	 * the whole of each sequences.
	 * <p>
	 * Instead, this operator considers only one value from each source and picks the
	 * smallest of all these values, then replenishes the slot for that picked source.
	 * <p>
	 * Note that it is delaying errors until all data is consumed.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeComparing.svg" alt="">
	 *
	 * @param prefetch the number of elements to prefetch from each source (avoiding too
	 * many small requests to the source when picking)
	 * @param comparator the {@link Comparator} to use to find the smallest value
	 * @param sources {@link Publisher} sources to merge
	 * @param <T> the merged type
	 * @return a merged {@link Flux} that compares latest values from each source, using the
	 * smallest value and replenishing the source that produced it
	 * @deprecated Use {@link #mergeComparingDelayError(int, Comparator, Publisher[])} instead
	 * (as {@link #mergeComparing(Publisher[])} don't have this operator's delayError behavior).
	 * To be removed in 3.6.0 at the earliest.
	 */
	@SafeVarargs
	@Deprecated
	public static <T> Flux<T> mergeOrdered(int prefetch, Comparator<? super T> comparator, Publisher<? extends T>... sources) {
		if (sources.length == 0) {
			return empty();
		}
		if (sources.length == 1) {
			return from(sources[0]);
		}
		return onAssembly(new FluxMergeComparing<>(prefetch, comparator, true, true, sources));
	}

	/**
	 * Merge data from {@link Publisher} sequences emitted by the passed {@link Publisher}
	 * into an ordered merged sequence. Unlike concat, the inner publishers are subscribed to
	 * eagerly. Unlike merge, their emitted values are merged into the final sequence in
	 * subscription order.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeSequentialAsyncSources.svg" alt="">
	 *
	 * @param sources a {@link Publisher} of {@link Publisher} sources to merge
	 * @param <T> the merged type
	 *
	 * @return a merged {@link Flux}, subscribing early but keeping the original ordering
	 */
	public static <T> Flux<T> mergeSequential(Publisher<? extends Publisher<? extends T>> sources) {
		return mergeSequential(sources, false, Queues.SMALL_BUFFER_SIZE,
				Queues.XS_BUFFER_SIZE);
	}

	/**
	 * Merge data from {@link Publisher} sequences emitted by the passed {@link Publisher}
	 * into an ordered merged sequence. Unlike concat, the inner publishers are subscribed to
	 * eagerly (but at most {@code maxConcurrency} sources at a time). Unlike merge, their
	 * emitted values are merged into the final sequence in subscription order.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeSequentialAsyncSources.svg" alt="">
	 *
	 * @param sources a {@link Publisher} of {@link Publisher} sources to merge
	 * @param prefetch the inner source request size
	 * @param maxConcurrency the request produced to the main source thus limiting concurrent merge backlog
	 * @param <T> the merged type
	 *
	 * @return a merged {@link Flux}, subscribing early but keeping the original ordering
	 */
	public static <T> Flux<T> mergeSequential(Publisher<? extends Publisher<? extends T>> sources,
			int maxConcurrency, int prefetch) {
		return mergeSequential(sources, false, maxConcurrency, prefetch);
	}

	/**
	 * Merge data from {@link Publisher} sequences emitted by the passed {@link Publisher}
	 * into an ordered merged sequence. Unlike concat, the inner publishers are subscribed to
	 * eagerly (but at most {@code maxConcurrency} sources at a time). Unlike merge, their
	 * emitted values are merged into the final sequence in subscription order.
	 * This variant will delay any error until after the rest of the mergeSequential backlog has been processed.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeSequentialAsyncSources.svg" alt="">
	 *
	 * @param sources a {@link Publisher} of {@link Publisher} sources to merge
	 * @param prefetch the inner source request size
	 * @param maxConcurrency the request produced to the main source thus limiting concurrent merge backlog
	 * @param <T> the merged type
	 *
	 * @return a merged {@link Flux}, subscribing early but keeping the original ordering
	 */
	public static <T> Flux<T> mergeSequentialDelayError(Publisher<? extends Publisher<? extends T>> sources,
			int maxConcurrency, int prefetch) {
		return mergeSequential(sources, true, maxConcurrency, prefetch);
	}

	/**
	 * Merge data from {@link Publisher} sequences provided in an array/vararg
	 * into an ordered merged sequence. Unlike concat, sources are subscribed to
	 * eagerly. Unlike merge, their emitted values are merged into the final sequence in subscription order.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeSequentialVarSources.svg" alt="">
	 *
	 * @param sources a number of {@link Publisher} sequences to merge
	 * @param <I> the merged type
	 *
	 * @return a merged {@link Flux}, subscribing early but keeping the original ordering
	 */
	@SafeVarargs
	public static <I> Flux<I> mergeSequential(Publisher<? extends I>... sources) {
		return mergeSequential(Queues.XS_BUFFER_SIZE, false, sources);
	}

	/**
	 * Merge data from {@link Publisher} sequences provided in an array/vararg
	 * into an ordered merged sequence. Unlike concat, sources are subscribed to
	 * eagerly. Unlike merge, their emitted values are merged into the final sequence in subscription order.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeSequentialVarSources.svg" alt="">
	 *
	 * @param prefetch the inner source request size
	 * @param sources a number of {@link Publisher} sequences to merge
	 * @param <I> the merged type
	 *
	 * @return a merged {@link Flux}, subscribing early but keeping the original ordering
	 */
	@SafeVarargs
	public static <I> Flux<I> mergeSequential(int prefetch, Publisher<? extends I>... sources) {
		return mergeSequential(prefetch, false, sources);
	}

	/**
	 * Merge data from {@link Publisher} sequences provided in an array/vararg
	 * into an ordered merged sequence. Unlike concat, sources are subscribed to
	 * eagerly. Unlike merge, their emitted values are merged into the final sequence in subscription order.
	 * This variant will delay any error until after the rest of the mergeSequential backlog
	 * has been processed.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeSequentialVarSources.svg" alt="">
	 *
	 * @param prefetch the inner source request size
	 * @param sources a number of {@link Publisher} sequences to merge
	 * @param <I> the merged type
	 *
	 * @return a merged {@link Flux}, subscribing early but keeping the original ordering
	 */
	@SafeVarargs
	public static <I> Flux<I> mergeSequentialDelayError(int prefetch, Publisher<? extends I>... sources) {
		return mergeSequential(prefetch, true, sources);
	}

	/**
	 * Merge data from {@link Publisher} sequences provided in an {@link Iterable}
	 * into an ordered merged sequence. Unlike concat, sources are subscribed to
	 * eagerly. Unlike merge, their emitted values are merged into the final sequence in subscription order.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeSequentialVarSources.svg" alt="">
	 *
	 * @param sources an {@link Iterable} of {@link Publisher} sequences to merge
	 * @param <I> the merged type
	 *
	 * @return a merged {@link Flux}, subscribing early but keeping the original ordering
	 */
	public static <I> Flux<I> mergeSequential(Iterable<? extends Publisher<? extends I>> sources) {
		return mergeSequential(sources, false, Queues.SMALL_BUFFER_SIZE,
				Queues.XS_BUFFER_SIZE);
	}

	/**
	 * Merge data from {@link Publisher} sequences provided in an {@link Iterable}
	 * into an ordered merged sequence. Unlike concat, sources are subscribed to
	 * eagerly (but at most {@code maxConcurrency} sources at a time). Unlike merge, their
	 * emitted values are merged into the final sequence in subscription order.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeSequentialVarSources.svg" alt="">
	 *
	 * @param sources an {@link Iterable} of {@link Publisher} sequences to merge
	 * @param maxConcurrency the request produced to the main source thus limiting concurrent merge backlog
	 * @param prefetch the inner source request size
	 * @param <I> the merged type
	 *
	 * @return a merged {@link Flux}, subscribing early but keeping the original ordering
	 */
	public static <I> Flux<I> mergeSequential(Iterable<? extends Publisher<? extends I>> sources,
			int maxConcurrency, int prefetch) {
		return mergeSequential(sources, false, maxConcurrency, prefetch);
	}

	/**
	 * Merge data from {@link Publisher} sequences provided in an {@link Iterable}
	 * into an ordered merged sequence. Unlike concat, sources are subscribed to
	 * eagerly (but at most {@code maxConcurrency} sources at a time). Unlike merge, their
	 * emitted values are merged into the final sequence in subscription order.
	 * This variant will delay any error until after the rest of the mergeSequential backlog
	 * has been processed.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mergeSequentialVarSources.svg" alt="">
	 *
	 * @param sources an {@link Iterable} of {@link Publisher} sequences to merge
	 * @param maxConcurrency the request produced to the main source thus limiting concurrent merge backlog
	 * @param prefetch the inner source request size
	 * @param <I> the merged type
	 *
	 * @return a merged {@link Flux}, subscribing early but keeping the original ordering
	 */
	public static <I> Flux<I> mergeSequentialDelayError(Iterable<? extends Publisher<? extends I>> sources,
			int maxConcurrency, int prefetch) {
		return mergeSequential(sources, true, maxConcurrency, prefetch);
	}

	/**
	 * Create a {@link Flux} that will never signal any data, error or completion signal.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/never.svg" alt="">
	 *
	 * @param <T> the {@link Subscriber} type target
	 *
	 * @return a never completing {@link Flux}
	 */
	public static <T> Flux<T> never() {
		return FluxNever.instance();
	}

	/**
	 * Build a {@link Flux} that will only emit a sequence of {@code count} incrementing integers,
	 * starting from {@code start}. That is, emit integers between {@code start} (included)
	 * and {@code start + count} (excluded) then complete.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/range.svg" alt="">
	 *
	 * @param start the first integer to be emit
	 * @param count the total number of incrementing values to emit, including the first value
	 * @return a ranged {@link Flux}
	 */
	public static Flux<Integer> range(int start, int count) {
		if (count == 1) {
			return just(start);
		}
		if (count == 0) {
			return empty();
		}
		return onAssembly(new FluxRange(start, count));
	}

	/**
	 * Creates a {@link Flux} that mirrors the most recently emitted {@link Publisher},
	 * forwarding its data until a new {@link Publisher} comes in the source.
	 * <p>
	 * The resulting {@link Flux} will complete once there are no new {@link Publisher} in
	 * the source (source has completed) and the last mirrored {@link Publisher} has also
	 * completed.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/switchOnNext.svg" alt="">
	 * <p>
	 * This operator requests the {@code mergedPublishers} source for an unbounded amount of inner publishers,
	 * but doesn't request each inner {@link Publisher} unless the downstream has made
	 * a corresponding request (no prefetch on publishers emitted by {@code mergedPublishers}).
	 *
	 * @param mergedPublishers The {@link Publisher} of {@link Publisher} to switch on and mirror.
	 * @param <T> the produced type
	 *
	 * @return a {@link SinkManyAbstractBase} accepting publishers and producing T
	 */
	public static <T> Flux<T> switchOnNext(Publisher<? extends Publisher<? extends T>> mergedPublishers) {
		return onAssembly(new FluxSwitchMapNoPrefetch<>(from(mergedPublishers),
			identityFunction()));
	}

	/**
	 * Creates a {@link Flux} that mirrors the most recently emitted {@link Publisher},
	 * forwarding its data until a new {@link Publisher} comes in the source.
	 * <p>
	 * The resulting {@link Flux} will complete once there are no new {@link Publisher} in
	 * the source (source has completed) and the last mirrored {@link Publisher} has also
	 * completed.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/switchOnNext.svg" alt="">
	 *
	 * @param mergedPublishers The {@link Publisher} of {@link Publisher} to switch on and mirror.
	 * @param prefetch the inner source request size
	 * @param <T> the produced type
	 *
	 * @return a {@link SinkManyAbstractBase} accepting publishers and producing T
	 *
	 * @deprecated to be removed in 3.6.0 at the earliest. In 3.5.0, you should replace
	 * calls with prefetch=0 with calls to switchOnNext(mergedPublishers), as the default
	 * behavior of the single-parameter variant will then change to prefetch=0.
	 */
	@Deprecated
	public static <T> Flux<T> switchOnNext(Publisher<? extends Publisher<? extends T>> mergedPublishers, int prefetch) {
		if (prefetch == 0) {
			return onAssembly(new FluxSwitchMapNoPrefetch<>(from(mergedPublishers),
					identityFunction()));
		}
		return onAssembly(new FluxSwitchMap<>(from(mergedPublishers),
				identityFunction(),
				Queues.unbounded(prefetch), prefetch));
	}

	/**
	 * Uses a resource, generated by a supplier for each individual Subscriber, while streaming the values from a
	 * Publisher derived from the same resource and makes sure the resource is released if the sequence terminates or
	 * the Subscriber cancels.
	 * <p>
	 * Eager resource cleanup happens just before the source termination and exceptions raised by the cleanup Consumer
	 * may override the terminal event.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/usingForFlux.svg" alt="">
	 * <p>
	 * For an asynchronous version of the cleanup, with distinct path for onComplete, onError
	 * and cancel terminations, see {@link #usingWhen(Publisher, Function, Function, BiFunction, Function)}.
	 *
	 * @param resourceSupplier a {@link Callable} that is called on subscribe to generate the resource
	 * @param sourceSupplier a factory to derive a {@link Publisher} from the supplied resource
	 * @param resourceCleanup a resource cleanup callback invoked on completion
	 * @param <T> emitted type
	 * @param <D> resource type
	 *
	 * @return a new {@link Flux} built around a disposable resource
	 * @see #usingWhen(Publisher, Function, Function, BiFunction, Function)
	 * @see #usingWhen(Publisher, Function, Function)
	 */
	public static <T, D> Flux<T> using(Callable<? extends D> resourceSupplier, Function<? super D, ? extends
			Publisher<? extends T>> sourceSupplier, Consumer<? super D> resourceCleanup) {
		return using(resourceSupplier, sourceSupplier, resourceCleanup, true);
	}

	/**
	 * Uses a resource, generated by a supplier for each individual Subscriber, while streaming the values from a
	 * Publisher derived from the same resource and makes sure the resource is released if the sequence terminates or
	 * the Subscriber cancels.
	 * <p>
	 * <ul> <li>Eager resource cleanup happens just before the source termination and exceptions raised by the cleanup
	 * Consumer may override the terminal event.</li> <li>Non-eager cleanup will drop any exception.</li> </ul>
	 * <p>
	 * <img class="marble" src="doc-files/marbles/usingForFlux.svg" alt="">
	 * <p>
	 * For an asynchronous version of the cleanup, with distinct path for onComplete, onError
	 * and cancel terminations, see {@link #usingWhen(Publisher, Function, Function, BiFunction, Function)}.
	 *
	 * @param resourceSupplier a {@link Callable} that is called on subscribe to generate the resource
	 * @param sourceSupplier a factory to derive a {@link Publisher} from the supplied resource
	 * @param resourceCleanup a resource cleanup callback invoked on completion
	 * @param eager true to clean before terminating downstream subscribers
	 * @param <T> emitted type
	 * @param <D> resource type
	 *
	 * @return a new {@link Flux} built around a disposable resource
	 * @see #usingWhen(Publisher, Function, Function, BiFunction, Function)
	 * @see #usingWhen(Publisher, Function, Function)
	 */
	public static <T, D> Flux<T> using(Callable<? extends D> resourceSupplier, Function<? super D, ? extends
			Publisher<? extends T>> sourceSupplier, Consumer<? super D> resourceCleanup, boolean eager) {
		return onAssembly(new FluxUsing<>(resourceSupplier,
				sourceSupplier,
				resourceCleanup,
				eager));
	}

	/**
	 * Uses a resource, generated by a {@link Publisher} for each individual {@link Subscriber},
	 * while streaming the values from a {@link Publisher} derived from the same resource.
	 * Whenever the resulting sequence terminates, a provided {@link Function} generates
	 * a "cleanup" {@link Publisher} that is invoked but doesn't change the content of the
	 * main sequence. Instead it just defers the termination (unless it errors, in which case
	 * the error suppresses the original termination signal).
	 * <p>
	 * Note that if the resource supplying {@link Publisher} emits more than one resource, the
	 * subsequent resources are dropped ({@link Operators#onNextDropped(Object, Context)}). If
	 * the publisher errors AFTER having emitted one resource, the error is also silently dropped
	 * ({@link Operators#onErrorDropped(Throwable, Context)}).
	 * An empty completion or error without at least one onNext signal triggers a short-circuit
	 * of the main sequence with the same terminal signal (no resource is established, no
	 * cleanup is invoked).
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/usingWhenSuccessForFlux.svg" alt="">
	 *
	 * @param resourceSupplier a {@link Publisher} that "generates" the resource,
	 * subscribed for each subscription to the main sequence
	 * @param resourceClosure a factory to derive a {@link Publisher} from the supplied resource
	 * @param asyncCleanup an asynchronous resource cleanup invoked when the resource
	 * closure terminates (with onComplete, onError or cancel)
	 * @param <T> the type of elements emitted by the resource closure, and thus the main sequence
	 * @param <D> the type of the resource object
	 * @return a new {@link Flux} built around a "transactional" resource, with asynchronous
	 * cleanup on all terminations (onComplete, onError, cancel)
	 */
	public static <T, D> Flux<T> usingWhen(Publisher<D> resourceSupplier,
			Function<? super D, ? extends Publisher<? extends T>> resourceClosure,
			Function<? super D, ? extends Publisher<?>> asyncCleanup) {
		return usingWhen(resourceSupplier, resourceClosure, asyncCleanup, (resource, error) -> asyncCleanup.apply(resource), asyncCleanup);
	}

	/**
	 * Uses a resource, generated by a {@link Publisher} for each individual {@link Subscriber},
	 * while streaming the values from a {@link Publisher} derived from the same resource.
	 * Note that all steps of the operator chain that would need the resource to be in an open
	 * stable state need to be described inside the {@code resourceClosure} {@link Function}.
	 * <p>
	 * Whenever the resulting sequence terminates, the relevant {@link Function} generates
	 * a "cleanup" {@link Publisher} that is invoked but doesn't change the content of the
	 * main sequence. Instead it just defers the termination (unless it errors, in which case
	 * the error suppresses the original termination signal).
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/usingWhenSuccessForFlux.svg" alt="">
	 * <p>
	 * Individual cleanups can also be associated with main sequence cancellation and
	 * error terminations:
	 * <p>
	 * <img class="marble" src="doc-files/marbles/usingWhenFailureForFlux.svg" alt="">
	 * <p>
	 * Note that if the resource supplying {@link Publisher} emits more than one resource, the
	 * subsequent resources are dropped ({@link Operators#onNextDropped(Object, Context)}). If
	 * the publisher errors AFTER having emitted one resource, the error is also silently dropped
	 * ({@link Operators#onErrorDropped(Throwable, Context)}).
	 * An empty completion or error without at least one onNext signal triggers a short-circuit
	 * of the main sequence with the same terminal signal (no resource is established, no
	 * cleanup is invoked).
	 * <p>
	 * Additionally, the terminal signal is replaced by any error that might have happened
	 * in the terminating {@link Publisher}:
	 * <p>
	 * <img class="marble" src="doc-files/marbles/usingWhenCleanupErrorForFlux.svg" alt="">
	 * <p>
	 * Finally, early cancellations will cancel the resource supplying {@link Publisher}:
	 * <p>
	 * <img class="marble" src="doc-files/marbles/usingWhenEarlyCancelForFlux.svg" alt="">
	 *
	 * @param resourceSupplier a {@link Publisher} that "generates" the resource,
	 * subscribed for each subscription to the main sequence
	 * @param resourceClosure a factory to derive a {@link Publisher} from the supplied resource
	 * @param asyncComplete an asynchronous resource cleanup invoked if the resource closure terminates with onComplete
	 * @param asyncError an asynchronous resource cleanup invoked if the resource closure terminates with onError.
	 * The terminating error is provided to the {@link BiFunction}
	 * @param asyncCancel an asynchronous resource cleanup invoked if the resource closure is cancelled.
	 * When {@code null}, the {@code asyncComplete} path is used instead.
	 * @param <T> the type of elements emitted by the resource closure, and thus the main sequence
	 * @param <D> the type of the resource object
	 * @return a new {@link Flux} built around a "transactional" resource, with several
	 * termination path triggering asynchronous cleanup sequences
	 * @see #usingWhen(Publisher, Function, Function)
	 */
	public static <T, D> Flux<T> usingWhen(Publisher<D> resourceSupplier,
			Function<? super D, ? extends Publisher<? extends T>> resourceClosure,
			Function<? super D, ? extends Publisher<?>> asyncComplete,
			BiFunction<? super D, ? super Throwable, ? extends Publisher<?>> asyncError,
			//the operator itself accepts null for asyncCancel, but we won't in the public API
			Function<? super D, ? extends Publisher<?>> asyncCancel) {
		return onAssembly(new FluxUsingWhen<>(resourceSupplier, resourceClosure,
				asyncComplete, asyncError, asyncCancel));
	}

	/**
	 * Zip two sources together, that is to say wait for all the sources to emit one
	 * element and combine these elements once into an output value (constructed by the provided
	 * combinator). The operator will continue doing so until any of the sources completes.
	 * Errors will immediately be forwarded.
	 * This "Step-Merge" processing is especially useful in Scatter-Gather scenarios.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/zipTwoSourcesWithZipperForFlux.svg" alt="">
	 *
	 * @param source1 The first {@link Publisher} source to zip.
	 * @param source2 The second {@link Publisher} source to zip.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 * value to signal downstream
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <O> The produced output after transformation by the combinator
	 *
	 * @return a zipped {@link Flux}
	 */
    public static <T1, T2, O> Flux<O> zip(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			final BiFunction<? super T1, ? super T2, ? extends O> combinator) {

		return onAssembly(new FluxZip<T1, O>(source1,
				source2,
				combinator,
				Queues.xs(),
				Queues.XS_BUFFER_SIZE));
	}

	/**
	 * Zip two sources together, that is to say wait for all the sources to emit one
	 * element and combine these elements once into a {@link Tuple2}.
	 * The operator will continue doing so until any of the sources completes.
	 * Errors will immediately be forwarded.
	 * This "Step-Merge" processing is especially useful in Scatter-Gather scenarios.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/zipFixedSourcesForFlux.svg" alt="">
	 *
	 * @param source1 The first {@link Publisher} source to zip.
	 * @param source2 The second {@link Publisher} source to zip.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 *
	 * @return a zipped {@link Flux}
	 */
	public static <T1, T2> Flux<Tuple2<T1, T2>> zip(Publisher<? extends T1> source1, Publisher<? extends T2> source2) {
		return zip(source1, source2, tuple2Function());
	}

	/**
	 * Zip three sources together, that is to say wait for all the sources to emit one
	 * element and combine these elements once into a {@link Tuple3}.
	 * The operator will continue doing so until any of the sources completes.
	 * Errors will immediately be forwarded.
	 * This "Step-Merge" processing is especially useful in Scatter-Gather scenarios.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/zipFixedSourcesForFlux.svg" alt="">
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 *
	 * @return a zipped {@link Flux}
	 */
	public static <T1, T2, T3> Flux<Tuple3<T1, T2, T3>> zip(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3) {
		return zip(Tuples.fn3(), source1, source2, source3);
	}

	/**
	 * Zip four sources together, that is to say wait for all the sources to emit one
	 * element and combine these elements once into a {@link Tuple4}.
	 * The operator will continue doing so until any of the sources completes.
	 * Errors will immediately be forwarded.
	 * This "Step-Merge" processing is especially useful in Scatter-Gather scenarios.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/zipFixedSourcesForFlux.svg" alt="">
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 *
	 * @return a zipped {@link Flux}
	 */
	public static <T1, T2, T3, T4> Flux<Tuple4<T1, T2, T3, T4>> zip(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4) {
		return zip(Tuples.fn4(), source1, source2, source3, source4);
	}

	/**
	 * Zip five sources together, that is to say wait for all the sources to emit one
	 * element and combine these elements once into a {@link Tuple5}.
	 * The operator will continue doing so until any of the sources completes.
	 * Errors will immediately be forwarded.
	 * This "Step-Merge" processing is especially useful in Scatter-Gather scenarios.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/zipFixedSourcesForFlux.svg" alt="">
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5 The fifth upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 * @param <T5> type of the value from source5
	 *
	 * @return a zipped {@link Flux}
	 */
	public static <T1, T2, T3, T4, T5> Flux<Tuple5<T1, T2, T3, T4, T5>> zip(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4,
			Publisher<? extends T5> source5) {
		return zip(Tuples.fn5(), source1, source2, source3, source4, source5);
	}

	/**
	 * Zip six sources together, that is to say wait for all the sources to emit one
	 * element and combine these elements once into a {@link Tuple6}.
	 * The operator will continue doing so until any of the sources completes.
	 * Errors will immediately be forwarded.
	 * This "Step-Merge" processing is especially useful in Scatter-Gather scenarios.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/zipFixedSourcesForFlux.svg" alt="">
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5 The fifth upstream {@link Publisher} to subscribe to.
	 * @param source6 The sixth upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 * @param <T5> type of the value from source5
	 * @param <T6> type of the value from source6
	 *
	 * @return a zipped {@link Flux}
	 */
	public static <T1, T2, T3, T4, T5, T6> Flux<Tuple6<T1, T2, T3, T4, T5, T6>> zip(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4,
			Publisher<? extends T5> source5,
			Publisher<? extends T6> source6) {
		return zip(Tuples.fn6(), source1, source2, source3, source4, source5, source6);
	}

	/**
	 * Zip seven sources together, that is to say wait for all the sources to emit one
	 * element and combine these elements once into a {@link Tuple7}.
	 * The operator will continue doing so until any of the sources completes.
	 * Errors will immediately be forwarded.
	 * This "Step-Merge" processing is especially useful in Scatter-Gather scenarios.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/zipFixedSourcesForFlux.svg" alt="">
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5 The fifth upstream {@link Publisher} to subscribe to.
	 * @param source6 The sixth upstream {@link Publisher} to subscribe to.
	 * @param source7 The seventh upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 * @param <T5> type of the value from source5
	 * @param <T6> type of the value from source6
	 * @param <T7> type of the value from source7
	 *
	 * @return a zipped {@link Flux}
	 */
	public static <T1, T2, T3, T4, T5, T6, T7> Flux<Tuple7<T1, T2, T3, T4, T5, T6, T7>> zip(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4,
			Publisher<? extends T5> source5,
			Publisher<? extends T6> source6,
			Publisher<? extends T7> source7) {
		return zip(Tuples.fn7(), source1, source2, source3, source4, source5, source6, source7);
	}

	/**
	 * Zip eight sources together, that is to say wait for all the sources to emit one
	 * element and combine these elements once into a {@link Tuple8}.
	 * The operator will continue doing so until any of the sources completes.
	 * Errors will immediately be forwarded.
	 * This "Step-Merge" processing is especially useful in Scatter-Gather scenarios.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/zipFixedSourcesForFlux.svg" alt="">
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5 The fifth upstream {@link Publisher} to subscribe to.
	 * @param source6 The sixth upstream {@link Publisher} to subscribe to.
	 * @param source7 The seventh upstream {@link Publisher} to subscribe to.
	 * @param source8 The eight upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 * @param <T5> type of the value from source5
	 * @param <T6> type of the value from source6
	 * @param <T7> type of the value from source7
	 * @param <T8> type of the value from source8
	 *
	 * @return a zipped {@link Flux}
	 */
	public static <T1, T2, T3, T4, T5, T6, T7, T8> Flux<Tuple8<T1, T2, T3, T4, T5, T6, T7, T8>> zip(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4,
			Publisher<? extends T5> source5,
			Publisher<? extends T6> source6,
			Publisher<? extends T7> source7,
			Publisher<? extends T8> source8) {
		return zip(Tuples.fn8(), source1, source2, source3, source4, source5, source6, source7, source8);
	}

	/**
	 * Zip multiple sources together, that is to say wait for all the sources to emit one
	 * element and combine these elements once into an output value (constructed by the provided
	 * combinator).
	 * The operator will continue doing so until any of the sources completes.
	 * Errors will immediately be forwarded.
	 * This "Step-Merge" processing is especially useful in Scatter-Gather scenarios.
	 *
	 * The {@link Iterable#iterator()} will be called on each {@link Publisher#subscribe(Subscriber)}.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/zipIterableSourcesForFlux.svg" alt="">
	 *
	 * @param sources the {@link Iterable} providing sources to zip
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <O> the combined produced type
	 *
	 * @return a zipped {@link Flux}
	 */
	public static <O> Flux<O> zip(Iterable<? extends Publisher<?>> sources,
			final Function<? super Object[], ? extends O> combinator) {

		return zip(sources, Queues.XS_BUFFER_SIZE, combinator);
	}

	/**
	 * Zip multiple sources together, that is to say wait for all the sources to emit one
	 * element and combine these elements once into an output value (constructed by the provided
	 * combinator).
	 * The operator will continue doing so until any of the sources completes.
	 * Errors will immediately be forwarded.
	 * This "Step-Merge" processing is especially useful in Scatter-Gather scenarios.
	 *
	 * The {@link Iterable#iterator()} will be called on each {@link Publisher#subscribe(Subscriber)}.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/zipIterableSourcesForFlux.svg" alt="">
	 *
	 * @param sources the {@link Iterable} providing sources to zip
	 * @param prefetch the inner source request size
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <O> the combined produced type
	 *
	 * @return a zipped {@link Flux}
	 */
	public static <O> Flux<O> zip(Iterable<? extends Publisher<?>> sources,
			int prefetch,
			final Function<? super Object[], ? extends O> combinator) {

		return onAssembly(new FluxZip<>(sources,
			combinator,
			Queues.get(prefetch),
			prefetch));
	}

	/**
	 * Zip multiple sources together, that is to say wait for all the sources to emit one
	 * element and combine these elements once into an output value (constructed by the provided
	 * combinator).
	 * The operator will continue doing so until any of the sources completes.
	 * Errors will immediately be forwarded.
	 * This "Step-Merge" processing is especially useful in Scatter-Gather scenarios.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/zipIterableSourcesForFlux.svg" alt="">
	 *
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 * value to signal downstream
	 * @param sources the array providing sources to zip
	 * @param <I> the type of the input sources
	 * @param <O> the combined produced type
	 *
	 * @return a zipped {@link Flux}
	 */
	@SafeVarargs
	public static <I, O> Flux<O> zip(
			final Function<? super Object[], ? extends O> combinator, Publisher<? extends I>... sources) {
		return zip(combinator, Queues.XS_BUFFER_SIZE, sources);
	}

	/**
	 * Zip multiple sources together, that is to say wait for all the sources to emit one
	 * element and combine these elements once into an output value (constructed by the provided
	 * combinator).
	 * The operator will continue doing so until any of the sources completes.
	 * Errors will immediately be forwarded.
	 * This "Step-Merge" processing is especially useful in Scatter-Gather scenarios.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/zipIterableSourcesForFlux.svg" alt="">
	 *
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 * value to signal downstream
	 * @param prefetch individual source request size
	 * @param sources the array providing sources to zip
	 * @param <I> the type of the input sources
	 * @param <O> the combined produced type
	 *
	 * @return a zipped {@link Flux}
	 */
	@SafeVarargs
	public static <I, O> Flux<O> zip(final Function<? super Object[], ? extends O> combinator,
			int prefetch,
			Publisher<? extends I>... sources) {

		if (sources.length == 0) {
			return empty();
		}
		if (sources.length == 1) {
		    Publisher<? extends I> source = sources[0];
		    if (source instanceof Fuseable) {
			    return onAssembly(new FluxMapFuseable<>(from(source),
					    v -> combinator.apply(new Object[]{v})));
		    }
			return onAssembly(new FluxMap<>(from(source),
					v -> combinator.apply(new Object[]{v})));
		}

		return onAssembly(new FluxZip<>(sources,
				combinator,
				Queues.get(prefetch),
				prefetch));
	}

	/**
	 * Zip multiple sources together, that is to say wait for all the sources to emit one
	 * element and combine these elements once into an output value (constructed by the provided
	 * combinator).
	 * The operator will continue doing so until any of the sources completes.
	 * Errors will immediately be forwarded.
	 * This "Step-Merge" processing is especially useful in Scatter-Gather scenarios.
	 * <p>
	 * Note that the {@link Publisher} sources from the outer {@link Publisher} will
	 * accumulate into an exhaustive list before starting zip operation.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/zipAsyncSourcesForFlux.svg" alt="">
	 *
	 * @param sources The {@link Publisher} of {@link Publisher} sources to zip. A finite publisher is required.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <TUPLE> the raw tuple type
	 * @param <V> The produced output after transformation by the given combinator
	 *
	 * @return a {@link Flux} based on the produced value
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
    public static <TUPLE extends Tuple2, V> Flux<V> zip(Publisher<? extends
			Publisher<?>> sources,
			final Function<? super TUPLE, ? extends V> combinator) {

		return onAssembly(new FluxBuffer<>(from(sources), Integer.MAX_VALUE, listSupplier())
		                    .flatMap(new Function<List<? extends Publisher<?>>, Publisher<V>>() {
			                    @Override
			                    public Publisher<V> apply(List<? extends Publisher<?>> publishers) {
				                    return zip(Tuples.fnAny((Function<Tuple2, V>)
						                    combinator), publishers.toArray(new Publisher[publishers
						                    .size()]));
			                    }
		                    }));
	}

	/**
	 *
	 * Emit a single boolean true if all values of this sequence match
	 * the {@link Predicate}.
	 * <p>
	 * The implementation uses short-circuit logic and completes with false if
	 * the predicate doesn't match a value.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/all.svg" alt="">
	 *
	 * @param predicate the {@link Predicate} that needs to apply to all emitted items
	 *
	 * @return a new {@link Mono} with <code>true</code> if all values satisfies a predicate and <code>false</code>
	 * otherwise
	 */
	public final Mono<Boolean> all(Predicate<? super T> predicate) {
		return Mono.onAssembly(new MonoAll<>(this, predicate));
	}

	/**
	 * Emit a single boolean true if any of the values of this {@link Flux} sequence match
	 * the predicate.
	 * <p>
	 * The implementation uses short-circuit logic and completes with true if
	 * the predicate matches a value.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/any.svg" alt="">
	 *
	 * @param predicate the {@link Predicate} that needs to apply to at least one emitted item
	 *
	 * @return a new {@link Mono} with <code>true</code> if any value satisfies a predicate and <code>false</code>
	 * otherwise
	 */
	public final Mono<Boolean> any(Predicate<? super T> predicate) {
		return Mono.onAssembly(new MonoAny<>(this, predicate));
	}

	/**
	 * Transform this {@link Flux} into a target type.
	 * <blockquote><pre>
	 * {@code flux.as(Mono::from).subscribe() }
	 * </pre></blockquote>
	 *
	 * @param transformer the {@link Function} to immediately map this {@link Flux}
	 * into a target type instance.
	 * @param <P> the returned instance type
	 *
	 * @return the {@link Flux} transformed to an instance of P
	 * @see #transformDeferred(Function) transformDeferred(Function) for a lazy transformation of Flux
	 */
	public final <P> P as(Function<? super Flux<T>, P> transformer) {
		return transformer.apply(this);
	}

	/**
	 * Subscribe to this {@link Flux} and <strong>block indefinitely</strong>
	 * until the upstream signals its first value or completes. Returns that value,
	 * or null if the Flux completes empty. In case the Flux errors, the original
	 * exception is thrown (wrapped in a {@link RuntimeException} if it was a checked
	 * exception).
	 * <p>
	 * Note that each blockFirst() will trigger a new subscription: in other words,
	 * the result might miss signal from hot publishers.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/blockFirst.svg" alt="">
	 *
	 * @return the first value or null
	 */
	
	public final T blockFirst() {
		BlockingFirstSubscriber<T> subscriber = new BlockingFirstSubscriber<>();
		subscribe((Subscriber<T>) subscriber);
		return subscriber.blockingGet();
	}

	/**
	 * Subscribe to this {@link Flux} and <strong>block</strong> until the upstream
	 * signals its first value, completes or a timeout expires. Returns that value,
	 * or null if the Flux completes empty. In case the Flux errors, the original
	 * exception is thrown (wrapped in a {@link RuntimeException} if it was a checked
	 * exception). If the provided timeout expires, a {@link RuntimeException} is thrown.
	 * <p>
	 * Note that each blockFirst() will trigger a new subscription: in other words,
	 * the result might miss signal from hot publishers.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/blockFirstWithTimeout.svg" alt="">
	 *
 	 * @param timeout maximum time period to wait for before raising a {@link RuntimeException}
	 * @return the first value or null
	 */
	
	public final T blockFirst(Duration timeout) {
		BlockingFirstSubscriber<T> subscriber = new BlockingFirstSubscriber<>();
		subscribe((Subscriber<T>) subscriber);
		return subscriber.blockingGet(timeout.toNanos(), TimeUnit.NANOSECONDS);
	}

	/**
	 * Subscribe to this {@link Flux} and <strong>block indefinitely</strong>
	 * until the upstream signals its last value or completes. Returns that value,
	 * or null if the Flux completes empty. In case the Flux errors, the original
	 * exception is thrown (wrapped in a {@link RuntimeException} if it was a checked
	 * exception).
	 * <p>
	 * Note that each blockLast() will trigger a new subscription: in other words,
	 * the result might miss signal from hot publishers.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/blockLast.svg" alt="">
	 *
	 * @return the last value or null
	 */
	
	public final T blockLast() {
		BlockingLastSubscriber<T> subscriber = new BlockingLastSubscriber<>();
		subscribe((Subscriber<T>) subscriber);
		return subscriber.blockingGet();
	}


	/**
	 * Subscribe to this {@link Flux} and <strong>block</strong> until the upstream
	 * signals its last value, completes or a timeout expires. Returns that value,
	 * or null if the Flux completes empty. In case the Flux errors, the original
	 * exception is thrown (wrapped in a {@link RuntimeException} if it was a checked
	 * exception). If the provided timeout expires, a {@link RuntimeException} is thrown.
	 * <p>
	 * Note that each blockLast() will trigger a new subscription: in other words,
	 * the result might miss signal from hot publishers.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/blockLastWithTimeout.svg" alt="">
	 *
	 * @param timeout maximum time period to wait for before raising a {@link RuntimeException}
	 * @return the last value or null
	 */
	
	public final T blockLast(Duration timeout) {
		BlockingLastSubscriber<T> subscriber = new BlockingLastSubscriber<>();
		subscribe((Subscriber<T>) subscriber);
		return subscriber.blockingGet(timeout.toNanos(), TimeUnit.NANOSECONDS);
	}

	/**
	 * Collect all incoming values into a single {@link List} buffer that will be emitted
	 * by the returned {@link Flux} once this Flux completes.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/buffer.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the buffer upon cancellation or error triggered by a data signal.
	 *
	 * @return a buffered {@link Flux} of at most one {@link List}
	 * @see #collectList() for an alternative collecting algorithm returning {@link Mono}
	 */
    public final Flux<List<T>> buffer() {
	    return buffer(Integer.MAX_VALUE);
	}

	/**
	 * Collect incoming values into multiple {@link List} buffers that will be emitted
	 * by the returned {@link Flux} each time the given max size is reached or once this
	 * Flux completes.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithMaxSize.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 *
	 * @param maxSize the maximum collected size
	 *
	 * @return a microbatched {@link Flux} of {@link List}
	 */
	public final Flux<List<T>> buffer(int maxSize) {
		return buffer(maxSize, listSupplier());
	}

	/**
	 * Collect incoming values into multiple user-defined {@link Collection} buffers that
	 * will be emitted by the returned {@link Flux} each time the given max size is reached
	 * or once this Flux completes.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithMaxSize.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal,
	 * as well as latest unbuffered element if the bufferSupplier fails.
	 *
	 * @param maxSize the maximum collected size
	 * @param bufferSupplier a {@link Supplier} of the concrete {@link Collection} to use for each buffer
	 * @param <C> the {@link Collection} buffer type
	 *
	 * @return a microbatched {@link Flux} of {@link Collection}
	 */
	public final <C extends Collection<? super T>> Flux<C> buffer(int maxSize, Supplier<C> bufferSupplier) {
		return onAssembly(new FluxBuffer<>(this, maxSize, bufferSupplier));
	}

	/**
	 * Collect incoming values into multiple {@link List} buffers that will be emitted
	 * by the returned {@link Flux} each time the given max size is reached or once this
	 * Flux completes. Buffers can be created with gaps, as a new buffer will be created
	 * every time {@code skip} values have been emitted by the source.
	 * <p>
	 * When maxSize < skip : dropping buffers
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithMaxSizeLessThanSkipSize.svg" alt="">
	 * <p>
	 * When maxSize > skip : overlapping buffers
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithMaxSizeGreaterThanSkipSize.svg" alt="">
	 * <p>
	 * When maxSize == skip : exact buffers
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithMaxSizeEqualsSkipSize.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements in between buffers (in the case of
	 * dropping buffers). It also discards the currently open buffer upon cancellation or error triggered by a data signal.
	 * Note however that overlapping buffer variant DOES NOT discard, as this might result in an element
	 * being discarded from an early buffer while it is still valid in a more recent buffer.
	 *
	 * @param skip the number of items to count before creating a new buffer
	 * @param maxSize the max collected size
	 *
	 * @return a microbatched {@link Flux} of possibly overlapped or gapped {@link List}
	 */
	public final Flux<List<T>> buffer(int maxSize, int skip) {
		return buffer(maxSize, skip, listSupplier());
	}

	/**
	 * Collect incoming values into multiple user-defined {@link Collection} buffers that
	 * will be emitted by the returned {@link Flux} each time the given max size is reached
	 * or once this Flux completes. Buffers can be created with gaps, as a new buffer will
	 * be created every time {@code skip} values have been emitted by the source
	 * <p>
	 * When maxSize < skip : dropping buffers
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithMaxSizeLessThanSkipSize.svg" alt="">
	 * <p>
	 * When maxSize > skip : overlapping buffers
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithMaxSizeGreaterThanSkipSize.svg" alt="">
	 * <p>
	 * When maxSize == skip : exact buffers
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithMaxSizeEqualsSkipSize.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements in between buffers (in the case of
	 * dropping buffers). It also discards the currently open buffer upon cancellation or error triggered by a data signal.
	 * Note however that overlapping buffer variant DOES NOT discard, as this might result in an element
	 * being discarded from an early buffer while it is still valid in a more recent buffer.
	 *
	 * @param skip the number of items to count before creating a new buffer
	 * @param maxSize the max collected size
	 * @param bufferSupplier a {@link Supplier} of the concrete {@link Collection} to use for each buffer
	 * @param <C> the {@link Collection} buffer type
	 *
	 * @return a microbatched {@link Flux} of possibly overlapped or gapped
	 * {@link Collection}
	 */
	public final <C extends Collection<? super T>> Flux<C> buffer(int maxSize,
			int skip, Supplier<C> bufferSupplier) {
		return onAssembly(new FluxBuffer<>(this, maxSize, skip, bufferSupplier));
	}

	/**
	 * Collect incoming values into multiple {@link List} buffers, as delimited by the
	 * signals of a companion {@link Publisher} this operator will subscribe to.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithBoundary.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 *
	 * @param other the companion {@link Publisher} whose signals trigger new buffers
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by signals from a {@link Publisher}
	 */
	public final Flux<List<T>> buffer(Publisher<?> other) {
		return buffer(other, listSupplier());
	}

	/**
	 * Collect incoming values into multiple user-defined {@link Collection} buffers, as
	 * delimited by the signals of a companion {@link Publisher} this operator will
	 * subscribe to.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithBoundary.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal,
	 * and the last received element when the bufferSupplier fails.
	 *
	 * @param other the companion {@link Publisher} whose signals trigger new buffers
	 * @param bufferSupplier a {@link Supplier} of the concrete {@link Collection} to use for each buffer
	 * @param <C> the {@link Collection} buffer type
	 *
	 * @return a microbatched {@link Flux} of {@link Collection} delimited by signals from a {@link Publisher}
	 */
	public final <C extends Collection<? super T>> Flux<C> buffer(Publisher<?> other, Supplier<C> bufferSupplier) {
		return onAssembly(new FluxBufferBoundary<>(this, other, bufferSupplier));
	}

	/**
	 * Collect incoming values into multiple {@link List} buffers that will be emitted by
	 * the returned {@link Flux} every {@code bufferingTimespan}.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithTimespan.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 *
	 * @param bufferingTimespan the duration from buffer creation until a buffer is closed and emitted
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by the given time span
	 */
	public final Flux<List<T>> buffer(Duration bufferingTimespan) {
		return buffer(bufferingTimespan, Schedulers.parallel());
	}

	/**
	 * Collect incoming values into multiple {@link List} buffers created at a given
	 * {@code openBufferEvery} period. Each buffer will last until the {@code bufferingTimespan} has elapsed,
	 * thus emitting the bucket in the resulting {@link Flux}.
	 * <p>
	 * When bufferingTimespan < openBufferEvery : dropping buffers
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithTimespanLessThanOpenBufferEvery.svg" alt="">
	 * <p>
	 * When bufferingTimespan > openBufferEvery : overlapping buffers
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithTimespanGreaterThanOpenBufferEvery.svg" alt="">
	 * <p>
	 * When bufferingTimespan == openBufferEvery : exact buffers
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithTimespanEqualsOpenBufferEvery.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 * It DOES NOT provide strong guarantees in the case of overlapping buffers, as elements
	 * might get discarded too early (from the first of two overlapping buffers for instance).
	 *
	 * @param bufferingTimespan the duration from buffer creation until a buffer is closed and emitted
	 * @param openBufferEvery the interval at which to create a new buffer
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by the given period openBufferEvery and sized by bufferingTimespan
	 */
	public final Flux<List<T>> buffer(Duration bufferingTimespan, Duration openBufferEvery) {
		return buffer(bufferingTimespan, openBufferEvery, Schedulers.parallel());
	}

	/**
	 * Collect incoming values into multiple {@link List} buffers that will be emitted by
	 * the returned {@link Flux} every {@code bufferingTimespan}, as measured on the provided {@link Scheduler}.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithTimespan.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 *
	 * @param bufferingTimespan the duration from buffer creation until a buffer is closed and emitted
	 * @param timer a time-capable {@link Scheduler} instance to run on
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by the given period
	 */
	public final Flux<List<T>> buffer(Duration bufferingTimespan, Scheduler timer) {
		return buffer(interval(bufferingTimespan, timer));
	}

	/**
	 * Collect incoming values into multiple {@link List} buffers created at a given
	 * {@code openBufferEvery} period, as measured on the provided {@link Scheduler}. Each
	 * buffer will last until the {@code bufferingTimespan} has elapsed (also measured on the scheduler),
	 * thus emitting the bucket in the resulting {@link Flux}.
	 * <p>
	 * When bufferingTimespan < openBufferEvery : dropping buffers
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithTimespanLessThanOpenBufferEvery.svg" alt="">
	 * <p>
	 * When bufferingTimespan > openBufferEvery : overlapping buffers
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithTimespanGreaterThanOpenBufferEvery.svg" alt="">
	 * <p>
	 * When bufferingTimespan == openBufferEvery : exact buffers
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWithTimespanEqualsOpenBufferEvery.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 * It DOES NOT provide strong guarantees in the case of overlapping buffers, as elements
	 * might get discarded too early (from the first of two overlapping buffers for instance).
	 *
	 * @param bufferingTimespan the duration from buffer creation until a buffer is closed and emitted
	 * @param openBufferEvery the interval at which to create a new buffer
	 * @param timer a time-capable {@link Scheduler} instance to run on
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by the given period openBufferEvery and sized by bufferingTimespan
	 */
	public final Flux<List<T>> buffer(Duration bufferingTimespan, Duration openBufferEvery, Scheduler timer) {
		if (bufferingTimespan.equals(openBufferEvery)) {
			return buffer(bufferingTimespan, timer);
		}
		return bufferWhen(interval(Duration.ZERO, openBufferEvery, timer), aLong -> Mono
				.delay(bufferingTimespan, timer));
	}

	/**
	 * Collect incoming values into multiple {@link List} buffers that will be emitted
	 * by the returned {@link Flux} each time the buffer reaches a maximum size OR the
	 * maxTime {@link Duration} elapses.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferTimeoutWithMaxSizeAndTimespan.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 *
	 * @param maxSize the max collected size
	 * @param maxTime the timeout enforcing the release of a partial buffer
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by given size or a given period timeout
	 */
	public final Flux<List<T>> bufferTimeout(int maxSize, Duration maxTime) {
		return bufferTimeout(maxSize, maxTime, listSupplier());
	}

	/**
	 * Collect incoming values into multiple user-defined {@link Collection} buffers that
	 * will be emitted by the returned {@link Flux} each time the buffer reaches a maximum
	 * size OR the maxTime {@link Duration} elapses.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferTimeoutWithMaxSizeAndTimespan.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 *
	 * @param maxSize the max collected size
	 * @param maxTime the timeout enforcing the release of a partial buffer
	 * @param bufferSupplier a {@link Supplier} of the concrete {@link Collection} to use for each buffer
	 * @param <C> the {@link Collection} buffer type
	 *
	 * @return a microbatched {@link Flux} of {@link Collection} delimited by given size or a given period timeout
	 */
	public final <C extends Collection<? super T>> Flux<C> bufferTimeout(int maxSize, Duration maxTime, Supplier<C> bufferSupplier) {
		return bufferTimeout(maxSize, maxTime, Schedulers.parallel(),
				bufferSupplier);
	}

	/**
	 * Collect incoming values into multiple {@link List} buffers that will be emitted
	 * by the returned {@link Flux} each time the buffer reaches a maximum size OR the
	 * maxTime {@link Duration} elapses, as measured on the provided {@link Scheduler}.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferTimeoutWithMaxSizeAndTimespan.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 *
	 * @param maxSize the max collected size
	 * @param maxTime the timeout enforcing the release of a partial buffer
	 * @param timer a time-capable {@link Scheduler} instance to run on
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by given size or a given period timeout
	 */
	public final Flux<List<T>> bufferTimeout(int maxSize, Duration maxTime, Scheduler timer) {
		return bufferTimeout(maxSize, maxTime, timer, listSupplier());
	}

	/**
	 * Collect incoming values into multiple user-defined {@link Collection} buffers that
	 * will be emitted by the returned {@link Flux} each time the buffer reaches a maximum
	 * size OR the maxTime {@link Duration} elapses, as measured on the provided {@link Scheduler}.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferTimeoutWithMaxSizeAndTimespan.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 *
	 * @param maxSize the max collected size
	 * @param maxTime the timeout enforcing the release of a partial buffer
	 * @param timer a time-capable {@link Scheduler} instance to run on
	 * @param bufferSupplier a {@link Supplier} of the concrete {@link Collection} to use for each buffer
	 * @param <C> the {@link Collection} buffer type
	 *
	 * @return a microbatched {@link Flux} of {@link Collection} delimited by given size or a given period timeout
	 */
	public final  <C extends Collection<? super T>> Flux<C> bufferTimeout(int maxSize, Duration maxTime,
			Scheduler timer, Supplier<C> bufferSupplier) {
		return onAssembly(new FluxBufferTimeout<>(this, maxSize, maxTime.toNanos(), TimeUnit.NANOSECONDS, timer, bufferSupplier));
	}

	/**
	 * Collect incoming values into multiple {@link List} buffers that will be emitted by
	 * the resulting {@link Flux} each time the given predicate returns true. Note that
	 * the element that triggers the predicate to return true (and thus closes a buffer)
	 * is included as last element in the emitted buffer.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferUntil.svg" alt="">
	 * <p>
	 * On completion, if the latest buffer is non-empty and has not been closed it is
	 * emitted. However, such a "partial" buffer isn't emitted in case of onError
	 * termination.
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 *
	 * @param predicate a predicate that triggers the next buffer when it becomes true.
	 *
	 * @return a microbatched {@link Flux} of {@link List}
	 */
	public final Flux<List<T>> bufferUntil(Predicate<? super T> predicate) {
		return onAssembly(new FluxBufferPredicate<>(this, predicate,
				listSupplier(), FluxBufferPredicate.Mode.UNTIL));
	}

	/**
	 * Collect incoming values into multiple {@link List} buffers that will be emitted by
	 * the resulting {@link Flux} each time the given predicate returns true. Note that
	 * the buffer into which the element that triggers the predicate to return true
	 * (and thus closes a buffer) is included depends on the {@code cutBefore} parameter:
	 * set it to true to include the boundary element in the newly opened buffer, false to
	 * include it in the closed buffer (as in {@link #bufferUntil(Predicate)}).
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferUntilWithCutBefore.svg" alt="">
	 * <p>
	 * On completion, if the latest buffer is non-empty and has not been closed it is
	 * emitted. However, such a "partial" buffer isn't emitted in case of onError
	 * termination.
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 *
	 * @param predicate a predicate that triggers the next buffer when it becomes true.
	 * @param cutBefore set to true to include the triggering element in the new buffer rather than the old.
	 *
	 * @return a microbatched {@link Flux} of {@link List}
	 */
	public final Flux<List<T>> bufferUntil(Predicate<? super T> predicate, boolean cutBefore) {
		return onAssembly(new FluxBufferPredicate<>(this, predicate, listSupplier(),
				cutBefore ? FluxBufferPredicate.Mode.UNTIL_CUT_BEFORE
						  : FluxBufferPredicate.Mode.UNTIL));
	}

	/**
	 * Collect subsequent repetitions of an element (that is, if they arrive right after
	 * one another) into multiple {@link List} buffers that will be emitted by the
	 * resulting {@link Flux}.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferUntilChanged.svg" alt="">
	 * <p>
	 *
	 * @return a microbatched {@link Flux} of {@link List}
	 */
	public final Flux<List<T>> bufferUntilChanged() {
		return bufferUntilChanged(identityFunction());
	}

	/**
	 * Collect subsequent repetitions of an element (that is, if they arrive right after
	 * one another), as compared by a key extracted through the user provided {@link
	 * Function}, into multiple {@link List} buffers that will be emitted by the
	 * resulting {@link Flux}.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferUntilChangedWithKey.svg" alt="">
	 * <p>
	 *
	 * @param keySelector function to compute comparison key for each element
	 * @return a microbatched {@link Flux} of {@link List}
	 */
	public final <V> Flux<List<T>> bufferUntilChanged(Function<? super T, ? extends V> keySelector) {
		return bufferUntilChanged(keySelector, equalPredicate());
	}

	/**
	 * Collect subsequent repetitions of an element (that is, if they arrive right after
	 * one another), as compared by a key extracted through the user provided {@link
	 * Function} and compared using a supplied {@link BiPredicate}, into multiple
	 * {@link List} buffers that will be emitted by the resulting {@link Flux}.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferUntilChangedWithKey.svg" alt="">
	 * <p>
	 *
	 * @param keySelector function to compute comparison key for each element
	 * @param keyComparator predicate used to compare keys
	 * @return a microbatched {@link Flux} of {@link List}
	 */
	public final <V> Flux<List<T>> bufferUntilChanged(Function<? super T, ? extends V> keySelector,
			BiPredicate<? super V, ? super V> keyComparator) {
		return Flux.defer(() -> bufferUntil(new FluxBufferPredicate.ChangedPredicate<T, V>(keySelector,
				keyComparator), true));
	}

	/**
	 * Collect incoming values into multiple {@link List} buffers that will be emitted by
	 * the resulting {@link Flux}. Each buffer continues aggregating values while the
	 * given predicate returns true, and a new buffer is created as soon as the
	 * predicate returns false... Note that the element that triggers the predicate
	 * to return false (and thus closes a buffer) is NOT included in any emitted buffer.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWhile.svg" alt="">
	 * <p>
	 * On completion, if the latest buffer is non-empty and has not been closed it is
	 * emitted. However, such a "partial" buffer isn't emitted in case of onError
	 * termination.
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal,
	 * as well as the buffer-triggering element.
	 *
	 * @param predicate a predicate that triggers the next buffer when it becomes false.
	 *
	 * @return a microbatched {@link Flux} of {@link List}
	 */
	public final Flux<List<T>> bufferWhile(Predicate<? super T> predicate) {
		return onAssembly(new FluxBufferPredicate<>(this, predicate,
				listSupplier(), FluxBufferPredicate.Mode.WHILE));
	}

	/**
	 * Collect incoming values into multiple {@link List} buffers started each time an opening
	 * companion {@link Publisher} emits. Each buffer will last until the corresponding
	 * closing companion {@link Publisher} emits, thus releasing the buffer to the resulting {@link Flux}.
	 * <p>
	 * When Open signal is strictly not overlapping Close signal : dropping buffers (see green marbles in diagram below).
	 * <p>
	 * When Open signal is strictly more frequent than Close signal : overlapping buffers (see second and third buffers in diagram below).
	 * <p>
	 * When Open signal is exactly coordinated with Close signal : exact buffers
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWhen.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 * It DOES NOT provide strong guarantees in the case of overlapping buffers, as elements
	 * might get discarded too early (from the first of two overlapping buffers for instance).
	 *
	 * @param bucketOpening a companion {@link Publisher} to subscribe for buffer creation signals.
	 * @param closeSelector a factory that, given a buffer opening signal, returns a companion
	 * {@link Publisher} to subscribe to for buffer closure and emission signals.
	 * @param <U> the element type of the buffer-opening sequence
	 * @param <V> the element type of the buffer-closing sequence
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by an opening {@link Publisher} and a relative
	 * closing {@link Publisher}
	 */
	public final <U, V> Flux<List<T>> bufferWhen(Publisher<U> bucketOpening,
			Function<? super U, ? extends Publisher<V>> closeSelector) {
		return bufferWhen(bucketOpening, closeSelector, listSupplier());
	}

	/**
	 * Collect incoming values into multiple user-defined {@link Collection} buffers started each time an opening
	 * companion {@link Publisher} emits. Each buffer will last until the corresponding
	 * closing companion {@link Publisher} emits, thus releasing the buffer to the resulting {@link Flux}.
	 * <p>
	 * When Open signal is strictly not overlapping Close signal : dropping buffers (see green marbles in diagram below).
	 * <p>
	 * When Open signal is strictly more frequent than Close signal : overlapping buffers (see second and third buffers in diagram below).
	 * <p>
	 * <img class="marble" src="doc-files/marbles/bufferWhenWithSupplier.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the currently open buffer upon cancellation or error triggered by a data signal.
	 * It DOES NOT provide strong guarantees in the case of overlapping buffers, as elements
	 * might get discarded too early (from the first of two overlapping buffers for instance).
	 *
	 * @param bucketOpening a companion {@link Publisher} to subscribe for buffer creation signals.
	 * @param closeSelector a factory that, given a buffer opening signal, returns a companion
	 * {@link Publisher} to subscribe to for buffer closure and emission signals.
	 * @param bufferSupplier a {@link Supplier} of the concrete {@link Collection} to use for each buffer
	 * @param <U> the element type of the buffer-opening sequence
	 * @param <V> the element type of the buffer-closing sequence
	 * @param <C> the {@link Collection} buffer type
	 *
	 * @return a microbatched {@link Flux} of {@link Collection} delimited by an opening {@link Publisher} and a relative
	 * closing {@link Publisher}
	 */
	public final <U, V, C extends Collection<? super T>> Flux<C> bufferWhen(Publisher<U> bucketOpening,
			Function<? super U, ? extends Publisher<V>> closeSelector, Supplier<C> bufferSupplier) {
		return onAssembly(new FluxBufferWhen<>(this, bucketOpening, closeSelector,
				bufferSupplier, Queues.unbounded(Queues.XS_BUFFER_SIZE)));
	}

	/**
	 * Turn this {@link Flux} into a hot source and cache last emitted signals for further {@link Subscriber}. Will
	 * retain an unbounded volume of onNext signals. Completion and Error will also be
	 * replayed.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/cacheForFlux.svg" alt="">
	 *
	 * @return a replaying {@link Flux}
	 */
	public final Flux<T> cache() {
		return cache(Integer.MAX_VALUE);
	}

	/**
	 * Turn this {@link Flux} into a hot source and cache last emitted signals for further {@link Subscriber}.
	 * Will retain up to the given history size onNext signals. Completion and Error will also be
	 * replayed.
	 * <p>
	 *     Note that {@code cache(0)} will only cache the terminal signal without
	 *     expiration.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/cacheWithHistoryLimitForFlux.svg" alt="">
	 *
	 * @param history number of elements retained in cache
	 *
	 * @return a replaying {@link Flux}
	 *
	 */
	public final Flux<T> cache(int history) {
		return replay(history).autoConnect();
	}

	/**
	 * Turn this {@link Flux} into a hot source and cache last emitted signals for further
	 * {@link Subscriber}. Will retain an unbounded history but apply a per-item expiry timeout
	 * <p>
	 *   Completion and Error will also be replayed until {@code ttl} triggers in which case
	 *   the next {@link Subscriber} will start over a new subscription.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/cacheWithTtlForFlux.svg" alt="">
	 *
	 * @param ttl Time-to-live for each cached item and post termination.
	 *
	 * @return a replaying {@link Flux}
	 */
	public final Flux<T> cache(Duration ttl) {
		return cache(ttl, Schedulers.parallel());
	}

	/**
	 * Turn this {@link Flux} into a hot source and cache last emitted signals for further
	 * {@link Subscriber}. Will retain an unbounded history but apply a per-item expiry timeout
	 * <p>
	 *   Completion and Error will also be replayed until {@code ttl} triggers in which case
	 *   the next {@link Subscriber} will start over a new subscription.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/cacheWithTtlForFlux.svg" alt="">
	 *
	 * @param ttl Time-to-live for each cached item and post termination.
	 * @param timer the {@link Scheduler} on which to measure the duration.
	 *
	 * @return a replaying {@link Flux}
	 */
	public final Flux<T> cache(Duration ttl, Scheduler timer) {
		return cache(Integer.MAX_VALUE, ttl, timer);
	}

	/**
	 * Turn this {@link Flux} into a hot source and cache last emitted signals for further
	 * {@link Subscriber}. Will retain up to the given history size and apply a per-item expiry
	 * timeout.
	 * <p>
	 *   Completion and Error will also be replayed until {@code ttl} triggers in which case
	 *   the next {@link Subscriber} will start over a new subscription.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/cacheWithTtlAndMaxLimitForFlux.svg" alt="">
	 *
	 * @param history number of elements retained in cache
	 * @param ttl Time-to-live for each cached item and post termination.
	 *
	 * @return a replaying {@link Flux}
	 */
	public final Flux<T> cache(int history, Duration ttl) {
		return cache(history, ttl, Schedulers.parallel());
	}

	/**
	 * Turn this {@link Flux} into a hot source and cache last emitted signals for further
	 * {@link Subscriber}. Will retain up to the given history size and apply a per-item expiry
	 * timeout.
	 * <p>
	 *   Completion and Error will also be replayed until {@code ttl} triggers in which case
	 *   the next {@link Subscriber} will start over a new subscription.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/cacheWithTtlAndMaxLimitForFlux.svg"
	 * alt="">
	 *
	 * @param history number of elements retained in cache
	 * @param ttl Time-to-live for each cached item and post termination.
	 * @param timer the {@link Scheduler} on which to measure the duration.
	 *
	 * @return a replaying {@link Flux}
	 */
	public final Flux<T> cache(int history, Duration ttl, Scheduler timer) {
		return replay(history, ttl, timer).autoConnect();
	}

	/**
	 * Cast the current {@link Flux} produced type into a target produced type.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/castForFlux.svg" alt="">
	 *
	 * @param <E> the {@link Flux} output type
	 * @param clazz the target class to cast to
	 *
	 * @return a casted {@link Flux}
	 */
	public final <E> Flux<E> cast(Class<E> clazz) {
		Objects.requireNonNull(clazz, "clazz");
		return map(clazz::cast);
	}

	/**
	 * Prepare this {@link Flux} so that subscribers will cancel from it on a
	 * specified
	 * {@link Scheduler}.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/cancelOnForFlux.svg" alt="">
	 *
	 * @param scheduler the {@link Scheduler} to signal cancel  on
	 *
	 * @return a scheduled cancel {@link Flux}
	 */
	public final Flux<T> cancelOn(Scheduler scheduler) {
		return onAssembly(new FluxCancelOn<>(this, scheduler));
	}

	/**
	 * Activate traceback (full assembly tracing) for this particular {@link Flux}, in case of an error
	 * upstream of the checkpoint. Tracing incurs the cost of an exception stack trace
	 * creation.
	 * <p>
	 * It should be placed towards the end of the reactive chain, as errors
	 * triggered downstream of it cannot be observed and augmented with the traceback.
	 * <p>
	 * The traceback is attached to the error as a {@link Throwable#getSuppressed() suppressed exception}.
	 * As such, if the error is a {@link Exceptions#isMultiple(Throwable) composite one}, the traceback
	 * would appear as a component of the composite. In any case, the traceback nature can be detected via
	 * {@link Exceptions#isTraceback(Throwable)}.
	 *
	 * @return the assembly tracing {@link Flux}.
	 */
	public final Flux<T> checkpoint() {
		return checkpoint(null, true);
	}

	/**
	 * Activate traceback (assembly marker) for this particular {@link Flux} by giving it a description that
	 * will be reflected in the assembly traceback in case of an error upstream of the
	 * checkpoint. Note that unlike {@link #checkpoint()}, this doesn't create a
	 * filled stack trace, avoiding the main cost of the operator.
	 * However, as a trade-off the description must be unique enough for the user to find
	 * out where this Flux was assembled. If you only want a generic description, and
	 * still rely on the stack trace to find the assembly site, use the
	 * {@link #checkpoint(String, boolean)} variant.
	 * <p>
	 * It should be placed towards the end of the reactive chain, as errors
	 * triggered downstream of it cannot be observed and augmented with assembly trace.
	 * <p>
	 * The traceback is attached to the error as a {@link Throwable#getSuppressed() suppressed exception}.
	 * As such, if the error is a {@link Exceptions#isMultiple(Throwable) composite one}, the traceback
	 * would appear as a component of the composite. In any case, the traceback nature can be detected via
	 * {@link Exceptions#isTraceback(Throwable)}.
	 *
	 * @param description a unique enough description to include in the light assembly traceback.
	 * @return the assembly marked {@link Flux}
	 */
	public final Flux<T> checkpoint(String description) {
		return checkpoint(Objects.requireNonNull(description), false);
	}

	/**
	 * Activate traceback (full assembly tracing or the lighter assembly marking depending on the
	 * {@code forceStackTrace} option).
	 * <p>
	 * By setting the {@code forceStackTrace} parameter to {@literal true}, activate assembly
	 * tracing for this particular {@link Flux} and give it a description that
	 * will be reflected in the assembly traceback in case of an error upstream of the
	 * checkpoint. Note that unlike {@link #checkpoint(String)}, this will incur
	 * the cost of an exception stack trace creation. The description could for
	 * example be a meaningful name for the assembled flux or a wider correlation ID,
	 * since the stack trace will always provide enough information to locate where this
	 * Flux was assembled.
	 * <p>
	 * By setting {@code forceStackTrace} to {@literal false}, behaves like
	 * {@link #checkpoint(String)} and is subject to the same caveat in choosing the
	 * description.
	 * <p>
	 * It should be placed towards the end of the reactive chain, as errors
	 * triggered downstream of it cannot be observed and augmented with assembly marker.
	 * <p>
	 * The traceback is attached to the error as a {@link Throwable#getSuppressed() suppressed exception}.
	 * As such, if the error is a {@link Exceptions#isMultiple(Throwable) composite one}, the traceback
	 * would appear as a component of the composite. In any case, the traceback nature can be detected via
	 * {@link Exceptions#isTraceback(Throwable)}.
	 *
	 * @param description a description (must be unique enough if forceStackTrace is set
	 * to false).
	 * @param forceStackTrace false to make a light checkpoint without a stacktrace, true
	 * to use a stack trace.
	 * @return the assembly marked {@link Flux}.
	 */
	public final Flux<T> checkpoint( String description, boolean forceStackTrace) {
		final AssemblySnapshot stacktrace;
		if (!forceStackTrace) {
			stacktrace = new CheckpointLightSnapshot(description);
		}
		else {
			stacktrace = new CheckpointHeavySnapshot(description, Traces.callSiteSupplierFactory.get());
		}

		return new FluxOnAssembly<>(this, stacktrace);
	}

	/**
	 * Collect all elements emitted by this {@link Flux} into a user-defined container,
	 * by applying a collector {@link BiConsumer} taking the container and each element.
	 * The collected result will be emitted when this sequence completes, emitting the
	 * empty container if the sequence was empty.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/collect.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the container upon cancellation or error triggered by a data signal.
	 * Either the container type is a {@link Collection} (in which case individual elements are discarded)
	 * or not (in which case the entire container is discarded). In case the collector {@link BiConsumer} fails
	 * to accumulate an element, the container is discarded as above and the triggering element is also discarded.
	 *
	 * @param <E> the container type
	 * @param containerSupplier the supplier of the container instance for each Subscriber
	 * @param collector a consumer of both the container instance and the value being currently collected
	 *
	 * @return a {@link Mono} of the collected container on complete
	 *
	 */
	public final <E> Mono<E> collect(Supplier<E> containerSupplier, BiConsumer<E, ? super T> collector) {
		return Mono.onAssembly(new MonoCollect<>(this, containerSupplier, collector));
	}

	/**
	 * Collect all elements emitted by this {@link Flux} into a container,
	 * by applying a Java 8 Stream API {@link Collector}
	 * The collected result will be emitted when this sequence completes, emitting
	 * the empty container if the sequence was empty.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/collectWithCollector.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the intermediate container (see {@link Collector#supplier()}) upon
	 * cancellation, error or exception while applying the {@link Collector#finisher()}. Either the container type
	 * is a {@link Collection} (in which case individual elements are discarded) or not (in which case the entire
	 * container is discarded). In case the accumulator {@link BiConsumer} of the collector fails to accumulate
	 * an element into the intermediate container, the container is discarded as above and the triggering element
	 * is also discarded.
	 *
	 * @param collector the {@link Collector}
	 * @param <A> The mutable accumulation type
	 * @param <R> the container type
	 *
	 * @return a {@link Mono} of the collected container on complete
	 *
	 */
	public final <R, A> Mono<R> collect(Collector<? super T, A, ? extends R> collector) {
		return Mono.onAssembly(new MonoStreamCollector<>(this, collector));
	}

	/**
	 * Collect all elements emitted by this {@link Flux} into a {@link List} that is
	 * emitted by the resulting {@link Mono} when this sequence completes, emitting the
	 * empty {@link List} if the sequence was empty.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/collectList.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the elements in the {@link List} upon
	 * cancellation or error triggered by a data signal.
	 *
	 * @return a {@link Mono} of a {@link List} of all values from this {@link Flux}
	 */
	public final Mono<List<T>> collectList() {
		int characteristics = this.characteristics();
		if (Characteristics.isCallable(characteristics)) {
			if (Characteristics.isScalar(characteristics)) {
				@SuppressWarnings("unchecked")
				Fuseable.ScalarCallable<T> scalarCallable = (Fuseable.ScalarCallable<T>) this;

				T v;
				try {
					v = scalarCallable.call();
				}
				catch (Exception e) {
					return Mono.error(Exceptions.unwrap(e));
				}
				return Mono.onAssembly(new MonoCallable<>(() -> {
					List<T> list = Flux.<T>listSupplier().get();
					if (v != null) {
						list.add(v);
					}
					return list;
				}));

			}
			@SuppressWarnings("unchecked")
			Callable<T> thiz = (Callable<T>)this;
			return Mono.onAssembly(new MonoCallable<>(() -> {
				List<T> list = Flux.<T>listSupplier().get();
				T u = thiz.call();
				if (u != null) {
					list.add(u);
				}
				return list;
			}));
		}
		return Mono.onAssembly(new MonoCollectList<>(this));
	}

	/**
	 * Collect all elements emitted by this {@link Flux} into a hashed {@link Map} that is
	 * emitted by the resulting {@link Mono} when this sequence completes, emitting the
	 * empty {@link Map} if the sequence was empty.
	 * The key is extracted from each element by applying the {@code keyExtractor}
	 * {@link Function}. In case several elements map to the same key, the associated value
	 * will be the most recently emitted element.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/collectMapWithKeyExtractor.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the whole {@link Map} upon cancellation or error
	 * triggered by a data signal, so discard handlers will have to unpack the map.
	 *
	 * @param keyExtractor a {@link Function} to map elements to a key for the {@link Map}
	 * @param <K> the type of the key extracted from each source element
	 *
	 * @return a {@link Mono} of a {@link Map} of key-element pairs (only including latest
	 * element in case of key conflicts)
	 */
	public final <K> Mono<Map<K, T>> collectMap(Function<? super T, ? extends K> keyExtractor) {
		return collectMap(keyExtractor, identityFunction());
	}

	/**
	 * Collect all elements emitted by this {@link Flux} into a hashed {@link Map} that is
	 * emitted by the resulting {@link Mono} when this sequence completes, emitting the
	 * empty {@link Map} if the sequence was empty.
	 * The key is extracted from each element by applying the {@code keyExtractor}
	 * {@link Function}, and the value is extracted by the {@code valueExtractor} Function.
	 * In case several elements map to the same key, the associated value will be derived
	 * from the most recently emitted element.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/collectMapWithKeyAndValueExtractors.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the whole {@link Map} upon cancellation or error
	 * triggered by a data signal, so discard handlers will have to unpack the map.
	 *
	 * @param keyExtractor a {@link Function} to map elements to a key for the {@link Map}
	 * @param valueExtractor a {@link Function} to map elements to a value for the {@link Map}
	 *
	 * @param <K> the type of the key extracted from each source element
	 * @param <V> the type of the value extracted from each source element
	 *
	 * @return a {@link Mono} of a {@link Map} of key-element pairs (only including latest
	 * element's value in case of key conflicts)
	 */
	public final <K, V> Mono<Map<K, V>> collectMap(Function<? super T, ? extends K> keyExtractor,
			Function<? super T, ? extends V> valueExtractor) {
		return collectMap(keyExtractor, valueExtractor, () -> new HashMap<>());
	}

	/**
	 * Collect all elements emitted by this {@link Flux} into a user-defined {@link Map} that is
	 * emitted by the resulting {@link Mono} when this sequence completes, emitting the
	 * empty {@link Map} if the sequence was empty.
	 * The key is extracted from each element by applying the {@code keyExtractor}
	 * {@link Function}, and the value is extracted by the {@code valueExtractor} Function.
	 * In case several elements map to the same key, the associated value will be derived
	 * from the most recently emitted element.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/collectMapWithKeyAndValueExtractors.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the whole {@link Map} upon cancellation or error
	 * triggered by a data signal, so discard handlers will have to unpack the map.
	 *
	 * @param keyExtractor a {@link Function} to map elements to a key for the {@link Map}
	 * @param valueExtractor a {@link Function} to map elements to a value for the {@link Map}
	 * @param mapSupplier a {@link Map} factory called for each {@link Subscriber}
	 *
	 * @param <K> the type of the key extracted from each source element
	 * @param <V> the type of the value extracted from each source element
	 *
	 * @return a {@link Mono} of a {@link Map} of key-value pairs (only including latest
	 * element's value in case of key conflicts)
	 */
	public final <K, V> Mono<Map<K, V>> collectMap(
			final Function<? super T, ? extends K> keyExtractor,
			final Function<? super T, ? extends V> valueExtractor,
			Supplier<Map<K, V>> mapSupplier) {
		Objects.requireNonNull(keyExtractor, "Key extractor is null");
		Objects.requireNonNull(valueExtractor, "Value extractor is null");
		Objects.requireNonNull(mapSupplier, "Map supplier is null");
		return collect(mapSupplier, (m, d) -> m.put(keyExtractor.apply(d), valueExtractor.apply(d)));
	}

	/**
	 * Collect all elements emitted by this {@link Flux} into a {@link Map multimap} that is
	 * emitted by the resulting {@link Mono} when this sequence completes, emitting the
	 * empty {@link Map multimap} if the sequence was empty.
	 * The key is extracted from each element by applying the {@code keyExtractor}
	 * {@link Function}, and every element mapping to the same key is stored in the {@link List}
	 * associated to said key.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/collectMultiMapWithKeyExtractor.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the whole {@link Map} upon cancellation or error
	 * triggered by a data signal, so discard handlers will have to unpack the list values in the map.
	 *
	 * @param keyExtractor a {@link Function} to map elements to a key for the {@link Map}
	 *
	 * @param <K> the type of the key extracted from each source element
	 *
	 * @return a {@link Mono} of a {@link Map} of key-List(elements) pairs
	 */
	public final <K> Mono<Map<K, Collection<T>>> collectMultimap(Function<? super T, ? extends K> keyExtractor) {
		return collectMultimap(keyExtractor, identityFunction());
	}

	/**
	 * Collect all elements emitted by this {@link Flux} into a {@link Map multimap} that is
	 * emitted by the resulting {@link Mono} when this sequence completes, emitting the
	 * empty {@link Map multimap} if the sequence was empty.
	 * The key is extracted from each element by applying the {@code keyExtractor}
	 * {@link Function}, and every element mapping to the same key is converted by the
	 * {@code valueExtractor} Function to a value stored in the {@link List} associated to
	 * said key.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/collectMultiMapWithKeyAndValueExtractors.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the whole {@link Map} upon cancellation or error
	 * triggered by a data signal, so discard handlers will have to unpack the list values in the map.
	 *
	 * @param keyExtractor a {@link Function} to map elements to a key for the {@link Map}
	 * @param valueExtractor a {@link Function} to map elements to a value for the {@link Map}
	 *
	 * @param <K> the type of the key extracted from each source element
	 * @param <V> the type of the value extracted from each source element
	 *
	 * @return a {@link Mono} of a {@link Map} of key-List(values) pairs
	 */
	public final <K, V> Mono<Map<K, Collection<V>>> collectMultimap(Function<? super T, ? extends K> keyExtractor,
			Function<? super T, ? extends V> valueExtractor) {
		return collectMultimap(keyExtractor, valueExtractor, () -> new HashMap<>());
	}

	/**
	 * Collect all elements emitted by this {@link Flux} into a user-defined {@link Map multimap} that is
	 * emitted by the resulting {@link Mono} when this sequence completes, emitting the
	 * empty {@link Map multimap} if the sequence was empty.
	 * The key is extracted from each element by applying the {@code keyExtractor}
	 * {@link Function}, and every element mapping to the same key is converted by the
	 * {@code valueExtractor} Function to a value stored in the {@link Collection} associated to
	 * said key.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/collectMultiMapWithKeyAndValueExtractors.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the whole {@link Map} upon cancellation or error
	 * triggered by a data signal, so discard handlers will have to unpack the list values in the map.
	 *
	 * @param keyExtractor a {@link Function} to map elements to a key for the {@link Map}
	 * @param valueExtractor a {@link Function} to map elements to a value for the {@link Map}
	 * @param mapSupplier a multimap ({@link Map} of {@link Collection}) factory called
	 * for each {@link Subscriber}
	 *
	 * @param <K> the type of the key extracted from each source element
	 * @param <V> the type of the value extracted from each source element
	 *
	 * @return a {@link Mono} of a {@link Map} of key-Collection(values) pairs
	 *
	 */
	public final <K, V> Mono<Map<K, Collection<V>>> collectMultimap(
			final Function<? super T, ? extends K> keyExtractor,
			final Function<? super T, ? extends V> valueExtractor,
			Supplier<Map<K, Collection<V>>> mapSupplier) {
		Objects.requireNonNull(keyExtractor, "Key extractor is null");
		Objects.requireNonNull(valueExtractor, "Value extractor is null");
		Objects.requireNonNull(mapSupplier, "Map supplier is null");
		return collect(mapSupplier, (m, d) -> {
			K key = keyExtractor.apply(d);
			Collection<V> values = m.computeIfAbsent(key, k -> new ArrayList<>());
			values.add(valueExtractor.apply(d));
		});
	}

	/**
	 * Collect all elements emitted by this {@link Flux} until this sequence completes,
	 * and then sort them in natural order into a {@link List} that is emitted by the
	 * resulting {@link Mono}. If the sequence was empty, empty {@link List} will be emitted.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/collectSortedList.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator is based on {@link #collectList()}, and as such discards the
	 * elements in the {@link List} individually upon cancellation or error triggered by a data signal.
	 *
	 * @return a {@link Mono} of a sorted {@link List} of all values from this {@link Flux}, in natural order
	 */
	public final Mono<List<T>> collectSortedList() {
		return collectSortedList(null);
	}

	/**
	 * Collect all elements emitted by this {@link Flux} until this sequence completes,
	 * and then sort them using a {@link Comparator} into a {@link List} that is emitted
	 * by the resulting {@link Mono}. If the sequence was empty, empty {@link List} will be emitted.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/collectSortedListWithComparator.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator is based on {@link #collectList()}, and as such discards the
	 * elements in the {@link List} individually upon cancellation or error triggered by a data signal.
	 *
	 * @param comparator a {@link Comparator} to sort the items of this sequences
	 *
	 * @return a {@link Mono} of a sorted {@link List} of all values from this {@link Flux}
	 */
	public final Mono<List<T>> collectSortedList( Comparator<? super T> comparator) {
		return collectList().doOnNext(list -> {
			// Note: this assumes the list emitted by buffer() is mutable
			list.sort(comparator);
		});
	}

	/**
	 * Transform the elements emitted by this {@link Flux} asynchronously into Publishers,
	 * then flatten these inner publishers into a single {@link Flux}, sequentially and
	 * preserving order using concatenation.
	 * <p>
	 * There are three dimensions to this operator that can be compared with
	 * {@link #flatMap(Function) flatMap} and {@link #flatMapSequential(Function) flatMapSequential}:
	 * <ul>
	 *     <li><b>Generation of inners and subscription</b>: this operator waits for one
	 *     inner to complete before generating the next one and subscribing to it.</li>
	 *     <li><b>Ordering of the flattened values</b>: this operator naturally preserves
	 *     the same order as the source elements, concatenating the inners from each source
	 *     element sequentially.</li>
	 *     <li><b>Interleaving</b>: this operator does not let values from different inners
	 *     interleave (concatenation).</li>
	 * </ul>
	 *
	 * <p>
	 * Errors will immediately short circuit current concat backlog.
	 * Note that no prefetching is done on the source, which gets requested only if there
	 * is downstream demand.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatMap.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements it internally queued for backpressure upon cancellation.
	 *
	 * @param mapper the function to transform this sequence of T into concatenated sequences of V
	 * @param <V> the produced concatenated type
	 *
	 * @return a concatenated {@link Flux}
	 */
	public final <V> Flux<V> concatMap(Function<? super T, ? extends Publisher<? extends V>> mapper) {
		return onAssembly(new FluxConcatMapNoPrefetch<>(this, mapper, FluxConcatMap.ErrorMode.IMMEDIATE));
	}

	/**
	 * Transform the elements emitted by this {@link Flux} asynchronously into Publishers,
	 * then flatten these inner publishers into a single {@link Flux}, sequentially and
	 * preserving order using concatenation.
	 * <p>
	 * There are three dimensions to this operator that can be compared with
	 * {@link #flatMap(Function) flatMap} and {@link #flatMapSequential(Function) flatMapSequential}:
	 * <ul>
	 *     <li><b>Generation of inners and subscription</b>: this operator waits for one
	 *     inner to complete before generating the next one and subscribing to it.</li>
	 *     <li><b>Ordering of the flattened values</b>: this operator naturally preserves
	 *     the same order as the source elements, concatenating the inners from each source
	 *     element sequentially.</li>
	 *     <li><b>Interleaving</b>: this operator does not let values from different inners
	 *     interleave (concatenation).</li>
	 * </ul>
	 *
	 * <p>
	 * Errors will immediately short circuit current concat backlog. The prefetch argument
	 * allows to give an arbitrary prefetch size to the upstream source, or to disable
	 * prefetching with {@code 0}.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatMap.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements it internally queued for backpressure upon cancellation.
	 *
	 * @param mapper the function to transform this sequence of T into concatenated sequences of V
	 * @param prefetch the number of values to prefetch from upstream source, or {@code 0} to disable prefetching
	 * @param <V> the produced concatenated type
	 *
	 * @return a concatenated {@link Flux}
	 */
	public final <V> Flux<V> concatMap(Function<? super T, ? extends Publisher<? extends V>> mapper, int prefetch) {
		if (prefetch == 0) {
			return onAssembly(new FluxConcatMapNoPrefetch<>(this, mapper, FluxConcatMap.ErrorMode.IMMEDIATE));
		}
		return onAssembly(new FluxConcatMap<>(this, mapper, Queues.get(prefetch), prefetch,
				FluxConcatMap.ErrorMode.IMMEDIATE));
	}

	/**
	 * Transform the elements emitted by this {@link Flux} asynchronously into Publishers,
	 * then flatten these inner publishers into a single {@link Flux}, sequentially and
	 * preserving order using concatenation.
	 * <p>
	 * There are three dimensions to this operator that can be compared with
	 * {@link #flatMap(Function) flatMap} and {@link #flatMapSequential(Function) flatMapSequential}:
	 * <ul>
	 *     <li><b>Generation of inners and subscription</b>: this operator waits for one
	 *     inner to complete before generating the next one and subscribing to it.</li>
	 *     <li><b>Ordering of the flattened values</b>: this operator naturally preserves
	 *     the same order as the source elements, concatenating the inners from each source
	 *     element sequentially.</li>
	 *     <li><b>Interleaving</b>: this operator does not let values from different inners
	 *     interleave (concatenation).</li>
	 * </ul>
	 *
	 * <p>
	 * Errors in the individual publishers will be delayed at the end of the whole concat
	 * sequence (possibly getting combined into a {@link Exceptions#isMultiple(Throwable) composite})
	 * if several sources error.
	 * Note that no prefetching is done on the source, which gets requested only if there
	 * is downstream demand.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatMap.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements it internally queued for backpressure upon cancellation.
	 *
	 * @param mapper the function to transform this sequence of T into concatenated sequences of V
	 * @param <V> the produced concatenated type
	 *
	 * @return a concatenated {@link Flux}
	 *
	 */
	public final <V> Flux<V> concatMapDelayError(Function<? super T, ? extends Publisher<? extends V>> mapper) {
		return concatMapDelayError(mapper, 0);
	}

	/**
	 * Transform the elements emitted by this {@link Flux} asynchronously into Publishers,
	 * then flatten these inner publishers into a single {@link Flux}, sequentially and
	 * preserving order using concatenation.
	 * <p>
	 * There are three dimensions to this operator that can be compared with
	 * {@link #flatMap(Function) flatMap} and {@link #flatMapSequential(Function) flatMapSequential}:
	 * <ul>
	 *     <li><b>Generation of inners and subscription</b>: this operator waits for one
	 *     inner to complete before generating the next one and subscribing to it.</li>
	 *     <li><b>Ordering of the flattened values</b>: this operator naturally preserves
	 *     the same order as the source elements, concatenating the inners from each source
	 *     element sequentially.</li>
	 *     <li><b>Interleaving</b>: this operator does not let values from different inners
	 *     interleave (concatenation).</li>
	 * </ul>
	 *
	 * <p>
	 * Errors in the individual publishers will be delayed at the end of the whole concat
	 * sequence (possibly getting combined into a {@link Exceptions#isMultiple(Throwable) composite})
	 * if several sources error.
	 * The prefetch argument allows to give an arbitrary prefetch size to the upstream source,
	 * or to disable prefetching with {@code 0}.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatMap.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements it internally queued for backpressure upon cancellation.
	 *
	 * @param mapper the function to transform this sequence of T into concatenated sequences of V
	 * @param prefetch the number of values to prefetch from upstream source, or {@code 0} to disable prefetching
	 * @param <V> the produced concatenated type
	 *
	 * @return a concatenated {@link Flux}
	 *
	 */
	public final <V> Flux<V> concatMapDelayError(Function<? super T, ? extends Publisher<? extends V>> mapper, int prefetch) {
		return concatMapDelayError(mapper, true, prefetch);
	}

	/**
	 * Transform the elements emitted by this {@link Flux} asynchronously into Publishers,
	 * then flatten these inner publishers into a single {@link Flux}, sequentially and
	 * preserving order using concatenation.
	 * <p>
	 * There are three dimensions to this operator that can be compared with
	 * {@link #flatMap(Function) flatMap} and {@link #flatMapSequential(Function) flatMapSequential}:
	 * <ul>
	 *     <li><b>Generation of inners and subscription</b>: this operator waits for one
	 *     inner to complete before generating the next one and subscribing to it.</li>
	 *     <li><b>Ordering of the flattened values</b>: this operator naturally preserves
	 *     the same order as the source elements, concatenating the inners from each source
	 *     element sequentially.</li>
	 *     <li><b>Interleaving</b>: this operator does not let values from different inners
	 *     interleave (concatenation).</li>
	 * </ul>
	 *
	 * <p>
	 * Errors in the individual publishers will be delayed after the current concat
	 * backlog if delayUntilEnd is false or after all sources if delayUntilEnd is true.
	 * The prefetch argument allows to give an arbitrary prefetch size to the upstream source,
	 * or to disable prefetching with {@code 0}.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatMap.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements it internally queued for backpressure upon cancellation.
	 *
	 * @param mapper the function to transform this sequence of T into concatenated sequences of V
	 * @param delayUntilEnd delay error until all sources have been consumed instead of
	 * after the current source
	 * @param prefetch the number of values to prefetch from upstream source, or {@code 0} to disable prefetching
	 * @param <V> the produced concatenated type
	 *
	 * @return a concatenated {@link Flux}
	 *
	 */
	public final <V> Flux<V> concatMapDelayError(Function<? super T, ? extends Publisher<? extends V>> mapper,
												 boolean delayUntilEnd, int prefetch) {
		FluxConcatMap.ErrorMode errorMode = delayUntilEnd ? FluxConcatMap.ErrorMode.END : FluxConcatMap.ErrorMode.BOUNDARY;
		if (prefetch == 0) {
			return onAssembly(new FluxConcatMapNoPrefetch<>(this, mapper, errorMode));
		}
		return onAssembly(new FluxConcatMap<>(this, mapper, Queues.get(prefetch), prefetch, errorMode));
	}

	/**
	 * Transform the items emitted by this {@link Flux} into {@link Iterable}, then flatten the elements from those by
	 * concatenating them into a single {@link Flux}. For each iterable, {@link Iterable#iterator()} will be called
	 * at least once and at most twice.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatMapIterable.svg" alt="">
	 * <p>
	 * This operator inspects each {@link Iterable}'s {@link Spliterator} to assess if the iteration
	 * can be guaranteed to be finite (see {@link Operators#onDiscardMultiple(Iterator, boolean, Context)}).
	 * Since the default Spliterator wraps the Iterator we can have two {@link Iterable#iterator()}
	 * calls per iterable. This second invocation is skipped on a {@link Collection} however, a type which is
	 * assumed to be always finite.
	 * <p>
	 * Note that unlike {@link #flatMap(Function)} and {@link #concatMap(Function)}, with Iterable there is
	 * no notion of eager vs lazy inner subscription. The content of the Iterables are all played sequentially.
	 * Thus {@code flatMapIterable} and {@code concatMapIterable} are equivalent offered as a discoverability
	 * improvement for users that explore the API with the concat vs flatMap expectation.
	 *
	 * <p><strong>Discard Support:</strong> Upon cancellation, this operator discards {@code T} elements it prefetched and, in
	 * some cases, attempts to discard remainder of the currently processed {@link Iterable} (if it can
	 * safely ensure the iterator is finite). Note that this means each {@link Iterable}'s {@link Iterable#iterator()}
	 * method could be invoked twice.
	 *
	 * <p><strong>Error Mode Support:</strong> This operator supports {@link #onErrorContinue(BiConsumer) resuming on errors}
	 * (including when fusion is enabled). Exceptions thrown by the consumer are passed to
	 * the {@link #onErrorContinue(BiConsumer)} error consumer (the value consumer
	 * is not invoked, as the source element will be part of the sequence). The onNext
	 * signal is then propagated as normal.
	 *
	 * @param mapper the {@link Function} to transform input sequence into N {@link Iterable}
	 * @param <R> the merged output sequence type
	 *
	 * @return a concatenation of the values from the Iterables obtained from each element in this {@link Flux}
	 */
	public final <R> Flux<R> concatMapIterable(Function<? super T, ? extends Iterable<? extends R>> mapper) {
		return concatMapIterable(mapper, Queues.XS_BUFFER_SIZE);
	}

	/**
	 * Transform the items emitted by this {@link Flux} into {@link Iterable}, then flatten the emissions from those by
	 * concatenating them into a single {@link Flux}.
	 * The prefetch argument allows to give an arbitrary prefetch size to the upstream source.
	 * For each iterable, {@link Iterable#iterator()} will be called at least once and at most twice.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatMapIterable.svg" alt="">
	 * <p>
	 * This operator inspects each {@link Iterable}'s {@link Spliterator} to assess if the iteration
	 * can be guaranteed to be finite (see {@link Operators#onDiscardMultiple(Iterator, boolean, Context)}).
	 * Since the default Spliterator wraps the Iterator we can have two {@link Iterable#iterator()}
	 * calls per iterable. This second invocation is skipped on a {@link Collection} however, a type which is
	 * assumed to be always finite.
	 * <p>
	 * Note that unlike {@link #flatMap(Function)} and {@link #concatMap(Function)}, with Iterable there is
	 * no notion of eager vs lazy inner subscription. The content of the Iterables are all played sequentially.
	 * Thus {@code flatMapIterable} and {@code concatMapIterable} are equivalent offered as a discoverability
	 * improvement for users that explore the API with the concat vs flatMap expectation.
	 *
	 * <p><strong>Discard Support:</strong> Upon cancellation, this operator discards {@code T} elements it prefetched and, in
	 * some cases, attempts to discard remainder of the currently processed {@link Iterable} (if it can
	 * safely ensure the iterator is finite). Note that this means each {@link Iterable}'s {@link Iterable#iterator()}
	 * method could be invoked twice.
	 *
	 * <p><strong>Error Mode Support:</strong> This operator supports {@link #onErrorContinue(BiConsumer) resuming on errors}
	 * (including when fusion is enabled). Exceptions thrown by the consumer are passed to
	 * the {@link #onErrorContinue(BiConsumer)} error consumer (the value consumer
	 * is not invoked, as the source element will be part of the sequence). The onNext
	 * signal is then propagated as normal.
	 *
	 * @param mapper the {@link Function} to transform input sequence into N {@link Iterable}
	 * @param prefetch the number of values to request from the source upon subscription, to be transformed to {@link Iterable}
	 * @param <R> the merged output sequence type
	 *
	 * @return a concatenation of the values from the Iterables obtained from each element in this {@link Flux}
	 */
	public final <R> Flux<R> concatMapIterable(Function<? super T, ? extends Iterable<? extends R>> mapper,
			int prefetch) {
		return onAssembly(new FluxFlattenIterable<>(this, mapper, prefetch,
				Queues.get(prefetch)));
	}

	/**
	 * Concatenate emissions of this {@link Flux} with the provided {@link Publisher} (no interleave).
	 * <p>
	 * <img class="marble" src="doc-files/marbles/concatWithForFlux.svg" alt="">
	 *
	 * @param other the {@link Publisher} sequence to concat after this {@link Flux}
	 *
	 * @return a concatenated {@link Flux}
	 */
	public final Flux<T> concatWith(Publisher<? extends T> other) {
		if (this instanceof FluxConcatArray) {
			FluxConcatArray<T> fluxConcatArray = (FluxConcatArray<T>) this;

			return fluxConcatArray.concatAdditionalSourceLast(other);
		}
		return concat(this, other);
	}

	/**
	 * Evaluate each source value against the given {@link Predicate}. If the predicate test succeeds, the value is
	 * emitted. If the predicate test fails, the value is ignored and a request of 1 is made upstream.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/filterForFlux.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements that do not match the filter. It
	 * also discards elements internally queued for backpressure upon cancellation or error triggered by a data signal.
	 *
	 * <p><strong>Error Mode Support:</strong> This operator supports {@link #onErrorContinue(BiConsumer) resuming on errors}
	 * (including when fusion is enabled). Exceptions thrown by the predicate are
	 * considered as if the predicate returned false: they cause the source value to be
	 * dropped and a new element ({@code request(1)}) being requested from upstream.
	 *
	 * @param p the {@link Predicate} to test values against
	 *
	 * @return a new {@link Flux} containing only values that pass the predicate test
	 */
	public final Flux<T> filter(Predicate<? super T> p) {
		int characteristics = this.characteristics();
		if (Characteristics.isFuseable(characteristics)) {
			return onAssembly(new FluxFilterFuseable<>(this, p));
		}
		return onAssembly(new FluxFilter<>(this, p));
	}

	/**
	 * Transform the elements emitted by this {@link Flux} asynchronously into Publishers,
	 * then flatten these inner publishers into a single {@link Flux} through merging,
	 * which allow them to interleave.
	 * <p>
	 * There are three dimensions to this operator that can be compared with
	 * {@link #flatMapSequential(Function) flatMapSequential} and {@link #concatMap(Function) concatMap}:
	 * <ul>
	 *     <li><b>Generation of inners and subscription</b>: this operator is eagerly
	 *     subscribing to its inners.</li>
	 *     <li><b>Ordering of the flattened values</b>: this operator does not necessarily preserve
	 *     original ordering, as inner element are flattened as they arrive.</li>
	 *     <li><b>Interleaving</b>: this operator lets values from different inners interleave
	 *     (similar to merging the inner sequences).</li>
	 * </ul>
	 * <p>
	 * <img class="marble" src="doc-files/marbles/flatMapForFlux.svg" alt="">
	 * <p>
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements internally queued for backpressure upon cancellation or error triggered by a data signal.
	 *
	 * <p><strong>Error Mode Support:</strong> This operator supports {@link #onErrorContinue(BiConsumer) resuming on errors}
	 * in the mapper {@link Function}. Exceptions thrown by the mapper then behave as if
	 * it had mapped the value to an empty publisher. If the mapper does map to a scalar
	 * publisher (an optimization in which the value can be resolved immediately without
	 * subscribing to the publisher, e.g. a {@link Mono#fromCallable(Callable)}) but said
	 * publisher throws, this can be resumed from in the same manner.
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param <R> the merged output sequence type
	 *
	 * @return a new {@link Flux}
	 */
	public final <R> Flux<R> flatMap(Function<? super T, ? extends Publisher<? extends R>> mapper) {
		return flatMap(mapper, Queues.SMALL_BUFFER_SIZE, Queues
				.XS_BUFFER_SIZE);
	}

	/**
	 * Transform the elements emitted by this {@link Flux} asynchronously into Publishers,
	 * then flatten these inner publishers into a single {@link Flux} through merging,
	 * which allow them to interleave.
	 * <p>
	 * There are three dimensions to this operator that can be compared with
	 * {@link #flatMapSequential(Function) flatMapSequential} and {@link #concatMap(Function) concatMap}:
	 * <ul>
	 *     <li><b>Generation of inners and subscription</b>: this operator is eagerly
	 *     subscribing to its inners.</li>
	 *     <li><b>Ordering of the flattened values</b>: this operator does not necessarily preserve
	 *     original ordering, as inner element are flattened as they arrive.</li>
	 *     <li><b>Interleaving</b>: this operator lets values from different inners interleave
	 *     (similar to merging the inner sequences).</li>
	 * </ul>
	 * The concurrency argument allows to control how many {@link Publisher} can be
	 * subscribed to and merged in parallel. In turn, that argument shows the size of
	 * the first {@link Subscription#request} to the upstream.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/flatMapWithConcurrency.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements internally queued for backpressure upon cancellation or error triggered by a data signal.
	 *
	 * <p><strong>Error Mode Support:</strong> This operator supports {@link #onErrorContinue(BiConsumer) resuming on errors}
	 * in the mapper {@link Function}. Exceptions thrown by the mapper then behave as if
	 * it had mapped the value to an empty publisher. If the mapper does map to a scalar
	 * publisher (an optimization in which the value can be resolved immediately without
	 * subscribing to the publisher, e.g. a {@link Mono#fromCallable(Callable)}) but said
	 * publisher throws, this can be resumed from in the same manner.
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param concurrency the maximum number of in-flight inner sequences
	 *
	 * @param <V> the merged output sequence type
	 *
	 * @return a new {@link Flux}
	 */
	public final <V> Flux<V> flatMap(Function<? super T, ? extends Publisher<? extends V>> mapper, int
			concurrency) {
		return flatMap(mapper, concurrency, Queues.XS_BUFFER_SIZE);
	}

	/**
	 * Transform the elements emitted by this {@link Flux} asynchronously into Publishers,
	 * then flatten these inner publishers into a single {@link Flux} through merging,
	 * which allow them to interleave.
	 * <p>
	 * There are three dimensions to this operator that can be compared with
	 * {@link #flatMapSequential(Function) flatMapSequential} and {@link #concatMap(Function) concatMap}:
	 * <ul>
	 *     <li><b>Generation of inners and subscription</b>: this operator is eagerly
	 *     subscribing to its inners.</li>
	 *     <li><b>Ordering of the flattened values</b>: this operator does not necessarily preserve
	 *     original ordering, as inner element are flattened as they arrive.</li>
	 *     <li><b>Interleaving</b>: this operator lets values from different inners interleave
	 *     (similar to merging the inner sequences).</li>
	 * </ul>
	 * The concurrency argument allows to control how many {@link Publisher} can be
	 * subscribed to and merged in parallel. In turn, that argument shows the size of
	 * the first {@link Subscription#request} to the upstream.
	 * The prefetch argument allows to give an arbitrary prefetch size to the merged
	 * {@link Publisher} (in other words prefetch size means the size of the first
	 * {@link Subscription#request} to the merged {@link Publisher}).
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/flatMapWithConcurrencyAndPrefetch.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements internally queued for backpressure upon cancellation or error triggered by a data signal.
	 *
	 * <p><strong>Error Mode Support:</strong> This operator supports {@link #onErrorContinue(BiConsumer) resuming on errors}
	 * in the mapper {@link Function}. Exceptions thrown by the mapper then behave as if
	 * it had mapped the value to an empty publisher. If the mapper does map to a scalar
	 * publisher (an optimization in which the value can be resolved immediately without
	 * subscribing to the publisher, e.g. a {@link Mono#fromCallable(Callable)}) but said
	 * publisher throws, this can be resumed from in the same manner.
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param concurrency the maximum number of in-flight inner sequences
	 * @param prefetch the maximum in-flight elements from each inner {@link Publisher} sequence
	 *
	 * @param <V> the merged output sequence type
	 *
	 * @return a merged {@link Flux}
	 */
	public final <V> Flux<V> flatMap(Function<? super T, ? extends Publisher<? extends V>> mapper, int
			concurrency, int prefetch) {
		return flatMap(mapper, false, concurrency, prefetch);
	}

	/**
	 * Transform the elements emitted by this {@link Flux} asynchronously into Publishers,
	 * then flatten these inner publishers into a single {@link Flux} through merging,
	 * which allow them to interleave.
	 * <p>
	 * There are three dimensions to this operator that can be compared with
	 * {@link #flatMapSequential(Function) flatMapSequential} and {@link #concatMap(Function) concatMap}:
	 * <ul>
	 *     <li><b>Generation of inners and subscription</b>: this operator is eagerly
	 *     subscribing to its inners.</li>
	 *     <li><b>Ordering of the flattened values</b>: this operator does not necessarily preserve
	 *     original ordering, as inner element are flattened as they arrive.</li>
	 *     <li><b>Interleaving</b>: this operator lets values from different inners interleave
	 *     (similar to merging the inner sequences).</li>
	 * </ul>
	 * The concurrency argument allows to control how many {@link Publisher} can be
	 * subscribed to and merged in parallel. The prefetch argument allows to give an
	 * arbitrary prefetch size to the merged {@link Publisher}. This variant will delay
	 * any error until after the rest of the flatMap backlog has been processed.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/flatMapWithConcurrencyAndPrefetch.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements internally queued for backpressure upon cancellation or error triggered by a data signal.
	 *
	 * <p><strong>Error Mode Support:</strong> This operator supports {@link #onErrorContinue(BiConsumer) resuming on errors}
	 * in the mapper {@link Function}. Exceptions thrown by the mapper then behave as if
	 * it had mapped the value to an empty publisher. If the mapper does map to a scalar
	 * publisher (an optimization in which the value can be resolved immediately without
	 * subscribing to the publisher, e.g. a {@link Mono#fromCallable(Callable)}) but said
	 * publisher throws, this can be resumed from in the same manner.
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param concurrency the maximum number of in-flight inner sequences
	 * @param prefetch the maximum in-flight elements from each inner {@link Publisher} sequence
	 *
	 * @param <V> the merged output sequence type
	 *
	 * @return a merged {@link Flux}
	 */
	public final <V> Flux<V> flatMapDelayError(Function<? super T, ? extends Publisher<? extends V>> mapper,
			int concurrency, int prefetch) {
		return flatMap(mapper, true, concurrency, prefetch);
	}

	/**
	 * Transform the items emitted by this {@link Flux} into {@link Iterable}, then flatten the elements from those by
	 * merging them into a single {@link Flux}. For each iterable, {@link Iterable#iterator()} will be called at least
	 * once and at most twice.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/flatMapIterableForFlux.svg" alt="">
	 * <p>
	 * This operator inspects each {@link Iterable}'s {@link Spliterator} to assess if the iteration
	 * can be guaranteed to be finite (see {@link Operators#onDiscardMultiple(Iterator, boolean, Context)}).
	 * Since the default Spliterator wraps the Iterator we can have two {@link Iterable#iterator()}
	 * calls per iterable. This second invocation is skipped on a {@link Collection} however, a type which is
	 * assumed to be always finite.
	 * <p>
	 * Note that unlike {@link #flatMap(Function)} and {@link #concatMap(Function)}, with Iterable there is
	 * no notion of eager vs lazy inner subscription. The content of the Iterables are all played sequentially.
	 * Thus {@code flatMapIterable} and {@code concatMapIterable} are equivalent offered as a discoverability
	 * improvement for users that explore the API with the concat vs flatMap expectation.
	 *
	 * <p><strong>Discard Support:</strong> Upon cancellation, this operator discards {@code T} elements it prefetched and, in
	 * some cases, attempts to discard remainder of the currently processed {@link Iterable} (if it can
	 * safely ensure the iterator is finite). Note that this means each {@link Iterable}'s {@link Iterable#iterator()}
	 * method could be invoked twice.
	 *
	 * <p><strong>Error Mode Support:</strong> This operator supports {@link #onErrorContinue(BiConsumer) resuming on errors}
	 * (including when fusion is enabled). Exceptions thrown by the consumer are passed to
	 * the {@link #onErrorContinue(BiConsumer)} error consumer (the value consumer
	 * is not invoked, as the source element will be part of the sequence). The onNext
	 * signal is then propagated as normal.
	 *
	 * @param mapper the {@link Function} to transform input sequence into N {@link Iterable}
	 *
	 * @param <R> the merged output sequence type
	 *
	 * @return a concatenation of the values from the Iterables obtained from each element in this {@link Flux}
	 */
	public final <R> Flux<R> flatMapIterable(Function<? super T, ? extends Iterable<? extends R>> mapper) {
		return flatMapIterable(mapper, Queues.SMALL_BUFFER_SIZE);
	}

	/**
	 * Transform the items emitted by this {@link Flux} into {@link Iterable}, then flatten the emissions from those by
	 * merging them into a single {@link Flux}. The prefetch argument allows to give an
	 * arbitrary prefetch size to the upstream source.
	 * For each iterable, {@link Iterable#iterator()} will be called at least once and at most twice.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/flatMapIterableForFlux.svg" alt="">
	 * <p>
	 * This operator inspects each {@link Iterable}'s {@link Spliterator} to assess if the iteration
	 * can be guaranteed to be finite (see {@link Operators#onDiscardMultiple(Iterator, boolean, Context)}).
	 * Since the default Spliterator wraps the Iterator we can have two {@link Iterable#iterator()}
	 * calls per iterable. This second invocation is skipped on a {@link Collection} however, a type which is
	 * assumed to be always finite.
	 * <p>
	 * Note that unlike {@link #flatMap(Function)} and {@link #concatMap(Function)}, with Iterable there is
	 * no notion of eager vs lazy inner subscription. The content of the Iterables are all played sequentially.
	 * Thus {@code flatMapIterable} and {@code concatMapIterable} are equivalent offered as a discoverability
	 * improvement for users that explore the API with the concat vs flatMap expectation.
	 *
	 * <p><strong>Discard Support:</strong> Upon cancellation, this operator discards {@code T} elements it prefetched and, in
	 * some cases, attempts to discard remainder of the currently processed {@link Iterable} (if it can
	 * safely ensure the iterator is finite).
	 * Note that this means each {@link Iterable}'s {@link Iterable#iterator()} method could be invoked twice.
	 *
	 * <p><strong>Error Mode Support:</strong> This operator supports {@link #onErrorContinue(BiConsumer) resuming on errors}
	 * (including when fusion is enabled). Exceptions thrown by the consumer are passed to
	 * the {@link #onErrorContinue(BiConsumer)} error consumer (the value consumer
	 * is not invoked, as the source element will be part of the sequence). The onNext
	 * signal is then propagated as normal.
	 *
	 * @param mapper the {@link Function} to transform input sequence into N {@link Iterable}
	 * @param prefetch the number of values to request from the source upon subscription, to be transformed to {@link Iterable}
	 *
	 * @param <R> the merged output sequence type
	 *
	 * @return a concatenation of the values from the Iterables obtained from each element in this {@link Flux}
	 */
	public final <R> Flux<R> flatMapIterable(Function<? super T, ? extends Iterable<? extends R>> mapper, int prefetch) {
		return onAssembly(new FluxFlattenIterable<>(this, mapper, prefetch,
				Queues.get(prefetch)));
	}

	/**
	 * The prefetch configuration of the {@link Flux}
	 * @return the prefetch configuration of the {@link Flux}, -1 if unspecified
	 */
	public int getPrefetch() {
		return -1;
	}

	/**
	 * Take only the first N values from this {@link Flux}, if available.
	 * Furthermore, ensure that the total amount requested upstream is capped at {@code n}.
	 * If n is zero, the source isn't even subscribed to and the operator completes immediately
	 * upon subscription.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/takeLimitRequestTrue.svg" alt="">
	 * <p>
	 * Backpressure signals from downstream subscribers are smaller than the cap are
	 * propagated as is, but if they would cause the total requested amount to go over the
	 * cap, they are reduced to the minimum value that doesn't go over.
	 * <p>
	 * As a result, this operator never let the upstream produce more elements than the
	 * cap.
	 * Typically useful for cases where a race between request and cancellation can lead the upstream to
	 * producing a lot of extraneous data, and such a production is undesirable (e.g.
	 * a source that would send the extraneous data over the network).
	 *
	 * @param n the number of elements to emit from this flux, which is also the backpressure
	 * cap for all of downstream's request
	 *
	 * @return a {@link Flux} of {@code n} elements from the source, that requests AT MOST {@code n} from upstream in total.
	 * @see #take(long)
	 * @see #take(long, boolean)
	 * @deprecated replace with {@link #take(long, boolean) take(n, true)} in 3.4.x, then {@link #take(long)} in 3.5.0.
	 * To be removed in 3.6.0 at the earliest. See https://github.com/reactor/reactor-core/issues/2339
	 */
	@Deprecated
	public final Flux<T> limitRequest(long n) {
		return take(n, true);
	}

	/**
	 * Transform the items emitted by this {@link Flux} by applying a synchronous function
	 * to each item.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/mapForFlux.svg" alt="">
	 *
	 * <p><strong>Error Mode Support:</strong> This operator supports {@link #onErrorContinue(BiConsumer) resuming on errors}
	 * (including when fusion is enabled). Exceptions thrown by the mapper then cause the
	 * source value to be dropped and a new element ({@code request(1)}) being requested
	 * from upstream.
	 *
	 * @param mapper the synchronous transforming {@link Function}
	 *
	 * @param <V> the transformed type
	 *
	 * @return a transformed {@link Flux}
	 */
	public final <V> Flux<V> map(Function<? super T, ? extends V> mapper) {
		int characteristics = this.characteristics();
		if (Characteristics.isFuseable(characteristics)) {
			return onAssembly(new FluxMapFuseable<>(this, mapper));
		}
		return onAssembly(new FluxMap<>(this, mapper));
	}

	/**
	 * Evaluate each accepted value against the given {@link Class} type. If the
	 * value matches the type, it is passed into the resulting {@link Flux}. Otherwise
	 * the value is ignored and a request of 1 is emitted.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/ofTypeForFlux.svg" alt="">
	 *
	 * @param clazz the {@link Class} type to test values against
	 *
	 * @return a new {@link Flux} filtered on items of the requested type
	 */
	public final <U> Flux<U> ofType(final Class<U> clazz) {
			Objects.requireNonNull(clazz, "clazz");
			return filter(o -> clazz.isAssignableFrom(o.getClass())).cast(clazz);
	}

	/**
	 * Reduce the values from this {@link Flux} sequence into a single object of the same
	 * type than the emitted items. Reduction is performed using a {@link BiFunction} that
	 * takes the intermediate result of the reduction and the current value and returns
	 * the next intermediate value of the reduction. Note, {@link BiFunction} will not
	 * be invoked for a sequence with 0 or 1 elements. In case of one element's
	 * sequence, the result will be directly sent to the subscriber.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/reduceWithSameReturnType.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards the internally accumulated value upon cancellation or error.
	 *
	 * @param aggregator the reducing {@link BiFunction}
	 *
	 * @return a reduced {@link Flux}
	 */
	public final Mono<T> reduce(BiFunction<T, T, T> aggregator) {
		int characteristics = this.characteristics();
		if (Characteristics.isCallable(characteristics)) {
			@SuppressWarnings("unchecked")
			Callable<T> thiz = (Callable<T>)this;
		    return Mono.onAssembly(wrapToMono(thiz, characteristics));
		}
	    return Mono.onAssembly(new MonoReduce<>(this, aggregator));
	}

	/**
	 * Returns the appropriate Mono instance for a known Supplier Flux, WITHOUT applying hooks
	 * (see {@link #wrap(Publisher)}).
	 *
	 * @param supplier the supplier Flux
	 *
	 * @return the mono representing that Flux
	 */
	static <T> Mono<T> wrapToMono(Callable<T> supplier, int characteristics) {
		if (Characteristics.isScalar(characteristics) || supplier instanceof Fuseable.ScalarCallable) {
			Fuseable.ScalarCallable<T> scalarCallable = (Fuseable.ScalarCallable<T>) supplier;

			T v;
			try {
				v = scalarCallable.call();
			}
			catch (Exception e) {
				return new MonoError<>(Exceptions.unwrap(e));
			}
			if (v == null) {
				return MonoEmpty.instance();
			}
			return new MonoJust<>(v);
		}
		return new MonoCallable<>(supplier);
	}

	/**
	 * Skip the specified number of elements from the beginning of this {@link Flux} then
	 * emit the remaining elements.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/skip.svg" alt="">
	 *
	 * <p><strong>Discard Support:</strong> This operator discards elements that are skipped.
	 *
	 * @param skipped the number of elements to drop
	 *
	 * @return a dropping {@link Flux} with the specified number of elements skipped at
	 * the beginning
	 */
	public final Flux<T> skip(long skipped) {
		if (skipped == 0L) {
			return this;
		}
		else {
			return onAssembly(new FluxSkip<>(this, skipped));
		}
	}

	/**
	 * Subscribe to this {@link Flux} and request unbounded demand.
	 * <p>
	 * This version doesn't specify any consumption behavior for the events from the
	 * chain, especially no error handling, so other variants should usually be preferred.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/subscribeIgoringAllSignalsForFlux.svg" alt="">
	 *
	 * @return a new {@link Disposable} that can be used to cancel the underlying {@link Subscription}
	 */
	public final Disposable subscribe() {
		return subscribe(null, null, null);
	}

	/**
	 * Subscribe a {@link Consumer} to this {@link Flux} that will consume all the
	 * elements in the  sequence. It will request an unbounded demand ({@code Long.MAX_VALUE}).
	 * <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(Consumer)}.
	 * <p>
	 * For a version that gives you more control over backpressure and the request, see
	 * {@link #subscribe(Subscriber)} with a {@link BaseSubscriber}.
	 * <p>
	 * Keep in mind that since the sequence can be asynchronous, this will immediately
	 * return control to the calling thread. This can give the impression the consumer is
	 * not invoked when executing in a main thread or a unit test for instance.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/subscribeWithOnNextForFlux.svg" alt="">
	 *
	 * @param consumer the consumer to invoke on each value (onNext signal)
	 *
	 * @return a new {@link Disposable} that can be used to cancel the underlying {@link Subscription}
	 */
	public final Disposable subscribe(Consumer<? super T> consumer) {
		Objects.requireNonNull(consumer, "consumer");
		return subscribe(consumer, null, null);
	}

	/**
	 * Subscribe to this {@link Flux} with a {@link Consumer} that will consume all the
	 * elements in the sequence, as well as a {@link Consumer} that will handle errors.
	 * The subscription will request an unbounded demand ({@code Long.MAX_VALUE}).
	 * <p>
	 * For a passive version that observe and forward incoming data see
	 * {@link #doOnNext(Consumer)} and {@link #doOnError(Consumer)}.
	 * <p>For a version that gives you more control over backpressure and the request, see
	 * {@link #subscribe(Subscriber)} with a {@link BaseSubscriber}.
	 * <p>
	 * Keep in mind that since the sequence can be asynchronous, this will immediately
	 * return control to the calling thread. This can give the impression the consumers are
	 * not invoked when executing in a main thread or a unit test for instance.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/subscribeWithOnNextAndOnErrorForFlux.svg" alt="">
	 *
	 * @param consumer the consumer to invoke on each next signal
	 * @param errorConsumer the consumer to invoke on error signal
	 *
	 * @return a new {@link Disposable} that can be used to cancel the underlying {@link Subscription}
	 */
	public final Disposable subscribe( Consumer<? super T> consumer, Consumer<? super Throwable> errorConsumer) {
		Objects.requireNonNull(errorConsumer, "errorConsumer");
		return subscribe(consumer, errorConsumer, null);
	}

	/**
	 * Subscribe {@link Consumer} to this {@link Flux} that will respectively consume all the
	 * elements in the sequence, handle errors and react to completion. The subscription
	 * will request unbounded demand ({@code Long.MAX_VALUE}).
	 * <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(Consumer)},
	 * {@link #doOnError(Consumer)} and {@link #doOnComplete(Runnable)}.
	 * <p>For a version that gives you more control over backpressure and the request, see
	 * {@link #subscribe(Subscriber)} with a {@link BaseSubscriber}.
	 * <p>
	 * Keep in mind that since the sequence can be asynchronous, this will immediately
	 * return control to the calling thread. This can give the impression the consumer is
	 * not invoked when executing in a main thread or a unit test for instance.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/subscribeWithOnNextAndOnErrorAndOnCompleteForFlux.svg" alt="">
	 *
	 * @param consumer the consumer to invoke on each value
	 * @param errorConsumer the consumer to invoke on error signal
	 * @param completeConsumer the consumer to invoke on complete signal
	 *
	 * @return a new {@link Disposable} that can be used to cancel the underlying {@link Subscription}
	 */
	public final Disposable subscribe(
			 Consumer<? super T> consumer,
			 Consumer<? super Throwable> errorConsumer,
			 Runnable completeConsumer) {
		return subscribe(consumer, errorConsumer, completeConsumer, (Context) null);
	}

	/**
	 * Subscribe {@link Consumer} to this {@link Flux} that will respectively consume all the
	 * elements in the sequence, handle errors, react to completion, and request upon subscription.
	 * It will let the provided {@link Subscription subscriptionConsumer}
	 * request the adequate amount of data, or request unbounded demand
	 * {@code Long.MAX_VALUE} if no such consumer is provided.
	 * <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(Consumer)},
	 * {@link #doOnError(Consumer)}, {@link #doOnComplete(Runnable)}
	 * and {@link #doOnSubscribe(Consumer)}.
	 * <p>For a version that gives you more control over backpressure and the request, see
	 * {@link #subscribe(Subscriber)} with a {@link BaseSubscriber}.
	 * <p>
	 * Keep in mind that since the sequence can be asynchronous, this will immediately
	 * return control to the calling thread. This can give the impression the consumer is
	 * not invoked when executing in a main thread or a unit test for instance.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/subscribeForFlux.svg" alt="">
	 *
	 * @param consumer the consumer to invoke on each value
	 * @param errorConsumer the consumer to invoke on error signal
	 * @param completeConsumer the consumer to invoke on complete signal
	 * @param subscriptionConsumer the consumer to invoke on subscribe signal, to be used
	 * for the initial {@link Subscription#request(long) request}, or null for max request
	 *
	 * @return a new {@link Disposable} that can be used to cancel the underlying {@link Subscription}
	 * @deprecated Because users tend to forget to {@link Subscription#request(long) request} the subsciption. If
	 * the behavior is really needed, consider using {@link #subscribeWith(Subscriber)}. To be removed in 3.5.
	 */
	@Deprecated
	public final Disposable subscribe(
			 Consumer<? super T> consumer,
			 Consumer<? super Throwable> errorConsumer,
			 Runnable completeConsumer,
			 Consumer<? super Subscription> subscriptionConsumer) {
		return subscribeWith(new LambdaSubscriber<>(consumer, errorConsumer,
				completeConsumer,
				subscriptionConsumer,
				null));
	}

	/**
	 * Subscribe {@link Consumer} to this {@link Flux} that will respectively consume all the
	 * elements in the sequence, handle errors and react to completion. Additionally, a {@link Context}
	 * is tied to the subscription. At subscription, an unbounded request is implicitly made.
	 * <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(Consumer)},
	 * {@link #doOnError(Consumer)}, {@link #doOnComplete(Runnable)}
	 * and {@link #doOnSubscribe(Consumer)}.
	 * <p>For a version that gives you more control over backpressure and the request, see
	 * {@link #subscribe(Subscriber)} with a {@link BaseSubscriber}.
	 * <p>
	 * Keep in mind that since the sequence can be asynchronous, this will immediately
	 * return control to the calling thread. This can give the impression the consumer is
	 * not invoked when executing in a main thread or a unit test for instance.
	 *
	 * <p>
	 * <img class="marble" src="doc-files/marbles/subscribeForFlux.svg" alt="">
	 *
	 * @param consumer the consumer to invoke on each value
	 * @param errorConsumer the consumer to invoke on error signal
	 * @param completeConsumer the consumer to invoke on complete signal
	 * @param initialContext the base {@link Context} tied to the subscription that will
	 * be visible to operators upstream
	 *
	 * @return a new {@link Disposable} that can be used to cancel the underlying {@link Subscription}
	 */
	public final Disposable subscribe(
			 Consumer<? super T> consumer,
			 Consumer<? super Throwable> errorConsumer,
			 Runnable completeConsumer,
			 Context initialContext) {
		return subscribeWith(new LambdaSubscriber<>(consumer, errorConsumer,
				completeConsumer,
				null,
				initialContext));
	}

	@Override
	@SuppressWarnings("unchecked")
	public final void subscribe(Subscriber<? super T> actual) {
		CorePublisher publisher = Operators.onLastAssembly(this);
		CoreSubscriber subscriber = Operators.toCoreSubscriber(actual);

		int sourceCharacteristics = this.characteristics();
		int characteristics = publisher.characteristics();

		if (this != publisher && subscriber instanceof Fuseable.QueueSubscription && Characteristics.isFuseable(sourceCharacteristics) && !(Characteristics.isFuseable(characteristics))) {
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
	 * @see Flux#subscribe(Subscriber)
	 */
	public abstract void subscribe(CoreSubscriber<? super T> actual);

	/**
	 * Subscribe a provided instance of a subclass of {@link Subscriber} to this {@link Flux}
	 * and return said instance for further chaining calls. This is similar to {@link #as(Function)},
	 * except a subscription is explicitly performed by this method.
	 * <p>
	 * If you need more control over backpressure and the request, use a {@link BaseSubscriber}.
	 *
	 * @param subscriber the {@link Subscriber} to subscribe with and return
	 * @param <E> the reified type from the input/output subscriber
	 *
	 * @return the passed {@link Subscriber}
	 */
	public final <E extends Subscriber<? super T>> E subscribeWith(E subscriber) {
		subscribe(subscriber);
		return subscriber;
	}

	/**
	 * Take only the first N values from this {@link Flux}, if available.
	 * If n is zero, the source isn't even subscribed to and the operator completes immediately upon subscription.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/takeLimitRequestTrue.svg" alt="">
	 * <p>
	 * This ensures that the total amount requested upstream is capped at {@code n}, although smaller
	 * requests can be made if the downstream makes requests &lt; n. In any case, this operator never lets
	 * the upstream produce more elements than the cap, and it can be used to more strictly adhere to backpressure.
	 * <p>
	 * This mode is typically useful for cases where a race between request and cancellation can lead
	 * the upstream to producing a lot of extraneous data, and such a production is undesirable (e.g.
	 * a source that would send the extraneous data over the network).
	 * It is equivalent to {@link #take(long, boolean)} with {@code limitRequest == true},
	 * If there is a requirement for unbounded upstream request (eg. for performance reasons),
	 * use {@link #take(long, boolean)} with {@code limitRequest=false} instead.
	 *
	 * @param n the maximum number of items to request from upstream and emit from this {@link Flux}
	 *
	 * @return a {@link Flux} limited to size N
	 * @see #take(long, boolean)
	 */
	public final Flux<T> take(long n) {
		return take(n, true);
	}

	/**
	 * Take only the first N values from this {@link Flux}, if available.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/takeLimitRequestTrue.svg" alt="">
	 * <p>
	 * If {@code limitRequest == true}, ensure that the total amount requested upstream is capped
	 * at {@code n}. In that configuration, this operator never let the upstream produce more elements
	 * than the cap, and it can be used to more strictly adhere to backpressure.
	 * If n is zero, the source isn't even subscribed to and the operator completes immediately
	 * upon subscription (the behavior inherited from {@link #take(long)}).
	 * <p>
	 * This mode is typically useful for cases where a race between request and cancellation can lead
	 * the upstream to producing a lot of extraneous data, and such a production is undesirable (e.g.
	 * a source that would send the extraneous data over the network).
	 * <p>
	 * <img class="marble" src="doc-files/marbles/takeLimitRequestFalse.svg" alt="takeLimitRequestFalse">
	 * <p>
	 * If {@code limitRequest == false} this operator doesn't propagate the backpressure requested amount.
	 * Rather, it makes an unbounded request and cancels once N elements have been emitted.
	 * If n is zero, the source is subscribed to but immediately cancelled, then the operator completes.
	 * <p>
	 * In this mode, the source could produce a lot of extraneous elements despite cancellation.
	 * If that behavior is undesirable and you do not own the request from downstream
	 * (e.g. prefetching operators), consider using {@code limitRequest = true} instead.
	 *
	 * @param n the number of items to emit from this {@link Flux}
	 * @param limitRequest {@code true} to follow the downstream request more closely and limit the upstream request
	 * to {@code n}. {@code false} to request an unbounded amount from upstream.
	 *
	 * @return a {@link Flux} limited to size N
	 */
	public final Flux<T> take(long n, boolean limitRequest) {
		if (limitRequest) {
			return onAssembly(new FluxLimitRequest<>(this, n));
		}
		int characteristics = this.characteristics();
		if (Characteristics.isFuseable(characteristics)) {
			return onAssembly(new FluxTakeFuseable<>(this, n));
		}
		return onAssembly(new FluxTake<>(this, n));
	}

	/**
	 * To be used by custom operators: invokes assembly {@link Hooks} pointcut given a
	 * {@link Flux}, potentially returning a new {@link Flux}. This is for example useful
	 * to activate cross-cutting concerns at assembly time, eg. a generalized
	 * {@link #checkpoint()}.
	 *
	 * @param <T> the value type
	 * @param source the source to apply assembly hooks onto
	 *
	 * @return the source, potentially wrapped with assembly time cross-cutting behavior
	 */
	@SuppressWarnings("unchecked")
	protected static <T> Flux<T> onAssembly(Flux<T> source) {
		Function<Publisher, Publisher> hook = Hooks.onEachOperatorHook;
		if(hook != null) {
			source = (Flux<T>) hook.apply(source);
		}
		if (Hooks.GLOBAL_TRACE) {
			AssemblySnapshot
					stacktrace = new AssemblySnapshot(null, Traces.callSiteSupplierFactory.get());
			source = (Flux<T>) Hooks.addAssemblyInfo(source, stacktrace);
		}
		return source;
	}

	/**
	 * To be used by custom operators: invokes assembly {@link Hooks} pointcut given a
	 * {@link ConnectableFlux}, potentially returning a new {@link ConnectableFlux}. This
	 * is for example useful to activate cross-cutting concerns at assembly time, eg. a
	 * generalized {@link #checkpoint()}.
	 *
	 * @param <T> the value type
	 * @param source the source to apply assembly hooks onto
	 *
	 * @return the source, potentially wrapped with assembly time cross-cutting behavior
	 */
	@SuppressWarnings("unchecked")
	protected static <T> ConnectableFlux<T> onAssembly(ConnectableFlux<T> source) {
		Function<Publisher, Publisher> hook = Hooks.onEachOperatorHook;
		if(hook != null) {
			source = (ConnectableFlux<T>) hook.apply(source);
		}
		if (Hooks.GLOBAL_TRACE) {
			AssemblySnapshot stacktrace = new AssemblySnapshot(null, Traces.callSiteSupplierFactory.get());
			source = (ConnectableFlux<T>) Hooks.addAssemblyInfo(source, stacktrace);
		}
		return source;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName();
	}


	final <V> Flux<V> flatMap(Function<? super T, ? extends Publisher<? extends
			V>> mapper, boolean delayError, int concurrency, int prefetch) {
		return onAssembly(new FluxFlatMap<>(
				this,
				mapper,
				delayError,
				concurrency,
				Queues.get(concurrency),
				prefetch,
				Queues.get(prefetch)
		));
	}

	static BooleanSupplier countingBooleanSupplier(BooleanSupplier predicate, long max) {
		if (max <= 0) {
			return predicate;
		}
		return new BooleanSupplier() {
			long n;

			@Override
			public boolean getAsBoolean() {
				return n++ < max && predicate.getAsBoolean();
			}
		};
	}

	static <O> Predicate<O> countingPredicate(Predicate<O> predicate, long max) {
		if (max == 0) {
			return predicate;
		}
		return new Predicate<O>() {
			long n;

			@Override
			public boolean test(O o) {
				return n++ < max && predicate.test(o);
			}
		};
	}

	@SuppressWarnings("unchecked")
	static <O> Supplier<Set<O>> hashSetSupplier() {
		return SET_SUPPLIER;
	}

	@SuppressWarnings("unchecked")
	static <O> Supplier<List<O>> listSupplier() {
		return LIST_SUPPLIER;
	}

	@SuppressWarnings("unchecked")
	static <U, V> BiPredicate<U, V> equalPredicate() {
		return OBJECT_EQUAL;
	}

	@SuppressWarnings("unchecked")
	static <T> Function<T, T> identityFunction(){
		return IDENTITY_FUNCTION;
	}

	@SuppressWarnings("unchecked")
	static <A, B> BiFunction<A, B, Tuple2<A, B>> tuple2Function() {
		return TUPLE2_BIFUNCTION;
	}

	/**
	 * Unchecked wrap of {@link Publisher} as {@link Flux}, supporting {@link Fuseable} sources.
	 * Note that this bypasses {@link Hooks#onEachOperator(String, Function) assembly hooks}.
	 *
	 * @param source the {@link Publisher} to wrap
	 * @param <I> input upstream type
	 * @return a wrapped {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	static <I> Flux<I> wrap(Publisher<? extends I> source) {
		if (source instanceof  Flux) {
			return (Flux<I>) source;
		}

		//for scalars we'll instantiate the operators directly to avoid onAssembly
		if (source instanceof Fuseable.ScalarCallable) {
			try {
				@SuppressWarnings("unchecked") I t =
						((Fuseable.ScalarCallable<I>) source).call();
				if (t != null) {
					return new FluxJust<>(t);
				}
				return FluxEmpty.instance();
			}
			catch (Exception e) {
				return new FluxError<>(Exceptions.unwrap(e));
			}
		}

		if(source instanceof Mono) {
			int characteristics = ((Mono<? extends I>) source).characteristics();
			if (Characteristics.isFuseable(characteristics)) {
				return new FluxSourceMonoFuseable<>((Mono<I>)source);
			}
			return new FluxSourceMono<>((Mono<I>)source);
		}
		if(source instanceof Fuseable){
			return new FluxSourceFuseable<>(source);
		}
		return new FluxSource<>(source);
	}

	@SuppressWarnings("rawtypes")
	static final BiFunction      TUPLE2_BIFUNCTION       = Tuples::of;
	@SuppressWarnings("rawtypes")
	static final Supplier        LIST_SUPPLIER           = ArrayList::new;
	@SuppressWarnings("rawtypes")
	static final Supplier        SET_SUPPLIER            = HashSet::new;
	static final BooleanSupplier ALWAYS_BOOLEAN_SUPPLIER = () -> true;
	@SuppressWarnings("rawtypes")
	static final BiPredicate     OBJECT_EQUAL            = Object::equals;
	@SuppressWarnings("rawtypes")
	static final Function        IDENTITY_FUNCTION       = Function.identity();

}
