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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.test.reactor_new_api.concurrent.Queues;
import org.test.reactor_new_api.context.Context;
import org.test.reactor_new_api.function.Tuple2;
import org.test.reactor_new_api.function.Tuples;
import org.test.reactor_original.FluxOnAssembly.AssemblySnapshot;
import org.test.reactor_original.FluxSink.OverflowStrategy;

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
	 * Programmatically create a {@link Flux} with the capability of emitting multiple
	 * elements in a synchronous or asynchronous manner through the {@link FluxSink} API.
	 * This includes emitting elements from multiple threads.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/createForFlux.svg" alt="">
	 * <p>
	 * This Flux factory is useful if one wants to adapt some other multi-valued async API
	 * and not worry about cancellation and backpressure (which is handled by buffering
	 * all signals if the downstream can't keep up).
	 * <p>
	 * For example:
	 *
	 * <pre><code>
	 * Flux.&lt;String&gt;create(emitter -&gt; {
	 *
	 *     ActionListener al = e -&gt; {
	 *         emitter.next(textField.getText());
	 *     };
	 *     // without cleanup support:
	 *
	 *     button.addActionListener(al);
	 *
	 *     // with cleanup support:
	 *
	 *     button.addActionListener(al);
	 *     emitter.onDispose(() -> {
	 *         button.removeListener(al);
	 *     });
	 * });
	 * </code></pre>
	 *
	 * <p><strong>Discard Support:</strong> The {@link FluxSink} exposed by this operator buffers in case of
	 * overflow. The buffer is discarded when the main sequence is cancelled.
	 *
	 * @param <T> The type of values in the sequence
	 * @param emitter Consume the {@link FluxSink} provided per-subscriber by Reactor to generate signals.
	 * @return a {@link Flux}
	 * @see #push(Consumer)
	 */
	public static <T> Flux<T> create(Consumer<? super FluxSink<T>> emitter) {
		return create(emitter, OverflowStrategy.BUFFER);
	}

	/**
	 * Programmatically create a {@link Flux} with the capability of emitting multiple
	 * elements in a synchronous or asynchronous manner through the {@link FluxSink} API.
	 * This includes emitting elements from multiple threads.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/createWithOverflowStrategy.svg" alt="">
	 * <p>
	 * This Flux factory is useful if one wants to adapt some other multi-valued async API
	 * and not worry about cancellation and backpressure (which is handled by buffering
	 * all signals if the downstream can't keep up).
	 * <p>
	 * For example:
	 *
	 * <pre><code>
	 * Flux.&lt;String&gt;create(emitter -&gt; {
	 *
	 *     ActionListener al = e -&gt; {
	 *         emitter.next(textField.getText());
	 *     };
	 *     // without cleanup support:
	 *
	 *     button.addActionListener(al);
	 *
	 *     // with cleanup support:
	 *
	 *     button.addActionListener(al);
	 *     emitter.onDispose(() -> {
	 *         button.removeListener(al);
	 *     });
	 * }, FluxSink.OverflowStrategy.LATEST);
	 * </code></pre>
	 *
	 * <p><strong>Discard Support:</strong> The {@link FluxSink} exposed by this operator discards elements
	 * as relevant to the chosen {@link OverflowStrategy}. For example, the {@link OverflowStrategy#DROP}
	 * discards each items as they are being dropped, while {@link OverflowStrategy#BUFFER}
	 * will discard the buffer upon cancellation.
	 *
	 * @param <T> The type of values in the sequence
	 * @param backpressure the backpressure mode, see {@link OverflowStrategy} for the
	 * available backpressure modes
	 * @param emitter Consume the {@link FluxSink} provided per-subscriber by Reactor to generate signals.
	 * @return a {@link Flux}
	 * @see #push(Consumer, reactor.core.publisher.FluxSink.OverflowStrategy)
	 */
	public static <T> Flux<T> create(Consumer<? super FluxSink<T>> emitter, OverflowStrategy backpressure) {
		return onAssembly(new FluxCreate<>(emitter, backpressure, FluxCreate.CreateMode.PUSH_PULL));
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
		if (this instanceof Callable) {
			if (this instanceof Fuseable.ScalarCallable) {
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
		return onAssembly(new FluxMap<>(this, mapper));
	}

	/**
	 * Emit only the first item emitted by this {@link Flux}, into a new {@link Mono}.
	 * If called on an empty {@link Flux}, emits an empty {@link Mono}.
	 * <p>
	 * <img class="marble" src="doc-files/marbles/next.svg" alt="">
	 *
	 * @return a new {@link Mono} emitting the first value in this {@link Flux}
	 */
	public final Mono<T> next() {
		if(this instanceof Callable){
			@SuppressWarnings("unchecked")
			Callable<T> m = (Callable<T>)this;
			return Mono.onAssembly(wrapToMono(m));
		}
		return Mono.onAssembly(new MonoNext<>(this));
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
		if (this instanceof Callable){
			@SuppressWarnings("unchecked")
			Callable<T> thiz = (Callable<T>)this;
		    return Mono.onAssembly(wrapToMono(thiz));
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
	static <T> Mono<T> wrapToMono(Callable<T> supplier) {
		if (supplier instanceof Fuseable.ScalarCallable) {
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

		if (subscriber instanceof Fuseable.QueueSubscription && this != publisher && this instanceof Fuseable
        && !(publisher instanceof Fuseable)) {
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
		if (this instanceof Fuseable) {
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

		if(source instanceof Mono){
			if(source instanceof Fuseable){
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
